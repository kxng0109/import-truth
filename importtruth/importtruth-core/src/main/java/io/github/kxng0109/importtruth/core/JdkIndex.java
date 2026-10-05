package io.github.kxng0109.importtruth.core;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers whether a name exists in the running JDK image. Records which
 * JDK answered, because the tool JDK may differ from the project JDK.
 */
public final class JdkIndex {

	private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
	private final String jdkVersion = System.getProperty("java.version", "unknown");

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
		FileSystem image = FileSystems.getFileSystem(URI.create("jrt:/"));
		String dotted = fqn.replace('.', '/') + ".class";
		if (modulesContain(image, dotted)) {
			return true;
		}
		String nested = dotted.substring(0, dotted.length() - ".class".length());
		int slash;
		while ((slash = nested.lastIndexOf('/')) > 0) {
			nested = nested.substring(0, slash) + "$" + nested.substring(slash + 1);
			if (modulesContain(image, nested + ".class")) {
				return true;
			}
		}
		return false;
	}

	private static boolean modulesContain(FileSystem image, String member) {
		Path modules = image.getPath("/modules");
		try (var entries = Files.list(modules)) {
			return entries.anyMatch(module -> Files.exists(module.resolve(member)));
		} catch (Exception missing) {
			return false;
		}
	}
}
