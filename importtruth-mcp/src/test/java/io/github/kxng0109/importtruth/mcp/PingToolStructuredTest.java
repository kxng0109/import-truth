package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.core.PingService;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the machine readable channel of the ping tool: the status
 * of a live server, and the error shape of a failed one.
 */
@DisplayName("Ping tool structured content")
final class PingToolStructuredTest {

	private final PingTool tool = new PingTool(new PingService("importtruth", projectVersion()));

	@Test
	@DisplayName("carries the status and the identity")
	void carriesStatusAndIdentity() {
		CallToolResult result = tool.call();
		Map<String, Object> structured = structuredOf(result);

		assertThat(result.isError()).as("error flag").isFalse();
		assertThat(((TextContent) result.content().get(0)).text()).as("rendered text")
				.isEqualTo("importtruth " + projectVersion());
		assertThat(structured).as("status").containsEntry("status", "OK");
		assertThat(structured).as("name").containsEntry("name", "importtruth");
		assertThat(structured).as("version").containsEntry("version", projectVersion());
	}

	@Test
	@DisplayName("marks a failed call as an error")
	void marksFailedCall() {
		PingTool failing = new PingTool(() -> {
			throw new IllegalStateException("no identity");
		});

		CallToolResult result = failing.call();
		Map<String, Object> structured = structuredOf(result);

		assertThat(result.isError()).as("error flag").isTrue();
		assertThat(structured).as("status").containsEntry("status", "ERROR");
		assertThat((String) structured.get("message")).as("message").contains("ping failed");
		assertThat(structured).as("a failure carries no verdict").doesNotContainKey("name");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> structuredOf(CallToolResult result) {
		assertThat(result.structuredContent()).as("structured content present").isInstanceOf(Map.class);
		return (Map<String, Object>) result.structuredContent();
	}

	private static String projectVersion() {
		Properties props = new Properties();
		try (InputStream in = PingToolStructuredTest.class.getResourceAsStream("/version.properties")) {
			assertThat(in).as("version resource present").isNotNull();
			props.load(in);
		} catch (IOException failure) {
			throw new AssertionError("Cannot read version", failure);
		}
		return props.getProperty("version");
	}
}
