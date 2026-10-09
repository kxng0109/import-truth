package io.github.kxng0109.importtruth.core;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
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

	private final Map<String, Boolean> cache = bounded(4096);
	private final Map<String, Boolean> packageCache = bounded(4096);
	private final Map<Integer, ReleaseIndex> releases = new ConcurrentHashMap<>();
	private final List<Path> modules;
	private final String jdkVersion = System.getProperty("java.version", "unknown");
	private final int runningRelease = Runtime.version().feature();

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
	 * Creates the index, listing the runtime image modules once.
	 */
	public JdkIndex() {
		List<Path> listed;
		try (var entries = Files.list(FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules"))) {
			listed = entries.toList();
		} catch (Exception missing) {
			listed = List.of();
		}
		this.modules = listed;
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
		return releases.computeIfAbsent(release, this::loadRelease);
	}

	private ReleaseIndex loadRelease(int release) {
		char marker = release == 8 ? '8' : release == 9 ? '9' : (char) ('A' + release - 10);
		Path ctSym = Path.of(System.getProperty("java.home"), "lib", "ct.sym");
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

	private record ReleaseIndex(Set<String> classes, Set<String> packages) {
	}
}
