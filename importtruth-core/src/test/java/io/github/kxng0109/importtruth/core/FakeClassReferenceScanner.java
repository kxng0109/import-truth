package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.model.ClassReferenceScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A test double for the class reference scanner.
 * <p>
 * The double answers by the simple name of the class files it is
 * given, so a test says which names a class carries without compiling
 * anything. A class file that is registered as failing makes the scan
 * of the directory that holds it fail, which is how a test reaches the
 * paths where a scan is incomplete.
 * <p>
 * The registry is static, because the service loader builds the double
 * itself. A test that uses it keeps its own methods on one thread.
 */
public final class FakeClassReferenceScanner implements ClassReferenceScanner {

	/** Suffix every Java class file carries. */
	private static final String CLASS_SUFFIX = ".class";

	/** The names each class carries, by simple name. */
	private static final Map<String, Set<String>> REFERENCES = new LinkedHashMap<>();

	/** The classes whose scan fails, with the message of the failure. */
	private static final Map<String, String> FAILING = new LinkedHashMap<>();

	/**
	 * Registers the classes a class with this simple name carries.
	 *
	 * @param simpleName simple name of the class, never null
	 * @param names       binary names the class carries, never null
	 */
	public static void carries(String simpleName, String... names) {
		REFERENCES.put(simpleName, Set.of(names));
	}

	/**
	 * Makes the scan of a class with this simple name fail.
	 *
	 * @param simpleName simple name of the class, never null
	 */
	public static void failOn(String simpleName) {
		failOn(simpleName, "the class " + simpleName + " could not be read");
	}

	/**
	 * Makes the scan of a class with this simple name fail with the
	 * given message. A null message is a failure that names nothing.
	 *
	 * @param simpleName simple name of the class, never null
	 * @param message    message of the failure, may be null
	 */
	public static void failOn(String simpleName, String message) {
		FAILING.put(simpleName, message);
	}

	/**
	 * Empties the registry, so one test never leaks into the next.
	 */
	public static void reset() {
		REFERENCES.clear();
		FAILING.clear();
	}

	@Override
	public Set<String> referencesOf(Path classFileOrDirectory) throws IOException {
		if (classFileOrDirectory == null) {
			throw new NullPointerException("classFileOrDirectory");
		}
		Set<String> names = new LinkedHashSet<>();
		for (String simple : simpleNames(classFileOrDirectory)) {
			if (FAILING.containsKey(simple)) {
				throw new IOException(FAILING.get(simple));
			}
			names.addAll(REFERENCES.getOrDefault(simple, Set.of()));
		}
		return names;
	}

	/**
	 * Lists the simple names of the class files a path holds.
	 *
	 * @param path class file or directory of class files, never null
	 * @return the simple names, never null
	 * @throws IOException when the path cannot be read
	 */
	private static Set<String> simpleNames(Path path) throws IOException {
		Set<String> names = new LinkedHashSet<>();
		if (Files.isDirectory(path)) {
			try (Stream<Path> walk = Files.walk(path)) {
				walk.filter(Files::isRegularFile)
						.map(file -> file.getFileName().toString())
						.filter(name -> name.endsWith(CLASS_SUFFIX))
						.forEach(name -> names.add(name.substring(0, name.length() - CLASS_SUFFIX.length())));
			}
			return names;
		}
		String name = path.getFileName().toString();
		if (name.endsWith(CLASS_SUFFIX)) {
			names.add(name.substring(0, name.length() - CLASS_SUFFIX.length()));
		}
		return names;
	}
}
