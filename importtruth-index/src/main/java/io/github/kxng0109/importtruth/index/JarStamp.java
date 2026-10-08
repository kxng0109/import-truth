package io.github.kxng0109.importtruth.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * File identity stamps for caches: size plus timestamp. Cheap to
 * compute, strong enough to skip re-hashing unchanged files.
 */
public final class JarStamp {

	private JarStamp() {
	}

	/**
	 * Stamps the file.
	 *
	 * @param file file, never null
	 * @return {@code millis:size}, never null
	 * @throws IOException when stats cannot be read
	 * @throws NullPointerException if {@code file} is {@code null}
	 */
	public static String key(Path file) throws IOException {
		Objects.requireNonNull(file, "file");
		return Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
	}
}
