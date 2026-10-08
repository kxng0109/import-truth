package io.github.kxng0109.importtruth.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRuleKind;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies pack loading: the shipped Jackson pack and malformed input.
 */
@DisplayName("PackLoader")
final class PackLoaderTest {

	@Test
	@DisplayName("loads the shipped Jackson pack")
	void loadsJacksonPack() throws Exception {
		PolicyPack pack;
		try (InputStream in = PackLoaderTest.class.getResourceAsStream("/packs/jackson3.yaml")) {
			assertThat(in).as("shipped pack present").isNotNull();
			pack = PackLoader.load("jackson3", in);
		}

		assertThat(pack.rules()).as("rule count").hasSize(3);
		assertThat(pack.rules().get(0).kind()).as("first rule prefer").isEqualTo(PolicyRuleKind.PREFER);
		assertThat(pack.rules().get(1).kind()).as("second rule allow").isEqualTo(PolicyRuleKind.ALLOW);
		assertThat(pack.rules().get(2).kind()).as("third rule rename").isEqualTo(PolicyRuleKind.RENAME);
	}

	@Test
	@DisplayName("rejects non-mapping documents")
	void rejectsNonMapping() {
		assertThatThrownBy(() -> PackLoader.load("bad", bytes("- just\n- a\n- list\n")))
				.as("list rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects oversized input")
	void rejectsOversized() {
		byte[] big = new byte[PackLoader.MAX_BYTES + 1];
		assertThatThrownBy(() -> PackLoader.load("big", new ByteArrayInputStream(big)))
				.as("oversize rejected")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("rejects malformed sections and values")
	void rejectsMalformed() {
		assertThatThrownBy(() -> PackLoader.load("bad", bytes("prefer: oops\n")))
				.as("non-list section rejected")
				.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> PackLoader.load("bad", bytes("prefer:\n  - oops\n")))
				.as("non-mapping entry rejected")
				.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> PackLoader.load("bad", bytes("allow:\n  - 42\n")))
				.as("non-text allow rejected")
				.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> PackLoader.load("bad", bytes("prefer:\n  - over: 42\n    package: a.b\n")))
				.as("non-text value rejected")
				.isInstanceOf(IOException.class);
		assertThatThrownBy(() -> PackLoader.load("  ", bytes("prefer: []\n")))
				.as("blank name rejected")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("loads map-style allow entries")
	void loadsMapAllows() throws Exception {
		PolicyPack pack = PackLoader.load("test", bytes("allow:\n  - package: com.example.keep\n"));

		assertThat(pack.rules()).as("one allow rule").hasSize(1);
		assertThat(pack.rules().get(0).kind()).as("allow kind").isEqualTo(PolicyRuleKind.ALLOW);
	}

	@Test
	@DisplayName("reads project packs with override or default")
	@SuppressWarnings("DataFlowIssue")
	void readsProjectPacks() throws Exception {
		Path dir = Files.createTempDirectory("packs");
		try {
			PolicyPack fallback = PackLoader.loadProjectPack(PackLoaderTest.class, dir);

			assertThat(fallback.name()).as("default pack").isEqualTo("jackson3");

			Files.write(dir.resolve(".importtruth.yml"),
					"allow:\n  - package: com.example.keep\n".getBytes(StandardCharsets.UTF_8));
			PolicyPack override = PackLoader.loadProjectPack(PackLoaderTest.class, dir);

			assertThat(override.name()).as("override pack").isEqualTo("project");
			assertThat(override.rules()).as("one allow rule").hasSize(1);
			assertThatThrownBy(() -> PackLoader.loadProjectPack(null, dir)).as("null anchor")
					.isInstanceOf(NullPointerException.class);
			assertThatThrownBy(() -> PackLoader.loadProjectPack(PackLoaderTest.class, null))
					.as("null project")
					.isInstanceOf(NullPointerException.class);
		} finally {
			deleteTree(dir);
		}
	}

	private static void deleteTree(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static InputStream bytes(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}
}
