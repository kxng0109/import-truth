package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.PathConfinement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves the file a model asked about to the path the check tools
 * read.
 *
 * <p>A relative path is read against the process working directory,
 * which is where the server was started, not where the project lives.
 * A path that lands outside the project is therefore read a second
 * time against the project root, so a path written relative to the
 * project still finds its file. The reading that already works always
 * wins, and a path that resolves nowhere keeps the rejection the
 * containment check gives it.</p>
 *
 * <p>Whether a reading lands inside the project is decided by the
 * core confinement rule, on the paths the operating system really has.
 * A path that only looks inside the project, because a directory of it
 * is a junction pointing elsewhere, is not a reading inside the
 * project, and the core service rejects it as it always did.</p>
 */
final class ProjectPaths {

	private ProjectPaths() {
	}

	/**
	 * Resolves one requested file against the project root.
	 *
	 * <p>The order is fixed. An absolute path is used as it is. A
	 * relative path is read against the working directory first, and
	 * when that reading lands inside the project it is used as it is.
	 * Only when the working directory reading lands outside the
	 * project is the same relative path read against the project root,
	 * and only when that reading lands inside the project and names a
	 * file that is there. Every other path is returned unchanged, so
	 * the containment check of the check service rejects it as
	 * before.</p>
	 *
	 * @param projectDir project root, never null
	 * @param file       file the model asked about, never null
	 * @return the path the tool should read, never null
	 * @throws NullPointerException if any argument is {@code null}
	 */
	static Path resolve(Path projectDir, Path file) {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(file, "file");
		if (file.isAbsolute()) {
			return file;
		}
		Path working = file.toAbsolutePath().normalize();
		if (PathConfinement.contains(projectDir, working)) {
			return file;
		}
		Path candidate = projectDir.toAbsolutePath().normalize().resolve(file).normalize();
		if (PathConfinement.contains(projectDir, candidate) && Files.isRegularFile(candidate)) {
			return candidate;
		}
		return file;
	}
}
