package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.model.Symbol;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds tool results in one consistent shape. A result carries the
 * rendered lines for the model and, for a tool with a verdict, the same
 * answer as structured content for a machine. The two are never
 * mirrors of each other: the lines stay short, and the structured
 * content holds the details a line cannot, such as a method descriptor.
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
	 * Builds a successful result with one line per row and the same
	 * answer as structured content for a machine.
	 *
	 * @param rows       answer lines, never null
	 * @param structured machine readable answer, never null
	 * @return successful result, never null
	 */
	static McpSchema.CallToolResult ok(List<String> rows, Map<String, Object> structured) {
		Objects.requireNonNull(rows, "rows");
		Objects.requireNonNull(structured, "structured");
		McpSchema.TextContent text =
				McpSchema.TextContent.builder(String.join("\n", rows)).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), false, structured, null);
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

	/**
	 * Builds an error result with the failure as structured content.
	 *
	 * @param message    error message, never null
	 * @param structured machine readable failure, never null
	 * @return error result, never null
	 */
	static McpSchema.CallToolResult err(String message, Map<String, Object> structured) {
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(structured, "structured");
		McpSchema.TextContent text = McpSchema.TextContent.builder(message).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), true, structured, null);
	}

	/**
	 * Builds the machine readable body of a failed call. A failure is
	 * not a verdict, so the status says {@code ERROR} and no caller can
	 * read a failed call as a missing symbol.
	 *
	 * @param message error message, never null
	 * @return structured content, never null
	 */
	static Map<String, Object> failure(String message) {
		Objects.requireNonNull(message, "message");
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("status", "ERROR");
		content.put("message", message);
		return content;
	}

	/**
	 * Builds the machine readable body of one failed symbol.
	 *
	 * @param name    symbol that failed, never null
	 * @param message failure message, never null
	 * @return structured content, never null
	 */
	static Map<String, Object> failure(String name, String message) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(message, "message");
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("status", "ERROR");
		content.put("name", name);
		content.put("message", message);
		return content;
	}

	/**
	 * Builds the machine readable entry of one indexed symbol. The
	 * descriptor is the method signature, so two overloads of one name
	 * stay apart. A key is present only when its value is known.
	 *
	 * @param symbol indexed symbol, never null
	 * @return structured entry, never null
	 */
	static Map<String, Object> symbol(Symbol symbol) {
		Objects.requireNonNull(symbol, "symbol");
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("name", symbol.fqn());
		entry.put("kind", symbol.kind().name());
		if (symbol.signature() != null) {
			entry.put("descriptor", symbol.signature());
		}
		entry.put("deprecated", symbol.deprecated());
		if (!symbol.deprecatedSince().isEmpty()) {
			entry.put("deprecatedSince", symbol.deprecatedSince());
		}
		entry.put("forRemoval", symbol.forRemoval());
		return entry;
	}
}
