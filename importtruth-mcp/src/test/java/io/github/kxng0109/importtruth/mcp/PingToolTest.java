package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema;

import io.github.kxng0109.importtruth.core.PingService;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ping tool definition and result shape.
 */
@DisplayName("PingTool")
final class PingToolTest {

	private final PingTool tool = new PingTool(new PingService("importtruth", projectVersion()));

	@Test
	@DisplayName("exposes ping name with empty object schema")
	void definitionExposesPingName() {
		McpSchema.Tool definition = tool.definition();

		assertThat(definition.name()).as("tool name").isEqualTo("ping");
		assertThat(definition.inputSchema())
				.as("empty object input schema")
				.isEqualTo(Map.of("type", "object", "properties", Map.of()));
	}

	@Test
	@DisplayName("returns successful identity text")
	void callReturnsSuccessfulIdentityText() {
		McpSchema.CallToolResult result = tool.call();

		assertThat(result.isError()).as("error flag").isFalse();
		assertThat(result.content()).as("content list").hasSize(1);
		assertThat(result.content().get(0)).as("content item").isInstanceOf(McpSchema.TextContent.class);
		assertThat(((McpSchema.TextContent) result.content().get(0)).text())
				.as("identity text")
				.isEqualTo("importtruth " + projectVersion());
	}

	private static String projectVersion() {
		Properties props = new Properties();
		try (InputStream in = PingToolTest.class.getResourceAsStream("/version.properties")) {
			assertThat(in).as("version resource present").isNotNull();
			props.load(in);
		} catch (IOException failure) {
			throw new AssertionError("Cannot read version", failure);
		}
		return props.getProperty("version");
	}
}
