package io.github.kxng0109.importtruth.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies stamp keys and digests.
 */
@DisplayName("JarStamp and Sha256")
final class StampTest {

	@TempDir
	private Path files;

	@Test
	@DisplayName("stamps files and digests bytes")
	@SuppressWarnings("DataFlowIssue")
	void stampsAndDigests() throws Exception {
		Path file = files.resolve("a.jar");
		Files.write(file, "bytes".getBytes(StandardCharsets.UTF_8));

		assertThat(JarStamp.key(file)).as("stamp shape").contains(":");
		assertThat(Sha256.ofBytes("x".getBytes(StandardCharsets.UTF_8))).as("digest length")
				.hasSize(64);
		assertThatThrownBy(() -> JarStamp.key(null)).as("null file")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Sha256.ofBytes(null)).as("null bytes")
				.isInstanceOf(NullPointerException.class);
	}
}
