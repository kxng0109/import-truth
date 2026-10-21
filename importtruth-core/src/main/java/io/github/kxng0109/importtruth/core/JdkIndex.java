package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.Sha256;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/**
 * Answers whether a name exists in the running JDK image. Records which
 * JDK answered, because the tool JDK may differ from the project JDK.
 */
public final class JdkIndex {

	/** Magic line at the head of a cache file, with its format version. */
	private static final String CACHE_MAGIC = "importtruth-jdk-index 1";

	/** Largest cache file that is read. */
	private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;

	/** Largest number of names a cache file may hold. */
	private static final int MAX_CACHE_ENTRIES = 1_000_000;

	private final Map<String, Boolean> cache = bounded(4096);
	private final Map<String, Boolean> packageCache = bounded(4096);
	private final Map<Integer, ReleaseIndex> releases = new ConcurrentHashMap<>();
	private final Set<Integer> withoutData = ConcurrentHashMap.newKeySet();
	private final Map<Integer, Object> releaseLocks = new ConcurrentHashMap<>();
	private final List<Path> modules;
	private final String jdkVersion = System.getProperty("java.version", "unknown");
	private final int runningRelease = Runtime.version().feature();
	private final Path ctSym;
	private final Path cacheDir;

	/**
	 * Creates a least recently used cache: typo storms evict eldest
	 * entries instead of growing without bound. Hits are stable truths
	 * of the running JDK; misses re-probe cheaply after eviction.
	 *
	 * @param bound maximum entries, positive
	 * @return synchronized LRU map, never null
	 */
	private static Map<String, Boolean> bounded(int bound) {
		return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
				return size() > bound;
			}
		});
	}

	/**
	 * Creates the index, listing the runtime image modules once. The
	 * documented API of a release is read from the running JDK's
	 * {@code lib/ct.sym} and kept in memory only.
	 */
	public JdkIndex() {
		this(defaultCtSym(), null);
	}

	/**
	 * Creates the index and keeps the parsed release index on disk
	 * under {@code <stateDir>/jdk}, so a new session does not parse
	 * the archive again.
	 *
	 * @param stateDir tool state directory, never null
	 * @throws NullPointerException when {@code stateDir} is {@code null}
	 */
	public JdkIndex(Path stateDir) {
		this(defaultCtSym(), Objects.requireNonNull(stateDir, "stateDir"));
	}

	/**
	 * Creates the index with an explicit archive and state directory.
	 * The archive is injectable so a test can drive the paths a real
	 * JDK does not have.
	 *
	 * @param ctSym    the {@code ct.sym} archive, or null when this
	 *                 build has none, as a native image does
	 * @param stateDir tool state directory, or null for no disk cache
	 */
	JdkIndex(Path ctSym, Path stateDir) {
		this(ctSym, stateDir, runningImageModules());
	}

	/**
	 * Creates the index with an explicit module list. A native image
	 * has no runtime image filesystem, and a test has to be able to
	 * drive that same shape.
	 *
	 * @param ctSym    the {@code ct.sym} archive, or null when this
	 *                 build has none, as a native image does
	 * @param stateDir tool state directory, or null for no disk cache
	 * @param modules  runtime image modules, never null
	 */
	JdkIndex(Path ctSym, Path stateDir, List<Path> modules) {
		this.ctSym = ctSym == null ? null : ctSym.toAbsolutePath().normalize();
		this.cacheDir = stateDir == null
				? null
				: stateDir.toAbsolutePath().normalize().resolve("jdk");
		this.modules = List.copyOf(modules);
	}

	/**
	 * Lists the modules of the running image. A build without the
	 * runtime image filesystem has none.
	 *
	 * @return the module paths, never null
	 */
	private static List<Path> runningImageModules() {
		try (var entries = Files.list(FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules"))) {
			return entries.toList();
		} catch (Exception missing) {
			return List.of();
		}
	}

	/**
	 * Returns the archive the documented API of a release is read
	 * from.
	 *
	 * @return the {@code ct.sym} path, or null when this build has no
	 *         JDK home and therefore no archive
	 */
	private static Path defaultCtSym() {
		String home = System.getProperty("java.home");
		if (home == null) {
			// A native image carries no JDK, so it has no archive to read.
			return null;
		}
		return Path.of(home, "lib", "ct.sym");
	}

	/**
	 * Returns the running JDK version that backs these answers.
	 *
	 * @return version string, never null
	 */
	public String jdkVersion() {
		return jdkVersion;
	}

	/**
	 * Returns the running JDK's major release.
	 *
	 * @return feature version, never negative
	 */
	public int runningRelease() {
		return runningRelease;
	}

	/**
	 * Reports whether this build can answer questions about the JDK API
	 * at all. A native image has neither the runtime image filesystem
	 * nor a {@code ct.sym}, so it cannot, and the tool must say that
	 * instead of claiming a class is missing.
	 *
	 * @return true when the JDK API surface can be consulted
	 */
	public boolean available() {
		return !modules.isEmpty() || ctSym != null;
	}

	/**
	 * Checks the name against the documented API of {@code release},
	 * read from the running JDK's {@code ct.sym}. Falls back to the
	 * running image when {@code release} is newer than the runtime
	 * or no {@code ct.sym} is available.
	 *
	 * @param fqn     fully qualified name, never null
	 * @param release target major release
	 * @return true when a matching class file exists
	 * @throws NullPointerException when {@code fqn} is {@code null}
	 */
	public boolean existsIn(String fqn, int release) {
		Objects.requireNonNull(fqn, "fqn");
		ReleaseIndex indexed = releaseIndex(release);
		if (indexed == null) {
			return exists(fqn);
		}
		String dotted = fqn.replace('.', '/') + ".class";
		if (indexed.classes().contains(dotted)) {
			return true;
		}
		String nested = dotted.substring(0, dotted.length() - ".class".length());
		int slash;
		while ((slash = nested.lastIndexOf('/')) > 0) {
			nested = nested.substring(0, slash) + "$" + nested.substring(slash + 1);
			if (indexed.classes().contains(nested + ".class")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks the name against the runtime image, including nested types.
	 *
	 * @param fqn fully qualified name, never null
	 * @return true when a matching class file exists
	 * @throws NullPointerException when {@code fqn} is {@code null}
	 */
	public boolean exists(String fqn) {
		Objects.requireNonNull(fqn, "fqn");
		return cache.computeIfAbsent(fqn, this::probe);
	}

	private boolean probe(String fqn) {
		String dotted = fqn.replace('.', '/') + ".class";
		if (contains(dotted)) {
			return true;
		}
		String nested = dotted.substring(0, dotted.length() - ".class".length());
		int slash;
		while ((slash = nested.lastIndexOf('/')) > 0) {
			nested = nested.substring(0, slash) + "$" + nested.substring(slash + 1);
			if (contains(nested + ".class")) {
				return true;
			}
		}
		return false;
	}

	private boolean contains(String member) {
		return anyModule(member, false);
	}

	private boolean anyModule(String member, boolean directoriesOnly) {
		for (Path module : modules) {
			try {
				Path resolved = module.resolve(member);
				if (directoriesOnly ? Files.isDirectory(resolved) : Files.exists(resolved)) {
					return true;
				}
			} catch (Exception missing) {
				// Unreadable module: treated as absent, like a missing entry.
			}
		}
		return false;
	}

	/**
	 * Checks whether a package exists in the runtime image.
	 *
	 * @param name dotted package name, never null
	 * @return true when some module holds the package directory
	 * @throws NullPointerException when {@code name} is {@code null}
	 */
	public boolean packageExists(String name) {
		Objects.requireNonNull(name, "name");
		return packageCache.computeIfAbsent(name, this::probePackage);
	}

	private boolean probePackage(String name) {
		return anyModule(name.replace('.', '/'), true);
	}

	/**
	 * Checks whether a package exists in the documented API of
	 * {@code release}. Falls back to the running image when
	 * {@code release} is newer than the runtime or no {@code ct.sym}
	 * is available.
	 *
	 * @param name    dotted package name, never null
	 * @param release target major release
	 * @return true when some module holds the package directory
	 * @throws NullPointerException when {@code name} is {@code null}
	 */
	public boolean packageExistsIn(String name, int release) {
		Objects.requireNonNull(name, "name");
		ReleaseIndex indexed = releaseIndex(release);
		if (indexed == null) {
			return packageExists(name);
		}
		return indexed.packages().contains(name.replace('.', '/'));
	}

	private ReleaseIndex releaseIndex(int release) {
		if (release < 8 || release > runningRelease) {
			return null;
		}
		// One loader at a time. A plain computeIfAbsent lets every
		// concurrent caller parse the whole archive on its own.
		synchronized (releaseLocks.computeIfAbsent(release, key -> new Object())) {
			ReleaseIndex known = releases.get(release);
			if (known != null) {
				return known;
			}
			if (withoutData.contains(release)) {
				return null;
			}
			ReleaseIndex loaded = loadRelease(release);
			if (loaded == null) {
				// The negative answer is cached as well: a JDK without
				// a readable ct.sym must not parse the archive on every
				// lookup. A null never reaches the map, which forbids
				// null values.
				withoutData.add(release);
			} else {
				releases.put(release, loaded);
			}
			return loaded;
		}
	}

	/**
	 * Loads the documented API of a release. The disk cache answers
	 * first, so a new session does not parse the archive again; a
	 * parsed release is published to the cache for the next one.
	 *
	 * @param release target major release
	 * @return the index, or null when the archive holds no data
	 */
	private ReleaseIndex loadRelease(int release) {
		ReleaseIndex cached = readCache(release);
		if (cached != null) {
			return cached;
		}
		ReleaseIndex parsed = parseArchive(release);
		if (parsed != null) {
			writeCache(release, parsed);
		}
		return parsed;
	}

	/**
	 * Parses the documented API of a release from the archive. A JDK
	 * without a readable archive offers no documented API.
	 *
	 * @param release target major release
	 * @return the index, or null when the archive holds no data
	 */
	private ReleaseIndex parseArchive(int release) {
		if (ctSym == null) {
			// This build has no archive, so it has no documented API.
			return null;
		}
		char marker = release == 8 ? '8' : release == 9 ? '9' : (char) ('A' + release - 10);
		Set<String> classes = new HashSet<>();
		Set<String> packages = new HashSet<>();
		try (ZipFile zip = new ZipFile(ctSym.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				String entry = entries.nextElement().getName();
				int slash = entry.indexOf('/');
				if (slash < 0 || entry.indexOf(marker, 0, slash) < 0) {
					continue;
				}
				String rest = entry.substring(slash + 1);
				if (rest.endsWith(".sig")) {
					String path = rest.substring(rest.indexOf('/') + 1);
					String binary = path.substring(0, path.length() - ".sig".length()) + ".class";
					classes.add(binary);
					int dir = path.lastIndexOf('/');
					while (dir > 0) {
						packages.add(path.substring(0, dir));
						dir = path.lastIndexOf('/', dir - 1);
					}
				}
			}
		} catch (Exception missing) {
			return null;
		}
		if (classes.isEmpty()) {
			return null;
		}
		return new ReleaseIndex(Set.copyOf(classes), Set.copyOf(packages));
	}

	/**
	 * Reads a cached release index. A cache that is missing, corrupt
	 * or written for another archive is ignored: the archive is parsed
	 * again rather than a lookup failing.
	 *
	 * @param release target major release
	 * @return the cached index, or null when there is none
	 */
	private ReleaseIndex readCache(int release) {
		if (cacheDir == null) {
			return null;
		}
		Path file = cacheFile(release);
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > MAX_CACHE_BYTES) {
				return null;
			}
			return parseCache(Files.readAllLines(file, StandardCharsets.UTF_8), release);
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}

	/**
	 * Parses a cached release index. The file holds a magic line, the
	 * archive identity, the release, then the class names and the
	 * package names, each with its count.
	 *
	 * @param lines   the file lines, never null
	 * @param release the release the file must describe
	 * @return the index, or null when the file is not a valid cache
	 */
	private ReleaseIndex parseCache(List<String> lines, int release) {
		Cursor cursor = new Cursor(lines);
		if (!cursor.expect(CACHE_MAGIC)) {
			return null;
		}
		String path = cursor.header("path");
		long size = cursor.number("size");
		long modified = cursor.number("modified");
		String releaseText = cursor.header("release");
		if (path == null || size < 0 || modified < 0 || releaseText == null) {
			return null;
		}
		if (ctSym == null || !ctSym.toString().equals(path) || !String.valueOf(release).equals(releaseText)) {
			return null;
		}
		if (!identityMatches(size, modified)) {
			return null;
		}
		long classCount = cursor.number("classes");
		if (classCount < 0 || classCount > MAX_CACHE_ENTRIES) {
			return null;
		}
		Set<String> classes = new HashSet<>();
		for (long i = 0; i < classCount; i++) {
			String name = cursor.next();
			if (name == null) {
				return null;
			}
			classes.add(name);
		}
		long packageCount = cursor.number("packages");
		if (packageCount < 0 || packageCount > MAX_CACHE_ENTRIES) {
			return null;
		}
		Set<String> packages = new HashSet<>();
		for (long i = 0; i < packageCount; i++) {
			String name = cursor.next();
			if (name == null) {
				return null;
			}
			packages.add(name);
		}
		return new ReleaseIndex(Set.copyOf(classes), Set.copyOf(packages));
	}

	/**
	 * Tests whether the archive on disk is the one the cache was
	 * written from. A vanished archive keeps its cache: the recorded
	 * path still names the JDK the data came from.
	 *
	 * @param size     recorded archive size in bytes
	 * @param modified recorded archive modification time in millis
	 * @return true when the cache describes the archive on disk
	 */
	private boolean identityMatches(long size, long modified) {
		if (ctSym == null) {
			return false;
		}
		try {
			return Files.size(ctSym) == size && Files.getLastModifiedTime(ctSym).toMillis() == modified;
		} catch (IOException vanished) {
			return true;
		}
	}

	/**
	 * Writes the release index to the cache. The file is published
	 * atomically, so a reader never sees a half written index, and a
	 * cache that cannot be written only costs a parse next time.
	 *
	 * @param release target major release
	 * @param index   the index to cache, never null
	 */
	private void writeCache(int release, ReleaseIndex index) {
		if (cacheDir == null || ctSym == null) {
			return;
		}
		Path file = cacheFile(release);
		Path staged = null;
		try {
			long size = Files.size(ctSym);
			long modified = Files.getLastModifiedTime(ctSym).toMillis();
			Files.createDirectories(cacheDir);
			staged = Files.createTempFile(cacheDir, "jdk-index-", ".tmp");
			Files.write(staged, renderCache(release, index, size, modified), StandardCharsets.UTF_8);
			try {
				Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException noAtomic) {
				Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException unwritable) {
			// A cache that cannot be written is not an error: the next
			// session parses the archive again.
		} finally {
			if (staged != null) {
				try {
					Files.deleteIfExists(staged);
				} catch (IOException ignored) {
					// The staged file is already gone or already moved.
				}
			}
		}
	}

	/**
	 * Renders the cache file of a release.
	 *
	 * @param release  target major release
	 * @param index    the index to render, never null
	 * @param size     archive size in bytes
	 * @param modified archive modification time in millis
	 * @return the file lines, never null
	 */
	private List<String> renderCache(int release, ReleaseIndex index, long size, long modified) {
		List<String> classes = index.classes().stream().sorted().toList();
		List<String> packages = index.packages().stream().sorted().toList();
		List<String> lines = new ArrayList<>();
		lines.add(CACHE_MAGIC);
		lines.add("path " + (ctSym == null ? "none" : ctSym));
		lines.add("size " + size);
		lines.add("modified " + modified);
		lines.add("release " + release);
		lines.add("classes " + classes.size());
		lines.addAll(classes);
		lines.add("packages " + packages.size());
		lines.addAll(packages);
		return lines;
	}

	/**
	 * Names the cache file of a release. The name follows the archive
	 * path and the release, so the file is found again even when the
	 * archive has vanished; the archive identity is verified inside.
	 *
	 * @param release target major release
	 * @return the cache file, never null
	 */
	private Path cacheFile(int release) {
		String name = Sha256.ofBytes((ctSym + "|" + release).getBytes(StandardCharsets.UTF_8));
		return cacheDir.resolve(name + ".jdkidx");
	}

	/**
	 * Reads the lines of a cache file in order.
	 */
	private static final class Cursor {

		private final List<String> lines;
		private int at;

		private Cursor(List<String> lines) {
			this.lines = lines;
		}

		private boolean expect(String literal) {
			if (at >= lines.size() || !literal.equals(lines.get(at))) {
				return false;
			}
			at++;
			return true;
		}

		private String header(String name) {
			if (at >= lines.size()) {
				return null;
			}
			String line = lines.get(at++);
			String prefix = name + " ";
			if (!line.startsWith(prefix)) {
				return null;
			}
			return line.substring(prefix.length());
		}

		private long number(String name) {
			String value = header(name);
			if (value == null) {
				return -1;
			}
			try {
				return Long.parseLong(value.trim());
			} catch (NumberFormatException invalid) {
				return -1;
			}
		}

		private String next() {
			if (at >= lines.size()) {
				return null;
			}
			return lines.get(at++);
		}
	}

	private record ReleaseIndex(Set<String> classes, Set<String> packages) {
	}
}
