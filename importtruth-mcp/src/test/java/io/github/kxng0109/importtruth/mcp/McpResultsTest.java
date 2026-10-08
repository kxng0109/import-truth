package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies result shapes.
 */
@DisplayName("McpResults")
final class McpResultsTest {

	@Test
	@DisplayName("builds success and error results")
	@SuppressWarnings("DataFlowIssue")
	void buildsResults() {
		CallToolResult ok = McpResults.ok(List.of("a", "b"));
		CallToolResult single = McpResults.ok("solo");
		CallToolResult err = McpResults.err("bad");

		assertThat(ok.isError()).as("success flag").isFalse();
		assertThat(((TextContent) ok.content().get(0)).text()).as("joined lines").isEqualTo("a\nb");
		assertThat(((TextContent) single.content().get(0)).text()).as("single line").isEqualTo("solo");
		assertThat(err.isError()).as("error flag").isTrue();
		assertThat(((TextContent) err.content().get(0)).text()).as("error text").isEqualTo("bad");
		assertThatThrownBy(() -> McpResults.ok((List<String>) null)).as("null rows")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> McpResults.ok((String) null)).as("null line")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> McpResults.err(null)).as("null message")
				.isInstanceOf(NullPointerException.class);
	}
}
