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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
	private final ConcurrentHashMap<Path, Connection> readers = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Path, Object> guards = new ConcurrentHashMap<>();
	private final Map<Path, SegmentSets> segments = new LinkedHashMap<>();
	private volatile boolean closed;

	/**
	 * Maximum databases with cached segment sets. Content addressed
	 * paths make sets immutable, so eldest eviction only re-reads.
	 */
	static final int MAX_SEGMENT_DBS = 64;

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
		String stamp = JarStamp.key(jar);
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
			try {
				return existing.join();
			} catch (RuntimeException follower) {
				throw new IOException("Indexing failed for " + jar, follower.getCause());
			}
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
		return queryList(db, sql, "Query failed on ", statement -> statement.setString(1, fqn));
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
	 * Tests whether one index holds the name, stopping at the first
	 * row. Cheaper than {@link #findIn} when only existence matters:
	 * no ordering, no row materialization.
	 *
	 * @param db index file, never null
	 * @param fqn fully qualified name, never null
	 * @return true when present
	 * @throws IOException when the index cannot be read
	 */
	public boolean existsIn(Path db, String fqn) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(fqn, "fqn");
		String sql = "SELECT 1 FROM symbols WHERE fqn = ? LIMIT 1";
		synchronized (guardFor(db)) {
			try (PreparedStatement statement = readerFor(db).prepareStatement(sql)) {
				statement.setString(1, fqn);
				try (ResultSet rows = statement.executeQuery()) {
					return rows.next();
				}
			} catch (SQLException failure) {
				throw new IOException("Query failed on " + db, failure);
			}
		}
	}

	/**
	 * Tests whether any index holds the name, stopping at the first
	 * hit instead of scanning every index.
	 *
	 * @param dbs index files, never null
	 * @param fqn fully qualified name, never null
	 * @return true when present anywhere
	 * @throws IOException when an index cannot be read
	 */
	public boolean existsAcross(List<Path> dbs, String fqn) throws IOException {
		Objects.requireNonNull(dbs, "dbs");
		Objects.requireNonNull(fqn, "fqn");
		for (Path db : dbs) {
			if (existsIn(db, fqn)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tests whether one index might match the substring, skipping
	 * databases whose every name segment misses it. Segment sets are
	 * derived from content addressed index files, so a set never goes
	 * stale: new content means a new path. Suggestion paths only;
	 * existence verdicts never consult this set.
	 *
	 * @param db index file, never null
	 * @param query substring, never null
	 * @param insensitive true for case insensitive matching
	 * @return false only when no segment can match, otherwise true
	 * @throws IOException when the index cannot be read
	 */
	public boolean maybeMatches(Path db, String query, boolean insensitive) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(query, "query");
		SegmentSets sets;
		synchronized (segments) {
			sets = segments.get(db);
		}
		if (sets == null) {
			SegmentSets loaded;
			synchronized (guardFor(db)) {
				loaded = loadSegments(db);
			}
			synchronized (segments) {
				if (segments.size() >= MAX_SEGMENT_DBS) {
					segments.remove(segments.keySet().iterator().next());
				}
				segments.put(db, loaded);
			}
			sets = loaded;
		}
		Set<String> parts = insensitive ? sets.lower() : sets.exact();
		String needle = insensitive ? query.toLowerCase(Locale.ROOT) : query;
		for (String part : parts) {
			if (part.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private record SegmentSets(Set<String> exact, Set<String> lower) {
	}

	private SegmentSets loadSegments(Path db) throws IOException {
		String sql = "SELECT fqn FROM symbols";
		Set<String> exact = new HashSet<>();
		Set<String> lower = new HashSet<>();
		try (PreparedStatement statement = readerFor(db).prepareStatement(sql);
				ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				for (String part : rows.getString(1).split("\\.")) {
					exact.add(part);
					lower.add(part.toLowerCase(Locale.ROOT));
				}
			}
		} catch (SQLException failure) {
			throw new IOException("Segment read failed on " + db, failure);
		}
		return new SegmentSets(Set.copyOf(exact), Set.copyOf(lower));
	}

	/**
	 * Searches names by prefix, most specific first. Without a leading
	 * wildcard the name index can serve the range directly.
	 *
	 * @param db index file, never null
	 * @param prefix leading characters, never null
	 * @param limit maximum rows, positive
	 * @return matches ordered by name length, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> searchPrefix(Path db, String prefix, int limit) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(prefix, "prefix");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		// Existence probes stop at the first row: sorting only burns
		// scans. Ordered output is kept for larger limits.
		String sql = limit == 1
				? "SELECT fqn, kind, signature, parent_fqn, deprecated, deprecated_since, for_removal"
						+ " FROM symbols WHERE fqn LIKE ? ESCAPE '\\' LIMIT ?"
				: "SELECT fqn, kind, signature, parent_fqn, deprecated, deprecated_since, for_removal"
						+ " FROM symbols WHERE fqn LIKE ? ESCAPE '\\' ORDER BY LENGTH(fqn), fqn LIMIT ?";
		return queryList(db, sql, "Search failed on ", statement -> {
			statement.setString(1, escapeLike(prefix) + "%");
			statement.setInt(2, limit);
		});
	}
	/**
	 * Searches names by substring, most specific first.
	 *
	 * @param db index file, never null
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
		return queryList(db, sql, "Search failed on ", statement -> {
			statement.setString(1, "%" + escapeLike(query) + "%");
			statement.setInt(2, limit);
		});
	}

	/**
	 * Searches names by substring without regard to case.
	 *
	 * @param db index file, never null
	 * @param query substring, never null
	 * @param limit maximum rows, positive
	 * @return matches ordered by name length, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> searchInInsensitive(Path db, String query, int limit) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(query, "query");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		String sql = "SELECT fqn, kind, signature, parent_fqn, deprecated, deprecated_since, for_removal"
				+ " FROM symbols WHERE fqn ILIKE ? ESCAPE '\\' ORDER BY LENGTH(fqn), fqn LIMIT ?";
		return queryList(db, sql, "Search failed on ", statement -> {
			statement.setString(1, "%" + escapeLike(query) + "%");
			statement.setInt(2, limit);
		});
	}

	private List<Symbol> queryList(Path db, String sql, String failurePrefix, Binder binder)
			throws IOException {
		synchronized (guardFor(db)) {
			try (PreparedStatement statement = readerFor(db).prepareStatement(sql)) {
				binder.bind(statement);
				try (ResultSet rows = statement.executeQuery()) {
					return readAll(rows);
				}
			} catch (SQLException failure) {
				throw new IOException(failurePrefix + db, failure);
			}
		}
	}

	/**
	 * Returns the guard for one index file. One guard per file keeps
	 * databases independent: a slow scan on one index never blocks
	 * reads on another. Guards live only while their database is open.
	 *
	 * @param db index file, never null
	 * @return the guard, never null
	 */
	private Object guardFor(Path db) {
		return guards.computeIfAbsent(db, key -> new Object());
	}

	private interface Binder {

		void bind(PreparedStatement statement) throws SQLException;
	}

	private Path dbFile(String sha) {
		return baseDir.resolve(sha + ".mv.db");
	}

	private void write(String sha, String artifact, List<Symbol> symbols) throws IOException {
		// Unique tmp per writer: two processes indexing one sha must
		// never interleave pages in a shared file. First finished
		// publish still wins; losers delete only their own tmp.
		String nonce = Thread.currentThread().threadId() + "." + System.nanoTime();
		String tmpBase = baseDir.resolve(sha + ".tmp." + nonce).toAbsolutePath().toString();
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
		Path tmpFile = baseDir.resolve(sha + ".tmp." + nonce + ".mv.db");
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
	 * opening it on first use. Callers hold that file's guard, so one
	 * connection never serves two threads at once.
	 */
	private Connection readerFor(Path db) throws SQLException {
		if (closed) {
			throw new SQLException("Index store is closed");
		}
		Connection reader = readers.get(db);
		if (reader == null) {
			reader = connectRead(db);
			Connection raced = readers.putIfAbsent(db, reader);
			if (raced != null) {
				reader.close();
				reader = raced;
			}
		}
		return reader;
	}

	/**
	 * Closes every shared read connection and releases the index files.
	 * New queries fail fast once closing starts; in flight queries on
	 * one file drain before that file closes.
	 *
	 * @throws SQLException when a connection cannot close
	 */
	@Override
	public void close() throws SQLException {
		closed = true;
		for (Path db : List.copyOf(readers.keySet())) {
			synchronized (guardFor(db)) {
				Connection reader = readers.remove(db);
				guards.remove(db);
				if (reader != null) {
					reader.close();
				}
			}
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
