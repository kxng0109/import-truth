package io.github.kxng0109.importtruth.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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
	@DisplayName("hashes files with a fixed buffer")
	void hashesFilesStreamed() throws Exception {
		Path empty = files.resolve("empty.jar");
		Files.write(empty, new byte[0]);
		Path one = files.resolve("one.jar");
		Files.write(one, new byte[]{7});
		byte[] big = new byte[200000];
		for (int i = 0; i < big.length; i++) {
			big[i] = (byte) (i % 251);
		}
		Path large = files.resolve("large.jar");
		Files.write(large, big);

		assertThat(Sha256.ofFile(empty)).as("empty digest")
				.isEqualTo(Sha256.ofBytes(new byte[0]));
		assertThat(Sha256.ofFile(one)).as("one byte digest")
				.isEqualTo(Sha256.ofBytes(new byte[]{7}));
		assertThat(Sha256.ofFile(large)).as("large digest matches bytes")
				.isEqualTo(Sha256.ofBytes(big));
		assertThatThrownBy(() -> Sha256.ofFile(files.resolve("ghost.jar")))
				.as("missing file")
				.isInstanceOf(IOException.class);
	}

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
