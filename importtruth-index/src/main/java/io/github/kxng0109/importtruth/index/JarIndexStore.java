package io.github.kxng0109.importtruth.index;

import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One immutable database per library, filed by content hash. Concurrent
 * requests for the same library share a single indexing run. Pure Java
 * storage: no native libraries, nothing for application policies to block.
 */
public final class JarIndexStore implements AutoCloseable {

	private final Path baseDir;
	private final ConcurrentHashMap<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Path, ShaEntry> shas = new ConcurrentHashMap<>();
	private final Map<Path, Connection> readers = new HashMap<>();
	private boolean closed;

	/**
	 * Creates the store.
	 *
	 * @param baseDir directory holding index files, never null
	 * @throws IOException when the directory cannot be created
	 * @throws NullPointerException when {@code baseDir} is {@code null}
	 */
	public JarIndexStore(Path baseDir) throws IOException {
		Objects.requireNonNull(baseDir, "baseDir");
		Files.createDirectories(baseDir);
		this.baseDir = baseDir;
	}

	/**
	 * Hashes the jar, reusing the digest while the size and timestamp
	 * stamp agrees. One small entry per jar path; sessions touch
	 * dozens of jars, so no eviction is needed.
	 */
	private String shaOf(Path jar) throws IOException {
		Path key = jar.toAbsolutePath().normalize();
		String stamp = Files.getLastModifiedTime(jar).toMillis() + ":" + Files.size(jar);
		ShaEntry remembered = shas.get(key);
		if (remembered != null && remembered.stamp().equals(stamp)) {
			return remembered.sha();
		}
		String sha = Sha256.ofFile(jar);
		shas.put(key, new ShaEntry(stamp, sha));
		return sha;
	}

	private record ShaEntry(String stamp, String sha) {
	}

	/**
	 * Returns the index for the library, indexing it first when absent.
	 *
	 * @param jar     library file, never null
	 * @param indexer extractor, never null
	 * @return index file path, never null
	 * @throws IOException when the library cannot be read or stored
	 */
	public Path ensureIndexed(Path jar, LibraryIndexer indexer) throws IOException {
		Objects.requireNonNull(jar, "jar");
		Objects.requireNonNull(indexer, "indexer");
		String sha = shaOf(jar);
		Path target = dbFile(sha);
		if (Files.exists(target)) {
			return target;
		}
		CompletableFuture<Path> future = new CompletableFuture<>();
		CompletableFuture<Path> existing = inFlight.putIfAbsent(sha, future);
		if (existing != null) {
			return existing.join();
		}
		try {
			List<Symbol> symbols = indexer.index(jar);
			write(sha, jar.getFileName().toString(), symbols);
			future.complete(target);
			return target;
		} catch (Throwable failure) {
			future.completeExceptionally(failure);
			if (failure instanceof IOException io) {
				throw io;
			}
			throw new IOException("Indexing failed for " + jar, failure);
		} finally {
			inFlight.remove(sha, future);
		}
	}

	/**
	 * Finds exact-name symbols in one index.
	 *
	 * @param db  index file, never null
	 * @param fqn fully qualified name, never null
	 * @return matches, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> findIn(Path db, String fqn) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(fqn, "fqn");
		String sql = "SELECT fqn, kind, signature, parent_fqn, deprecated, deprecated_since, for_removal"
				+ " FROM symbols WHERE fqn = ? ORDER BY fqn";
		synchronized (this) {
			try (PreparedStatement query = readerFor(db).prepareStatement(sql)) {
				query.setString(1, fqn);
				try (ResultSet rows = query.executeQuery()) {
					return readAll(rows);
				}
			} catch (SQLException failure) {
				throw new IOException("Query failed on " + db, failure);
			}
		}
	}

	/**
	 * Finds exact-name symbols across indexes, in order.
	 *
	 * @param dbs index files, never null
	 * @param fqn fully qualified name, never null
	 * @return matches in index order, never null
	 * @throws IOException when an index cannot be read
	 */
	public List<Symbol> findAcross(List<Path> dbs, String fqn) throws IOException {
		Objects.requireNonNull(dbs, "dbs");
		Objects.requireNonNull(fqn, "fqn");
		List<Symbol> matches = new ArrayList<>();
		for (Path db : dbs) {
			matches.addAll(findIn(db, fqn));
		}
		return matches;
	}

	/**
	 * Searches names by substring, most specific first.
	 *
	 * @param db    index file, never null
	 * @param query substring, never null
	 * @param limit maximum rows, positive
	 * @return matches ordered by name length, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> searchIn(Path db, String query, int limit) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(query, "query");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		String sql = "SELECT fqn, kind, signature, parent_fqn, deprecated, deprecated_since, for_removal"
				+ " FROM symbols WHERE fqn LIKE ? ESCAPE '\\' ORDER BY LENGTH(fqn), fqn LIMIT ?";
		synchronized (this) {
			try (PreparedStatement statement = readerFor(db).prepareStatement(sql)) {
				statement.setString(1, "%" + escapeLike(query) + "%");
				statement.setInt(2, limit);
				try (ResultSet rows = statement.executeQuery()) {
					return readAll(rows);
				}
			} catch (SQLException failure) {
				throw new IOException("Search failed on " + db, failure);
			}
		}
	}

	private Path dbFile(String sha) {
		return baseDir.resolve(sha + ".mv.db");
	}

	private void write(String sha, String artifact, List<Symbol> symbols) throws IOException {
		String tmpBase = baseDir.resolve(sha + ".tmp").toAbsolutePath().toString();
		String url = "jdbc:h2:file:" + tmpBase + ";LOCK_TIMEOUT=5000";
		try (Connection connection = DriverManager.getConnection(url)) {
			try (Statement schema = connection.createStatement()) {
				schema.execute("CREATE TABLE jar_info(sha256 VARCHAR(64) PRIMARY KEY, artifact VARCHAR(1024) NOT NULL)");
				schema.execute("CREATE TABLE symbols(fqn VARCHAR(1024) NOT NULL, kind VARCHAR(16) NOT NULL,"
						+ " signature VARCHAR(2048), parent_fqn VARCHAR(1024), deprecated BOOLEAN NOT NULL,"
						+ " deprecated_since VARCHAR(64) NOT NULL, for_removal BOOLEAN NOT NULL)");
				schema.execute("CREATE INDEX symbols_fqn ON symbols(fqn)");
			}
			connection.setAutoCommit(false);
			try (PreparedStatement info =
							connection.prepareStatement("INSERT INTO jar_info(sha256, artifact) VALUES (?, ?)");
					PreparedStatement row = connection.prepareStatement(
							"INSERT INTO symbols(fqn, kind, signature, parent_fqn,"
									+ " deprecated, deprecated_since, for_removal) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
				info.setString(1, sha);
				info.setString(2, artifact);
				info.executeUpdate();
				for (Symbol symbol : symbols) {
					row.setString(1, symbol.fqn());
					row.setString(2, symbol.kind().name());
					row.setString(3, symbol.signature());
					row.setString(4, symbol.parentFqn());
					row.setBoolean(5, symbol.deprecated());
					row.setString(6, symbol.deprecatedSince());
					row.setBoolean(7, symbol.forRemoval());
					row.addBatch();
				}
				row.executeBatch();
				connection.commit();
			} catch (SQLException failure) {
				connection.rollback();
				throw failure;
			} finally {
				connection.setAutoCommit(true);
			}
		} catch (SQLException failure) {
			throw new IOException("Index write failed for " + sha, failure);
		}
		Path tmpFile = baseDir.resolve(sha + ".tmp.mv.db");
		publishTmp(tmpFile, dbFile(sha));
	}

	/**
	 * Publishes a finished temp file. The first publisher wins: same-sha
	 * content is byte-identical, so a loser must never clobber a winner.
	 *
	 * @param tmpFile finished temp file, never null
	 * @param target  final location, never null
	 * @throws IOException when publishing fails and no file exists
	 */
	static void publishTmp(Path tmpFile, Path target) throws IOException {
		Objects.requireNonNull(tmpFile, "tmpFile");
		Objects.requireNonNull(target, "target");
		if (Files.exists(target)) {
			Files.deleteIfExists(tmpFile);
			return;
		}
		try {
			Files.move(tmpFile, target, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomic) {
			Files.deleteIfExists(tmpFile);
			if (Files.exists(target)) {
				return;
			}
			throw atomic;
		}
	}

	/**
	 * Returns the shared read connection for one index file,
	 * opening it on first use. Callers synchronize on the store.
	 */
	private Connection readerFor(Path db) throws SQLException {
		if (closed) {
			throw new SQLException("Index store is closed");
		}
		Connection reader = readers.get(db);
		if (reader == null) {
			reader = connectRead(db);
			readers.put(db, reader);
		}
		return reader;
	}

	/**
	 * Closes every shared read connection and releases the index files.
	 *
	 * @throws SQLException when a connection cannot close
	 */
	@Override
	public void close() throws SQLException {
		synchronized (this) {
			closed = true;
			for (Connection reader : readers.values()) {
				reader.close();
			}
			readers.clear();
		}
	}

	private static Connection connectRead(Path db) throws SQLException {
		String path = db.toAbsolutePath().toString();
		String base = path.endsWith(".mv.db") ? path.substring(0, path.length() - ".mv.db".length()) : path;
		return DriverManager.getConnection("jdbc:h2:file:" + base + ";LOCK_TIMEOUT=5000;ACCESS_MODE_DATA=r");
	}

	private static List<Symbol> readAll(ResultSet rows) throws SQLException {
		List<Symbol> symbols = new ArrayList<>();
		while (rows.next()) {
			symbols.add(
					new Symbol(
							rows.getString(1),
							SymbolKind.valueOf(rows.getString(2)),
							rows.getString(3),
							rows.getString(4),
							rows.getBoolean(5),
							rows.getString(6),
							rows.getBoolean(7)));
		}
		return symbols;
	}

	private static String escapeLike(String query) {
		return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
