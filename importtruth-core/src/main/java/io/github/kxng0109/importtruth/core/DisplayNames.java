package io.github.kxng0109.importtruth.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Shared file display names: project-relative with forward slashes,
 * falling back to the bare file name outside the project.
 */
public final class DisplayNames {

	private DisplayNames() {
	}

	/**
	 * Names the file for findings.
	 *
	 * @param projectDir project root, never null
	 * @param file       source file, never null
	 * @return relative display name, or the bare file name, never null
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public static String relativizeOrFileName(Path projectDir, Path file) {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(file, "file");
		try {
			return projectDir.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
		} catch (IllegalArgumentException notRelative) {
			return file.getFileName().toString();
		}
	}
}
