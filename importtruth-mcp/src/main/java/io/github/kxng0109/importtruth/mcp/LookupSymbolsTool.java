package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.LookupService;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Answers many symbols in one call. Each item keeps the exact lines
 * the single-symbol tool would print, so batches never disagree
 * with one-off answers.
 */
public final class LookupSymbolsTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "lookup_symbols";

	/** Maximum symbols per call. Keeps answers small enough to read. */
	static final int MAX_SYMBOLS = 50;

	private final LookupTool lookup;

	/**
	 * Creates the tool.
	 *
	 * @param lookup backing service, never null
	 * @throws NullPointerException if {@code lookup} is {@code null}
	 */
	public LookupSymbolsTool(LookupService lookup) {
		this.lookup = new LookupTool(Objects.requireNonNull(lookup, "lookup"));
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return batch lookup tool definition
	 */
	public McpSchema.Tool definition() {
		return McpSchema.Tool.builder(
						TOOL_NAME,
						Map.of(
								"type",
								"object",
								"properties",
								Map.of(
										"projectPath", Map.of("type", "string"),
										"symbols", Map.of("type", "array", "items", Map.of("type", "string"))),
								"required",
								List.of("projectPath", "symbols")))
				.description("Checks many fully qualified names against the project classpath in one call.")
				.build();
	}

	/**
	 * Handles a batch lookup call.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one block per symbol, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Object project = arguments.get("projectPath");
		Object raw = arguments.get("symbols");
		if (!(project instanceof String projectPath) || projectPath.isBlank()) {
			return error("projectPath is a required string");
		}
		if (!(raw instanceof List<?> symbols) || symbols.isEmpty()) {
			return error("symbols is a required non-empty array");
		}
		if (symbols.size() > MAX_SYMBOLS) {
			return error("at most " + MAX_SYMBOLS + " symbols per call");
		}
		List<String> lines = new ArrayList<>();
		for (Object item : symbols) {
			if (!(item instanceof String name) || name.isBlank()) {
				lines.add("ERROR symbol must be a non-blank string");
				continue;
			}
			McpSchema.CallToolResult single =
					lookup.call(Map.of("projectPath", projectPath, "symbol", name));
			String text = ((McpSchema.TextContent) single.content().get(0)).text();
			if (single.isError()) {
				lines.add("ERROR " + name + ": " + text);
			} else {
				lines.addAll(List.of(text.split("\n")));
			}
		}
		McpSchema.TextContent text = McpSchema.TextContent.builder(String.join("\n", lines)).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), false, null, null);
	}

	private static McpSchema.CallToolResult error(String message) {
		McpSchema.TextContent text = McpSchema.TextContent.builder(message).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), true, null, null);
	}
}
