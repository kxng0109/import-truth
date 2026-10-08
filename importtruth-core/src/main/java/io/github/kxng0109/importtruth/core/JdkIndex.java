package io.github.kxng0109.importtruth.core;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers whether a name exists in the running JDK image. Records which
 * JDK answered, because the tool JDK may differ from the project JDK.
 */
public final class JdkIndex {

	private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
	private final Map<String, Boolean> packageCache = new ConcurrentHashMap<>();
	private final List<Path> modules;
	private final String jdkVersion = System.getProperty("java.version", "unknown");

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
		for (Path module : modules) {
			try {
				if (Files.exists(module.resolve(member))) {
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
		String path = name.replace('.', '/');
		for (Path module : modules) {
			try {
				if (Files.isDirectory(module.resolve(path))) {
					return true;
				}
			} catch (Exception missing) {
				// Unreadable module: treated as absent, like a missing entry.
			}
		}
		return false;
	}
}
