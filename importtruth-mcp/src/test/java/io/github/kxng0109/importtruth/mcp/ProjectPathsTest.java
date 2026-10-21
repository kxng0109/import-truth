package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies how a requested file path is resolved against the project
 * root: the reading that already works wins, and a path that resolves
 * nowhere keeps the rejection.
 */
@DisplayName("ProjectPaths")
final class ProjectPathsTest {

	@TempDir
	private Path project;

	@Test
	@DisplayName("keeps an absolute path as it is")
	void keepsAbsolutePath() {
		Path absolute = project.resolve("src/main/java/com/other/Clean.java");

		assertThat(ProjectPaths.resolve(project, absolute))
				.as("absolute path unchanged")
				.isEqualTo(absolute);
	}

	@Test
	@DisplayName("keeps a path that lands inside the project")
	void keepsWorkingDirectoryPath() {
		Path root = Paths.get("").toAbsolutePath();
		Path relative = Paths.get("src", "main", "java", "com", "other", "Clean.java");

		assertThat(ProjectPaths.resolve(root, relative))
				.as("working directory path unchanged")
				.isEqualTo(relative);
	}

	@Test
	@DisplayName("reads a relative path against the project root")
	void readsRelativePathAgainstProject() throws Exception {
		Path source = project.resolve("src/main/java/com/other/Clean.java");
		Files.createDirectories(source.getParent());
		Files.write(source, "package com.other; public class Clean { }".getBytes(StandardCharsets.UTF_8));
		Path relative = Paths.get("src", "main", "java", "com", "other", "Clean.java");

		Path resolved = ProjectPaths.resolve(project, relative);

		assertThat(resolved)
				.as("read against the project root")
				.isEqualTo(project.resolve("src/main/java/com/other/Clean.java"));
		assertThat(Files.isRegularFile(resolved)).as("the file is there").isTrue();
	}

	@Test
	@DisplayName("keeps a path that exists nowhere")
	void keepsUnknownPath() {
		Path relative = Paths.get("src", "main", "java", "com", "other", "Nope.java");

		assertThat(ProjectPaths.resolve(project, relative))
				.as("unknown path unchanged")
				.isEqualTo(relative);
	}

	@Test
	@DisplayName("keeps a path that names a directory")
	void keepsDirectoryPath() throws Exception {
		Files.createDirectories(project.resolve("src/main/java/com/other"));
		Path relative = Paths.get("src", "main", "java", "com", "other");

		assertThat(ProjectPaths.resolve(project, relative))
				.as("directory path unchanged")
				.isEqualTo(relative);
	}

	@Test
	@DisplayName("keeps a path that leaves the project")
	void keepsEscapingPath() {
		Path relative = Paths.get("..", "outside", "Nope.java");

		assertThat(ProjectPaths.resolve(project, relative))
				.as("escaping path unchanged")
				.isEqualTo(relative);
	}

	@Test
	@DisplayName("keeps a working directory path that lands inside the project")
	void keepsExistingWorkingDirectoryPath() throws Exception {
		Path scratch = Paths.get("").toAbsolutePath().resolve("target").resolve("importtruth-projectpaths-project");
		deleteTree(scratch);
		Files.createDirectories(scratch.resolve("src/main/java/com/other"));
		Path source = scratch.resolve("src/main/java/com/other/Clean.java");
		Files.writeString(source, "package com.other; public class Clean { }");
		Path shadow = scratch.resolve("target/importtruth-projectpaths-project/src/main/java/com/other/Clean.java");
		Files.createDirectories(shadow.getParent());
		Files.writeString(shadow, "package com.other; public class Shadow { }");
		try {
			Path relative = Paths.get("").toAbsolutePath().relativize(source);

			Path resolved = ProjectPaths.resolve(scratch, relative);

			assertThat(resolved)
					.as("the working directory reading wins and is not shadowed")
					.isEqualTo(relative);
		} finally {
			deleteTree(scratch);
		}
	}

	@Test
	@EnabledOnOs(OS.WINDOWS)
	@DisplayName("does not hand the core a path that only looks inside the project")
	void doesNotHandCoreAJunctionPath() throws Exception {
		Path staging = staging("project-paths-junction");
		Path root = Files.createDirectories(staging.resolve("project"));
		Path outside = Files.createDirectories(staging.resolve("outside"));
		Path secret = outside.resolve("Secret.java");
		Files.writeString(secret, "package secret; public class Secret { }");
		Path link = root.resolve("link");
		boolean survived = false;
		try {
			junction(link, outside);
			Path relative = Paths.get("link", "Secret.java");

			Path resolved = ProjectPaths.resolve(root, relative);

			assertThat(resolved)
					.as("the path is not claimed to be inside the project")
					.isEqualTo(relative);
		} finally {
			removeJunction(link);
			survived = Files.exists(secret);
			deleteTree(staging);
		}
		assertThat(survived).as("the junction target survived the cleanup").isTrue();
	}

	@Test
	@DisplayName("rejects null arguments")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullArguments() {
		assertThatThrownBy(() -> ProjectPaths.resolve(null, Paths.get("Clean.java")))
				.as("null project rejected")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ProjectPaths.resolve(project, null))
				.as("null file rejected")
				.isInstanceOf(NullPointerException.class);
	}

	/**
	 * Creates a directory junction. A junction needs no privilege, and
	 * the JDK resolves one to its target, so a path spelled inside the
	 * project can name a file outside it.
	 *
	 * @param link   junction to create, never null
	 * @param target directory the junction points at, never null
	 * @throws IOException when the junction cannot be created
	 * @throws InterruptedException when the command is interrupted
	 */
	private static void junction(Path link, Path target) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("cmd", "/d", "/c", "mklink", "/J",
				link.toString(), target.toString())
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IOException("mklink /J failed: " + output.trim());
		}
	}

	/**
	 * Removes a junction without touching its target. A recursive
	 * delete would follow the junction into the target, so the junction
	 * is removed the way Windows removes it.
	 *
	 * @param link junction to remove, never null
	 * @throws IOException when the junction cannot be removed
	 * @throws InterruptedException when the command is interrupted
	 */
	private static void removeJunction(Path link) throws IOException, InterruptedException {
		Process process = new ProcessBuilder("cmd", "/d", "/c", "rmdir", link.toString())
				.redirectErrorStream(true)
				.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IOException("rmdir failed: " + output.trim());
		}
	}

	/**
	 * The directory a junction test stages its files in. It sits under
	 * the .tmp folder of the repository, so a test that fails half way
	 * leaves its files where they can be found and removed. The target
	 * of the junction is a sibling of the project inside that folder,
	 * so even a mistaken recursive delete can only reach scratch
	 * files.
	 *
	 * @param name name of the staging directory, never null
	 * @return the staging directory, never null
	 * @throws IOException when the staging directory cannot be created
	 */
	private static Path staging(String name) throws IOException {
		Path repository = Paths.get(System.getProperty("user.dir")).getParent();
		Path staging = repository.resolve(".tmp").resolve(name);
		Files.createDirectories(staging);
		return staging;
	}

	/**
	 * Removes a directory tree, if it is there.
	 *
	 * @param root tree to remove, never null
	 */
	private static void deleteTree(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException ignored) {
			// Best effort: the scratch area is disposable.
		}
	}
}
