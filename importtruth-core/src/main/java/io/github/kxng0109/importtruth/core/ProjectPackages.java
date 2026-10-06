package io.github.kxng0109.importtruth.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Learns a project's own packages from source and output directories.
 * A directory counts as a package only when it holds compiled classes or
 * sources, never from bare directory existence.
 */
public final class ProjectPackages {

	private ProjectPackages() {
	}

	/**
	 * Collects own-project package names.
	 *
	 * @param projectDir project root, never null
	 * @return dotted package names, never null
	 * @throws NullPointerException if {@code projectDir} is {@code null}
	 */
	public static Set<String> of(Path projectDir) {
		Objects.requireNonNull(projectDir, "projectDir");
		Set<String> packages = new HashSet<>();
		for (String root : List.of("src/main/java", "src/test/java")) {
			collectSources(projectDir.resolve(root), packages);
		}
		for (String root : List.of("target/classes", "target/test-classes")) {
			collectClasses(projectDir.resolve(root), packages);
		}
		return packages;
	}

	private static void collectSources(Path root, Set<String> packages) {
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(p -> p.toString().endsWith(".java"))
					.map(p -> root.relativize(p.getParent()))
					.map(ProjectPackages::dotted)
					.forEach(packages::add);
		} catch (IOException ignored) {
			// Best effort: a partial package set only weakens candidate hints.
		}
	}

	private static void collectClasses(Path root, Set<String> packages) {
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(p -> p.toString().endsWith(".class"))
					.map(p -> root.relativize(p.getParent()))
					.map(ProjectPackages::dotted)
					.forEach(packages::add);
		} catch (IOException ignored) {
			// Best effort: a partial package set only weakens candidate hints.
		}
	}

	private static String dotted(Path relative) {
		StringBuilder dotted = new StringBuilder();
		for (Path part : relative) {
			if (!dotted.isEmpty()) {
				dotted.append('.');
			}
			dotted.append(part);
		}
		return dotted.toString();
	}
}
