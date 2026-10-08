package io.github.kxng0109.importtruth.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Objects;

/**
 * Builds tool results in one consistent shape.
 */
final class McpResults {

	private McpResults() {
	}

	/**
	 * Builds a successful result with one line per row.
	 *
	 * @param rows answer lines, never null
	 * @return successful result, never null
	 */
	static McpSchema.CallToolResult ok(List<String> rows) {
		Objects.requireNonNull(rows, "rows");
		McpSchema.TextContent text =
				McpSchema.TextContent.builder(String.join("\n", rows)).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), false, null, null);
	}

	/**
	 * Builds a successful single-line result.
	 *
	 * @param line answer line, never null
	 * @return successful result, never null
	 */
	static McpSchema.CallToolResult ok(String line) {
		Objects.requireNonNull(line, "line");
		return ok(List.of(line));
	}

	/**
	 * Builds an error result.
	 *
	 * @param message error message, never null
	 * @return error result, never null
	 */
	static McpSchema.CallToolResult err(String message) {
		Objects.requireNonNull(message, "message");
		McpSchema.TextContent text = McpSchema.TextContent.builder(message).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), true, null, null);
	}
}
