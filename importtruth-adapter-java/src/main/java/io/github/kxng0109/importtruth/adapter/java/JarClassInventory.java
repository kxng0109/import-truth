package io.github.kxng0109.importtruth.adapter.java;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lists the classes an archive holds.
 * <p>
 * The list comes from the entry list of the archive, so no entry is
 * opened and no class is read. Every class the archive holds is
 * listed, including the package-private ones that a public API
 * index leaves out.
 * <p>
 * A multi-release archive holds the same class under several names.
 * Only the entry the running runtime would load counts, and the
 * prefix is stripped, so the base entry and the versioned entry
 * collapse to the one name they share.
 */
public final class JarClassInventory {

	/** Prefix of a versioned entry in a multi-release archive. */
	private static final String VERSIONS_PREFIX = "META-INF/versions/";

	/** Lowest release a multi-release archive may hold. */
	private static final int FIRST_RELEASE = 9;

	/** Suffix every Java class file carries. */
	private static final String CLASS_SUFFIX = ".class";

	private JarClassInventory() {
	}

	/**
	 * Lists the classes the archive holds.
	 *
	 * @param jar archive file, never null
	 * @return binary names of the classes, never null
	 * @throws IOException when the archive cannot be read
	 * @throws NullPointerException when {@code jar} is {@code null}
	 */
	public static Set<String> classesIn(Path jar) throws IOException {
		Objects.requireNonNull(jar, "jar");
		Set<String> entries = new LinkedHashSet<>();
		int runtime = Runtime.version().feature();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> names = zip.entries();
			while (names.hasMoreElements()) {
				ZipEntry entry = names.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				String name = entry.getName();
				if (!name.endsWith(CLASS_SUFFIX)) {
					continue;
				}
				if (name.startsWith(VERSIONS_PREFIX)) {
					name = versionedName(name, runtime);
					if (name == null) {
						continue;
					}
				}
				entries.add(name);
			}
		}
		return binaryNames(entries);
	}

	/**
	 * Strips the version prefix of a multi-release entry.
	 *
	 * @param name entry name, never null
	 * @param runtime feature release of the running runtime
	 * @return the name without its prefix, or null when the release does not run here
	 */
	private static String versionedName(String name, int runtime) {
		int slash = name.indexOf('/', VERSIONS_PREFIX.length());
		if (slash < 0) {
			return null;
		}
		int release;
		try {
			release = Integer.parseInt(name.substring(VERSIONS_PREFIX.length(), slash));
		} catch (NumberFormatException notVersioned) {
			return null;
		}
		if (release < FIRST_RELEASE || release > runtime) {
			return null;
		}
		return name.substring(slash + 1);
	}

	/**
	 * Converts entry names to binary names.
	 *
	 * @param entries entry names of class files, never null
	 * @return binary names, never null
	 */
	private static Set<String> binaryNames(Set<String> entries) {
		Set<String> names = new LinkedHashSet<>();
		for (String entry : entries) {
			String withoutSuffix = entry.substring(0, entry.length() - CLASS_SUFFIX.length());
			names.add(withoutSuffix.replace('/', '.'));
		}
		return names;
	}
}
