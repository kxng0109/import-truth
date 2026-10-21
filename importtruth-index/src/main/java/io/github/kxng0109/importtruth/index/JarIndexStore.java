package io.github.kxng0109.importtruth.index;

import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

/**
 * One immutable index per library, filed by content fingerprint. Each
 * index is a single sorted file that is read once into memory and then
 * queried without any file system call, so any number of threads read
 * it at once with no lock and no database engine. Pure Java storage:
 * no native libraries, nothing for application policies to block.
 *
 * <p>The file is a {@value #HEADER} byte header followed by the
 * payload: the names, the name offset table, the metadata rows, the
 * descriptor blob and the since values. The header holds the magic,
 * the version, the row count, the length of the name region, the
 * offset of every region, one CRC32C over the whole payload, and the
 * checksum algorithm.
 *
 * <p>The checksum covers every byte of the payload, so a flipped byte
 * in a name, in an offset, in a metadata row, in a descriptor or in a
 * since value is caught when the file is read. A damaged index is
 * never answered from: it is dropped and rebuilt. The header is pinned
 * by the equations that tie its fields to the payload, so a flipped
 * header field is caught as well. The checksum is computed when the
 * file is written and when it is loaded, never on the query path.
 */
public final class JarIndexStore implements AutoCloseable {

	/** Magic bytes at the head of every index file. */
	private static final int MAGIC = 0x49545449;

	/** Format version inside the file. */
	private static final int VERSION = 4;

	/** Header size in bytes. Exposed so the format tests can craft files. */
	static final int HEADER = 52;

	/** Bytes per symbol in the metadata region. Exposed for the format tests. */
	static final int META = 18;

	/** Checksum algorithm id for CRC32C. */
	static final int CHECKSUM_CRC32C = 1;

	/** Longest name an index row accepts. */
	private static final int MAX_NAME = 1024;

	/** Longest descriptor an index row accepts. */
	private static final int MAX_SIGNATURE = 2048;

	/**
	 * Largest index file the store will read into memory. Not final: a
	 * test lowers it.
	 */
	static long maxIndexBytes = 64L * 1024 * 1024;

	/** Index format tag, part of the file name. */
	static final String FORMAT = "v4";

	/** Symbol kinds by ordinal, so a row never allocates the array. */
	private static final SymbolKind[] KINDS = SymbolKind.values();

	/**
	 * Milliseconds a follower waits for the indexing leader before it
	 * gives up. Not final: a test lowers it.
	 */
	static long followerTimeoutMillis = 120_000L;

	/**
	 * Cached index bytes allowed at one time. Not final: a test lowers
	 * it to exercise the eviction path.
	 */
	static long cacheBudget = 32L * 1024 * 1024;

	/** Opens between two budget checks. Not final: a test lowers it. */
	static int trimInterval = 64;

	private final Path baseDir;
	private final ConcurrentHashMap<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Path, ShaEntry> shas = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Path, CompletableFuture<Index>> loads = new ConcurrentHashMap<>();
	private final Map<Path, Index> cache = new LinkedHashMap<>(16, 0.75f, true);
	private final Object cacheLock = new Object();
	private final AtomicInteger fileReads = new AtomicInteger();
	private final AtomicInteger opened = new AtomicInteger();
	private volatile boolean closed;
	private long cachedBytes;

	/**
	 * Creates the store.
	 *
	 * @param baseDir directory holding index files, never null
	 * @throws IOException when the directory cannot be created
	 * @throws NullPointerException if {@code baseDir} is {@code null}
	 */
	public JarIndexStore(Path baseDir) throws IOException {
		Objects.requireNonNull(baseDir, "baseDir");
		Files.createDirectories(baseDir);
		this.baseDir = baseDir;
		pruneStaleIndexes();
	}

	/**
	 * Hashes the jar, reusing the digest while the size and timestamp
	 * stamp agrees. One small entry per jar path: sessions touch
	 * dozens of jars, so no eviction is needed.
	 */
	private String shaOf(Path jar) throws IOException {
		Path key = jar.toAbsolutePath().normalize();
		ShaEntry remembered = shas.get(key);
		if (remembered != null && remembered.stamp().equals(JarStamp.key(jar))) {
			return remembered.sha();
		}
		String sha = Sha256.ofFile(jar);
		shas.put(key, new ShaEntry(JarStamp.key(jar), sha));
		return sha;
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
		// The file is not read here. A damaged index is detected when
		// it is queried, so the failure surfaces where the query is,
		// and the damaged file is dropped so the next call rebuilds it.
		if (Files.exists(target)) {
			return target;
		}
		CompletableFuture<Path> future = new CompletableFuture<>();
		CompletableFuture<Path> started = inFlight.putIfAbsent(sha, future);
		if (started != null) {
			return awaitIndex(started, jar);
		}
		try {
			List<Symbol> symbols = indexer.index(jar);
			write(sha, symbols);
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
	 * Waits for the leader that is indexing the same library. The wait
	 * is bounded: a leader that dies without answering must not hold a
	 * follower for ever.
	 *
	 * @param started the leader's future, never null
	 * @param jar     the library being indexed, never null
	 * @return the index file path, never null
	 * @throws IOException when the leader failed or did not answer
	 */
	private static Path awaitIndex(CompletableFuture<Path> started, Path jar) throws IOException {
		return await(started, "Indexing", jar);
	}

	/**
	 * Waits for a future with a bounded wait. A leader or loader that
	 * dies without answering must not hold the others for ever.
	 *
	 * @param future the future to wait for, never null
	 * @param what   what is being waited for, never null
	 * @param path   the file or library involved, never null
	 * @param <T>    the result type
	 * @return the value the future carries, never null
	 * @throws IOException when the wait failed or did not answer
	 */
	private static <T> T await(CompletableFuture<T> future, String what, Path path) throws IOException {
		try {
			return future.get(followerTimeoutMillis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException timeout) {
			throw new IOException(what + " timed out for " + path, timeout);
		} catch (ExecutionException failure) {
			throw new IOException(what + " failed for " + path, failure.getCause());
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IOException(what + " was interrupted for " + path, interrupted);
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
		Index index = load(db);
		int at = index.find(fqn);
		if (at < 0) {
			return List.of();
		}
		return List.of(index.symbol(at));
	}

	/**
	 * Finds exact-name symbols across indexes, in order.
	 *
	 * @param dbs index files, never null
	 * @param fqn fully qualified name, never null
	 * @return matches, never null
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
	 * Tests whether one index holds the name.
	 *
	 * @param db  index file, never null
	 * @param fqn fully qualified name, never null
	 * @return true when present
	 * @throws IOException when the index cannot be read
	 */
	public boolean existsIn(Path db, String fqn) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(fqn, "fqn");
		return load(db).find(fqn) >= 0;
	}

	/**
	 * Tests whether any index holds the name.
	 *
	 * @param dbs index files, never null
	 * @param fqn fully qualified name, never null
	 * @return true when present
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
	 * Searches names by prefix, most specific first.
	 *
	 * @param db     index file, never null
	 * @param prefix leading characters, never null
	 * @param limit  maximum rows, positive
	 * @return matches ordered by name length, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> searchPrefix(Path db, String prefix, int limit) throws IOException {
		return searchPrefix(db, prefix, limit, Set.of());
	}

	/**
	 * Searches names by prefix, keeping only the accepted kinds.
	 *
	 * @param db     index file, never null
	 * @param prefix leading characters, never null
	 * @param limit  maximum rows, positive
	 * @param kinds  accepted kinds, empty for every kind, never null
	 * @return matches ordered by name length, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> searchPrefix(Path db, String prefix, int limit, Set<SymbolKind> kinds)
			throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(prefix, "prefix");
		Objects.requireNonNull(kinds, "kinds");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		Index index = load(db);
		List<Symbol> matches = new ArrayList<>();
		for (int at : index.prefixRange(prefix)) {
			if (!index.accepts(at, kinds)) {
				continue;
			}
			matches.add(index.symbol(at));
			if (matches.size() >= limit) {
				break;
			}
		}
		return byLength(matches);
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
		return searchIn(db, query, limit, Set.of());
	}

	/**
	 * Searches names by substring, keeping only the accepted kinds.
	 *
	 * @param db    index file, never null
	 * @param query substring, never null
	 * @param limit maximum rows, positive
	 * @param kinds accepted kinds, empty for every kind, never null
	 * @return matches ordered by name length, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> searchIn(Path db, String query, int limit, Set<SymbolKind> kinds) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(query, "query");
		Objects.requireNonNull(kinds, "kinds");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		Index index = load(db);
		List<Symbol> matches = new ArrayList<>();
		byte[] needle = query.getBytes(StandardCharsets.UTF_8);
		for (int at = 0; at < index.count(); at++) {
			if (index.nameContains(at, needle) && index.accepts(at, kinds)) {
				matches.add(index.symbol(at));
				if (matches.size() >= limit) {
					break;
				}
			}
		}
		return byLength(matches);
	}

	/**
	 * Searches names by substring without regard to case.
	 *
	 * @param db    index file, never null
	 * @param query substring, never null
	 * @param limit maximum rows, positive
	 * @return matches ordered by name length, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> searchInInsensitive(Path db, String query, int limit) throws IOException {
		return searchInInsensitive(db, query, limit, Set.of());
	}

	/**
	 * Searches names by substring without regard to case, keeping only
	 * the accepted kinds.
	 *
	 * @param db    index file, never null
	 * @param query substring, never null
	 * @param limit maximum rows, positive
	 * @param kinds accepted kinds, empty for every kind, never null
	 * @return matches ordered by name length, never null
	 * @throws IOException when the index cannot be read
	 */
	public List<Symbol> searchInInsensitive(Path db, String query, int limit, Set<SymbolKind> kinds)
			throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(query, "query");
		Objects.requireNonNull(kinds, "kinds");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		Index index = load(db);
		String needle = query.toLowerCase(Locale.ROOT);
		List<Symbol> matches = new ArrayList<>();
		for (int at = 0; at < index.count(); at++) {
			if (index.name(at).toLowerCase(Locale.ROOT).contains(needle) && index.accepts(at, kinds)) {
				matches.add(index.symbol(at));
				if (matches.size() >= limit) {
					break;
				}
			}
		}
		return byLength(matches);
	}

	private static List<Symbol> byLength(List<Symbol> matches) {
		matches.sort(Comparator.comparingInt((Symbol symbol) -> symbol.fqn().length())
				.thenComparing(Symbol::fqn));
		return matches;
	}

	/**
	 * Tests whether one index holds a name segment that contains the
	 * query. The scan reads the held index, so no file is touched.
	 *
	 * @param db          index file, never null
	 * @param query       name fragment, never null
	 * @param insensitive true for case insensitive matching
	 * @return false only when no name segment can match, otherwise true
	 * @throws IOException when the index cannot be read
	 */
	public boolean maybeMatches(Path db, String query, boolean insensitive) throws IOException {
		Objects.requireNonNull(db, "db");
		Objects.requireNonNull(query, "query");
		Index index = load(db);
		if (insensitive) {
			String needle = query.toLowerCase(Locale.ROOT);
			for (int at = 0; at < index.count(); at++) {
				for (String part : index.name(at).split("\\.")) {
					if (part.toLowerCase(Locale.ROOT).contains(needle)) {
						return true;
					}
				}
			}
			return false;
		}
		byte[] needle = query.getBytes(StandardCharsets.UTF_8);
		for (int at = 0; at < index.count(); at++) {
			if (index.segmentContains(at, needle)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reads an index into memory, once per file. The first caller does
	 * the work and the others wait for it, so eight readers of one file
	 * read it once. A file that is already held costs nothing.
	 *
	 * @param db index file, never null
	 * @return the held index, never null
	 * @throws IOException when the file cannot be read or is damaged
	 */
	private Index load(Path db) throws IOException {
		if (closed) {
			throw new IOException("Index store is closed");
		}
		Index cached = cacheGet(db);
		if (cached != null) {
			return cached;
		}
		if (opened.incrementAndGet() % trimInterval == 0) {
			trimCache();
		}
		CompletableFuture<Index> future = new CompletableFuture<>();
		CompletableFuture<Index> started = loads.putIfAbsent(db, future);
		if (started != null) {
			return awaitLoad(started, db);
		}
		try {
			Index index = read(db);
			cachePut(db, index);
			future.complete(index);
			return index;
		} catch (CorruptIndexException corrupt) {
			future.completeExceptionally(corrupt);
			discard(db);
			throw corrupt;
		} catch (Throwable failure) {
			future.completeExceptionally(failure);
			throw failure;
		} finally {
			loads.remove(db, future);
		}
	}

	/**
	 * Waits for the reader that is loading the same file. The wait is
	 * bounded so a reader that dies cannot hold the others for ever.
	 *
	 * @param started the loader's future, never null
	 * @param db      the file being read, never null
	 * @return the held index, never null
	 * @throws IOException when the loader failed or did not answer
	 */
	private static Index awaitLoad(CompletableFuture<Index> started, Path db) throws IOException {
		return await(started, "Reading the index", db);
	}

	/**
	 * Reads the file and checks it. A damaged file is reported as
	 * corrupt, never as data.
	 *
	 * @param db index file, never null
	 * @return the parsed index, never null
	 * @throws IOException when the file cannot be read or is damaged
	 */
	private Index read(Path db) throws IOException {
		fileReads.incrementAndGet();
		return parse(readAllBytes(db), db);
	}

	/**
	 * Reads the whole file into a heap array. The read goes through a
	 * direct buffer this method owns, so the JDK's per thread direct
	 * buffer pool never retains memory the heap does not account for.
	 *
	 * @param file index file, never null
	 * @return the file bytes, never null
	 * @throws IOException when the file cannot be read
	 */
	private static byte[] readAllBytes(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			long size = channel.size();
			if (size > maxIndexBytes) {
				throw new IOException("Index file is too large: " + size + " bytes");
			}
			ByteBuffer direct = ByteBuffer.allocateDirect((int) size);
			long at = 0;
			while (at < size) {
				int read = channel.read(direct, at);
				if (read <= 0) {
					throw new CorruptIndexException("Index file is truncated: " + file);
				}
				at += read;
			}
			direct.flip();
			byte[] bytes = new byte[(int) size];
			direct.get(bytes);
			return bytes;
		}
	}

	/**
	 * Parses and verifies an index file. The header, the layout and
	 * the checksum are checked here, once, so the query path never
	 * pays for them.
	 *
	 * @param file the file bytes, never null
	 * @param db   the file it came from, never null
	 * @return the parsed index, never null
	 * @throws IOException when the file is not an index or is damaged
	 */
	private static Index parse(byte[] file, Path db) throws IOException {
		if (file.length < HEADER) {
			throw new CorruptIndexException("Index file is truncated: " + db);
		}
		if (intAt(file, 0) != MAGIC) {
			throw new IOException("Not an index file: " + db);
		}
		if (intAt(file, 4) != VERSION) {
			throw new IOException("Unsupported index version: " + db);
		}
		int algorithm = intAt(file, 40);
		if (algorithm != CHECKSUM_CRC32C) {
			throw new IOException("Unsupported checksum algorithm: " + algorithm + " in " + db);
		}
		int count = intAt(file, 8);
		int namesLength = intAt(file, 12);
		int offsetsAt = intAt(file, 16);
		int metaAt = intAt(file, 20);
		int signatureAt = intAt(file, 24);
		int sinceAt = intAt(file, 28);
		int payloadCrc = intAt(file, 32);
		if (count < 0 || namesLength < 0) {
			throw new CorruptIndexException("Negative size in " + db);
		}
		long metaEnd = (long) metaAt + (long) count * META;
		boolean layoutSound = offsetsAt == (long) HEADER + namesLength
				&& metaAt == (long) offsetsAt + ((long) count + 1) * Integer.BYTES
				&& signatureAt >= metaEnd
				&& sinceAt >= signatureAt
				&& metaEnd <= file.length
				&& sinceAt <= file.length;
		if (!layoutSound) {
			throw new CorruptIndexException("Inconsistent index layout in " + db);
		}
		CRC32C crc = new CRC32C();
		crc.update(file, HEADER, file.length - HEADER);
		if ((int) crc.getValue() != payloadCrc) {
			throw new CorruptIndexException("Index checksum mismatch in " + db);
		}
		int[] offsets = new int[count + 1];
		int previous = 0;
		for (int i = 0; i <= count; i++) {
			int offset = intAt(file, offsetsAt + i * Integer.BYTES);
			if (offset < previous || offset > namesLength) {
				throw new CorruptIndexException("Name offset out of range in " + db);
			}
			previous = offset;
			offsets[i] = offset;
		}
		int signatureLength = sinceAt - signatureAt;
		int sinceLength = file.length - sinceAt;
		for (int i = 0; i < count; i++) {
			int at = (int) metaAt + i * META;
			if ((file[at] & 0xff) >= KINDS.length) {
				throw new CorruptIndexException("Unknown symbol kind in " + db);
			}
			int signatureOffset = intAt(file, at + 2);
			int signatureSize = intAt(file, at + 6);
			int sinceOffset = intAt(file, at + 10);
			int sinceSize = intAt(file, at + 14);
			boolean descriptorSound = Integer.toUnsignedLong(signatureOffset)
					+ Integer.toUnsignedLong(signatureSize) <= signatureLength
					&& Integer.toUnsignedLong(sinceOffset)
					+ Integer.toUnsignedLong(sinceSize) <= sinceLength;
			if (!descriptorSound) {
				throw new CorruptIndexException("Descriptor out of range in " + db);
			}
		}
		return new Index(file, count, metaAt, signatureAt, sinceAt, offsets);
	}

	/**
	 * Reads a big endian int out of a byte array.
	 *
	 * @param data the array, never null
	 * @param at   the offset, never negative
	 * @return the value read
	 */
	private static int intAt(byte[] data, int at) {
		return (data[at] & 0xff) << 24
				| (data[at + 1] & 0xff) << 16
				| (data[at + 2] & 0xff) << 8
				| (data[at + 3] & 0xff);
	}

	/**
	 * Drops a damaged index so the next request rebuilds it. Only a
	 * file the store itself wrote is removed.
	 *
	 * @param db index file, never null
	 */
	private void discard(Path db) {
		String name = db.getFileName().toString();
		if (!name.endsWith("." + FORMAT + ".idx")) {
			return;
		}
		try {
			Files.deleteIfExists(db);
		} catch (IOException ignored) {
			// Best effort: a leftover damaged file only wastes disk.
		}
	}

	/**
	 * Returns a held index, remembering that it was used.
	 *
	 * @param db index file, never null
	 * @return the held index, or null when it is not held
	 */
	private Index cacheGet(Path db) {
		synchronized (cacheLock) {
			return cache.get(db);
		}
	}

	/**
	 * Holds an index and keeps the held bytes inside the budget.
	 *
	 * @param db    index file, never null
	 * @param index the index to hold, never null
	 */
	private void cachePut(Path db, Index index) {
		synchronized (cacheLock) {
			cache.put(db, index);
			cachedBytes += index.cachedBytes();
			evict();
		}
	}

	/**
	 * Releases the least recently used indexes until the held bytes fit
	 * the budget. The bytes are dropped, never changed, so a reader that
	 * already holds one keeps reading the same array.
	 */
	private void evict() {
		while (cachedBytes > cacheBudget && !cache.isEmpty()) {
			Map.Entry<Path, Index> eldest = cache.entrySet().iterator().next();
			cache.remove(eldest.getKey());
			cachedBytes -= eldest.getValue().cachedBytes();
		}
	}

	/**
	 * Keeps the held bytes inside the budget. The check runs every so
	 * often, so a budget that was lowered while the store was open is
	 * honoured.
	 */
	private void trimCache() {
		synchronized (cacheLock) {
			evict();
		}
	}

	/**
	 * Releases every held index. New queries fail fast once closing
	 * starts.
	 *
	 * @throws IOException never, kept for the close contract
	 */
	@Override
	public void close() throws IOException {
		closed = true;
		synchronized (cacheLock) {
			cache.clear();
			cachedBytes = 0;
		}
	}

	/**
	 * Removes index files written by an older format. The format tag
	 * is part of the file name, so a file without it can never be read
	 * again. Files younger than an hour are left alone, in case another
	 * process is still writing them.
	 */
	private void pruneStaleIndexes() {
		long cutoff = System.currentTimeMillis() - 3_600_000L;
		try (var files = Files.list(baseDir)) {
			for (Path file : files.filter(this::isStaleIndex).toList()) {
				try {
					if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
						Files.deleteIfExists(file);
					}
				} catch (IOException ignored) {
					// Best effort: a leftover file only wastes disk.
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// Best effort: pruning is housekeeping, never a failure.
		}
	}

	private boolean isStaleIndex(Path file) {
		String name = file.getFileName().toString();
		boolean indexFile = name.endsWith(".idx") || name.endsWith(".mv.db");
		return indexFile && !name.contains("." + FORMAT + ".");
	}

	private Path dbFile(String sha) {
		return baseDir.resolve(sha + "." + FORMAT + ".idx");
	}

	/**
	 * The number of index files this store has read from disk. A test
	 * uses it to prove that a file is read once.
	 *
	 * @return the number of file reads
	 */
	int fileReads() {
		return fileReads.get();
	}

	/**
	 * The bytes this store holds in memory for its index files. A test
	 * uses it to keep the memory budget honest.
	 *
	 * @return the held bytes
	 */
	long cachedBytes() {
		synchronized (cacheLock) {
			return cachedBytes;
		}
	}

	/**
	 * Writes the symbols as one sorted file. Names are sorted by their
	 * bytes so the reader can find them with a binary search, and the
	 * declaring type is left out because the name already carries it.
	 * The whole payload is checksummed here, never on the query path.
	 */
	private void write(String sha, List<Symbol> symbols) throws IOException {
		List<Symbol> sorted = new ArrayList<>(symbols);
		sorted.sort(Comparator.comparing(symbol -> symbol.fqn().getBytes(StandardCharsets.UTF_8),
				Arrays::compareUnsigned));
		int count = sorted.size();
		byte[][] names = new byte[count][];
		byte[] signatures = new byte[0];
		int[] signatureOffsets = new int[count + 1];
		byte[] sinces = new byte[0];
		int[] sinceOffsets = new int[count + 1];
		StringBuilder signatureText = new StringBuilder();
		StringBuilder sinceText = new StringBuilder();
		for (int i = 0; i < count; i++) {
			Symbol symbol = sorted.get(i);
			if (symbol.fqn().length() > MAX_NAME) {
				throw new IOException("Symbol name is longer than " + MAX_NAME + " characters");
			}
			if (symbol.signature() != null && symbol.signature().length() > MAX_SIGNATURE) {
				throw new IOException("Symbol signature is longer than " + MAX_SIGNATURE + " characters");
			}
			names[i] = symbol.fqn().getBytes(StandardCharsets.UTF_8);
			signatureOffsets[i] = signatureText.length();
			if (symbol.signature() != null) {
				signatureText.append(symbol.signature());
			}
			signatureOffsets[i + 1] = signatureText.length();
			sinceOffsets[i] = sinceText.length();
			sinceText.append(symbol.deprecatedSince());
			sinceOffsets[i + 1] = sinceText.length();
		}
		signatures = signatureText.toString().getBytes(StandardCharsets.UTF_8);
		sinces = sinceText.toString().getBytes(StandardCharsets.UTF_8);
		int namesLength = 0;
		for (byte[] name : names) {
			namesLength += name.length;
		}
		int offsetsAt = HEADER + namesLength;
		int metaAt = offsetsAt + (count + 1) * Integer.BYTES;
		int signatureAt = metaAt + count * META;
		int sinceAt = signatureAt + signatures.length;
		ByteBuffer buffer = ByteBuffer.allocate(sinceAt + sinces.length).order(ByteOrder.BIG_ENDIAN);
		buffer.putInt(MAGIC);
		buffer.putInt(VERSION);
		buffer.putInt(count);
		buffer.putInt(namesLength);
		buffer.putInt(offsetsAt);
		buffer.putInt(metaAt);
		buffer.putInt(signatureAt);
		buffer.putInt(sinceAt);
		buffer.putInt(0);
		buffer.putInt(0);
		buffer.putInt(CHECKSUM_CRC32C);
		buffer.putInt(0);
		buffer.putInt(0);
		buffer.position(HEADER);
		for (byte[] name : names) {
			buffer.put(name);
		}
		int nameAt = HEADER;
		for (int i = 0; i < count; i++) {
			buffer.putInt(nameAt - HEADER);
			nameAt += names[i].length;
		}
		buffer.putInt(nameAt - HEADER);
		for (int i = 0; i < count; i++) {
			Symbol symbol = sorted.get(i);
			buffer.put((byte) symbol.kind().ordinal());
			buffer.put((byte) ((symbol.deprecated() ? 1 : 0) | (symbol.forRemoval() ? 2 : 0)));
			buffer.putInt(signatureOffsets[i]);
			buffer.putInt(signatureOffsets[i + 1] - signatureOffsets[i]);
			buffer.putInt(sinceOffsets[i]);
			buffer.putInt(sinceOffsets[i + 1] - sinceOffsets[i]);
		}
		buffer.put(signatures);
		buffer.put(sinces);
		byte[] bytes = buffer.array();
		CRC32C crc = new CRC32C();
		crc.update(bytes, HEADER, bytes.length - HEADER);
		ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
		header.putInt(32, (int) crc.getValue());
		Path tmp = baseDir.resolve(sha + ".tmp." + Thread.currentThread().threadId()
				+ "." + System.nanoTime() + ".idx");
		try {
			Files.write(tmp, bytes);
			publishTmp(tmp, dbFile(sha));
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Publishes a finished temp file. The first publisher wins: same-sha
	 * content is byte-identical, so a loser must never clobber a winner.
	 *
	 * @param tmpFile finished temp file, never null
	 * @param target final location, never null
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
	 * One index file held in memory. The bytes are read once and never
	 * changed, so a reader that holds the array is never disturbed by
	 * an eviction, and every lookup is a memory search.
	 */
	private static final class Index {

		private final byte[] file;
		private final int count;
		private final int metaAt;
		private final int signatureAt;
		private final int sinceAt;
		private final int[] offsets;

		Index(byte[] file, int count, int metaAt, int signatureAt, int sinceAt, int[] offsets) {
			this.file = file;
			this.count = count;
			this.metaAt = metaAt;
			this.signatureAt = signatureAt;
			this.sinceAt = sinceAt;
			this.offsets = offsets;
		}

		/**
		 * The bytes this index holds, so the budget counts what the
		 * heap actually keeps.
		 *
		 * @return the held bytes
		 */
		long cachedBytes() {
			return (long) file.length + (long) offsets.length * Integer.BYTES;
		}

		int count() {
			return count;
		}

		private int nameStart(int index) {
			return HEADER + offsets[index];
		}

		private int nameLength(int index) {
			return offsets[index + 1] - offsets[index];
		}

		String name(int index) {
			return new String(file, nameStart(index), nameLength(index), StandardCharsets.UTF_8);
		}

		/**
		 * Finds the index of an exact name, or -1.
		 */
		int find(String fqn) {
			byte[] needle = fqn.getBytes(StandardCharsets.UTF_8);
			int low = 0;
			int high = count - 1;
			while (low <= high) {
				int middle = (low + high) >>> 1;
				int order = compare(nameStart(middle), nameLength(middle), needle);
				if (order == 0) {
					return middle;
				}
				if (order < 0) {
					low = middle + 1;
				} else {
					high = middle - 1;
				}
			}
			return -1;
		}

		private int compare(int start, int length, byte[] needle) {
			int shared = Math.min(length, needle.length);
			for (int i = 0; i < shared; i++) {
				int order = Integer.compare(file[start + i] & 0xff, needle[i] & 0xff);
				if (order != 0) {
					return order;
				}
			}
			return Integer.compare(length, needle.length);
		}

		/**
		 * Lists the indexes of every name starting with the prefix. The
		 * names are sorted, so the matches are one contiguous range.
		 */
		List<Integer> prefixRange(String prefix) {
			byte[] needle = prefix.getBytes(StandardCharsets.UTF_8);
			List<Integer> hits = new ArrayList<>();
			for (int index = 0; index < count; index++) {
				int start = nameStart(index);
				int length = nameLength(index);
				if (length < needle.length) {
					continue;
				}
				boolean same = true;
				for (int i = 0; i < needle.length; i++) {
					if ((file[start + i] & 0xff) != (needle[i] & 0xff)) {
						same = false;
						break;
					}
				}
				if (same) {
					hits.add(index);
				}
			}
			return hits;
		}

		/**
		 * Tests whether the whole name contains the needle.
		 */
		boolean nameContains(int index, byte[] needle) {
			int start = nameStart(index);
			int length = nameLength(index);
			if (needle.length == 0) {
				return true;
			}
			int limit = length - needle.length;
			outer:
			for (int i = 0; i <= limit; i++) {
				for (int j = 0; j < needle.length; j++) {
					if ((file[start + i + j] & 0xff) != (needle[j] & 0xff)) {
						continue outer;
					}
				}
				return true;
			}
			return false;
		}

		/**
		 * Tests whether any dot separated segment of the name contains
		 * the needle.
		 */
		boolean segmentContains(int index, byte[] needle) {
			int start = nameStart(index);
			int length = nameLength(index);
			if (needle.length == 0) {
				return true;
			}
			int segmentStart = start;
			for (int i = start; i <= start + length; i++) {
				boolean end = i == start + length || file[i] == '.';
				if (end) {
					int segmentLength = i - segmentStart;
					if (segmentLength >= needle.length) {
						outer:
						for (int p = segmentStart; p <= i - needle.length; p++) {
							for (int j = 0; j < needle.length; j++) {
								if ((file[p + j] & 0xff) != (needle[j] & 0xff)) {
									continue outer;
								}
							}
							return true;
						}
					}
					segmentStart = i + 1;
				}
			}
			return false;
		}

		/**
		 * Tests whether a symbol's kind is accepted. An empty set
		 * accepts every kind.
		 */
		boolean accepts(int index, Set<SymbolKind> kinds) {
			if (kinds.isEmpty()) {
				return true;
			}
			return kinds.contains(KINDS[file[metaAt + index * META] & 0xff]);
		}

		/**
		 * Reads one symbol. The declaring type is the name without its
		 * last segment, so it is derived rather than stored.
		 */
		Symbol symbol(int index) {
			String fqn = name(index);
			int at = metaAt + index * META;
			SymbolKind kind = KINDS[file[at] & 0xff];
			int flags = file[at + 1] & 0xff;
			int signatureOffset = intAt(file, at + 2);
			int signatureLength = intAt(file, at + 6);
			int sinceOffset = intAt(file, at + 10);
			int sinceLength = intAt(file, at + 14);
			String signature = null;
			if (signatureLength > 0) {
				signature = new String(file, signatureAt + signatureOffset, signatureLength,
						StandardCharsets.UTF_8);
			}
			String since = "";
			if (sinceLength > 0) {
				since = new String(file, sinceAt + sinceOffset, sinceLength, StandardCharsets.UTF_8);
			}
			String parent = null;
			int lastDot = fqn.lastIndexOf('.');
			if (lastDot > 0 && isMember(kind)) {
				parent = fqn.substring(0, lastDot);
			}
			return new Symbol(fqn, kind, signature, parent, (flags & 1) != 0, since, (flags & 2) != 0);
		}

		private static boolean isMember(SymbolKind kind) {
			return kind == SymbolKind.METHOD || kind == SymbolKind.FIELD;
		}
	}

	/**
	 * A file that claims to be an index but does not hold the data it
	 * claims. A corrupt index is never answered from.
	 */
	static final class CorruptIndexException extends IOException {

		private static final long serialVersionUID = 1L;

		/**
		 * Creates the exception.
		 *
		 * @param message what is wrong, never null
		 */
		CorruptIndexException(String message) {
			super(message);
		}
	}

	private record ShaEntry(String stamp, String sha) {
	}
}
