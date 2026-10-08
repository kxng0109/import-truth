package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies argument validation and limit clamping.
 */
@DisplayName("McpArgs")
final class McpArgsTest {

	@Test
	@DisplayName("reads strings and lists strictly")
	@SuppressWarnings("DataFlowIssue")
	void readsStrictly() {
		Map<String, Object> arguments = Map.of("name", "x", "items", List.of("a"));

		assertThat(McpArgs.string(arguments, "name")).as("present").hasValue("x");
		assertThat(McpArgs.string(arguments, "missing")).as("missing").isEmpty();
		assertThat(McpArgs.string(Map.of("name", " "), "name")).as("blank").isEmpty();
		assertThat(McpArgs.string(Map.of("name", 42), "name")).as("mistyped").isEmpty();
		assertThat(McpArgs.strings(arguments, "items")).as("present list").isPresent();
		assertThat(McpArgs.strings(Map.of("items", List.of()), "items")).as("empty list").isEmpty();
		assertThat(McpArgs.strings(arguments, "missing")).as("missing list").isEmpty();
		assertThatThrownBy(() -> McpArgs.string(null, "name")).as("null arguments")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> McpArgs.string(arguments, null)).as("null name")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> McpArgs.strings(null, "items")).as("null list arguments")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("clamps limits into range")
	void clampsLimits() {
		assertThat(McpArgs.boundedLimit(99, 5, 25)).as("clamped high").isEqualTo(25);
		assertThat(McpArgs.boundedLimit(-3, 5, 25)).as("clamped low").isEqualTo(1);
		assertThat(McpArgs.boundedLimit(7, 5, 25)).as("kept").isEqualTo(7);
		assertThat(McpArgs.boundedLimit("many", 5, 25)).as("default on text").isEqualTo(5);
		assertThat(McpArgs.boundedLimit(null, 5, 25)).as("default on null").isEqualTo(5);
	}
}
