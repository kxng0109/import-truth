package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies how a path is confined to a project root: the paths the
 * operating system really has decide, a path that is not there is an
 * error, and a junction cannot carry a file out of the project.
 *
 * <p>The junction fixture is shared with the other core tests, so the
 * junction is made and removed the same way everywhere.</p>
 */
@DisplayName("PathConfinement")
final class PathConfinementTest {

	@TempDir
	private Path project;

	@Test
	@DisplayName("resolves a file inside the project")
	void resolvesFileInsideProject() throws Exception {
		Path file = project.resolve("src/main/java/com/example/Inside.java");
		Files.createDirectories(file.getParent());
		Files.write(file, "package com.example; public class Inside { }".getBytes(StandardCharsets.UTF_8));

		Path resolved = PathConfinement.resolve(project, file);

		assertThat(resolved)
				.as("the resolved file")
				.isEqualTo(file.toRealPath());
	}

	@Test
	@DisplayName("rejects a file outside the project")
	void rejectsFileOutsideProject() throws Exception {
		Path outside = Files.createTempFile("Elsewhere", ".java");
		try {
			assertThatThrownBy(() -> PathConfinement.resolve(project, outside))
					.as("the file outside is rejected")
					.isInstanceOf(IOException.class)
					.hasMessage("File is outside the project: " + outside);
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	@DisplayName("rejects a sibling directory that shares the project prefix")
	void rejectsSiblingWithSharedPrefix() throws Exception {
		Path sibling = project.resolveSibling(project.getFileName().toString() + "-sibling");
		Path file = sibling.resolve("com/example/Sibling.java");
		Files.createDirectories(file.getParent());
		Files.write(file, "package com.example; public class Sibling { }".getBytes(StandardCharsets.UTF_8));
		try {
			assertThatThrownBy(() -> PathConfinement.resolve(project, file))
					.as("the sibling file is rejected")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("outside the project");
		} finally {
			deleteTree(sibling);
		}
	}

	@Test
	@DisplayName("rejects a file that is not there")
	void rejectsMissingFile() throws Exception {
		Path missing = project.resolve("src/main/java/com/example/Missing.java");

		assertThatThrownBy(() -> PathConfinement.resolve(project, missing))
				.as("a missing file is an error")
				.isInstanceOf(IOException.class)
				.hasMessageContaining("does not exist");
	}

	@Test
	@DisplayName("rejects a project root that is not there")
	void rejectsMissingProjectRoot() throws Exception {
		Path root = project.resolveSibling(project.getFileName().toString() + "-absent");
		Path file = root.resolve("com/example/Absent.java");

		assertThatThrownBy(() -> PathConfinement.resolve(root, file))
				.as("a missing project root is an error")
				.isInstanceOf(IOException.class)
				.hasMessageContaining("does not exist");
	}

	@Test
	@DisplayName("rejects a file that is outside the project and not there")
	void rejectsMissingFileOutsideProject() throws Exception {
		Path missing = project.resolveSibling(project.getFileName().toString() + "-absent/Clean.java");

		assertThatThrownBy(() -> PathConfinement.resolve(project, missing))
				.as("a file that is not there keeps the outside message")
				.isInstanceOf(IOException.class)
				.hasMessage("File is outside the project: " + missing);
	}

	@Test
	@DisplayName("tells a path inside the project from a path outside it")
	void tellsInsideFromOutside() throws Exception {
		Path inside = project.resolve("src/main/java/com/example/Inside.java");
		Files.createDirectories(inside.getParent());
		Files.write(inside, "package com.example; public class Inside { }".getBytes(StandardCharsets.UTF_8));
		Path outside = Files.createTempFile("Elsewhere", ".java");
		try {
			assertThat(PathConfinement.contains(project, inside)).as("inside the project").isTrue();
			assertThat(PathConfinement.contains(project, outside)).as("outside the project").isFalse();
			assertThat(PathConfinement.contains(project, project.resolve("src/Absent.java")))
					.as("a path that is not there")
					.isFalse();
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	@DisplayName("rejects null arguments")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullArguments() {
		assertThatThrownBy(() -> PathConfinement.resolve(null, project.resolve("A.java")))
				.as("null project rejected")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> PathConfinement.resolve(project, null))
				.as("null file rejected")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> PathConfinement.contains(null, project.resolve("A.java")))
				.as("null project rejected")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> PathConfinement.contains(project, null))
				.as("null candidate rejected")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@DisplayName("rejects a file reached through a junction inside the project")
	void rejectsFileReachedThroughJunction() throws Exception {
		Path staging = FileCheckServiceTest.staging("path-confinement-junction");
		Path root = Files.createDirectories(staging.resolve("project"));
		Path outside = Files.createDirectories(staging.resolve("outside"));
		Path secret = outside.resolve("Secret.java");
		Files.write(secret, "package secret; public class Secret { }".getBytes(StandardCharsets.UTF_8));
		Path link = root.resolve("link");
		boolean survived = false;
		try {
			FileCheckServiceTest.junction(link, outside);

			assertThatThrownBy(() -> PathConfinement.resolve(root, link.resolve("Secret.java")))
					.as("the file reached through the junction is rejected")
					.isInstanceOf(IOException.class)
					.hasMessageContaining("outside the project");
		} finally {
			FileCheckServiceTest.removeJunction(link);
			survived = Files.exists(secret);
			FileCheckServiceTest.deleteTree(staging);
		}
		assertThat(survived).as("the junction target survived the cleanup").isTrue();
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@DisplayName("resolves a junction that stays inside the project to its target")
	void resolvesJunctionInsideProject() throws Exception {
		Path staging = FileCheckServiceTest.staging("path-confinement-inside");
		Path root = Files.createDirectories(staging.resolve("project"));
		Path target = Files.createDirectories(root.resolve("real"));
		Path secret = target.resolve("Secret.java");
		Files.write(secret, "package secret; public class Secret { }".getBytes(StandardCharsets.UTF_8));
		Path link = root.resolve("link");
		boolean survived = false;
		try {
			FileCheckServiceTest.junction(link, target);

			Path resolved = PathConfinement.resolve(root, link.resolve("Secret.java"));

			assertThat(resolved)
					.as("the resolved path is the target, not the link")
					.isEqualTo(secret.toRealPath());
		} finally {
			FileCheckServiceTest.removeJunction(link);
			survived = Files.exists(secret);
			FileCheckServiceTest.deleteTree(staging);
		}
		assertThat(survived).as("the junction target survived the cleanup").isTrue();
	}

	/**
	 * Removes a directory tree, if it is there.
	 *
	 * @param root tree to remove, never null
	 */
	private static void deleteTree(Path root) {
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// Best effort: the temporary area is disposable.
		}
	}
}
