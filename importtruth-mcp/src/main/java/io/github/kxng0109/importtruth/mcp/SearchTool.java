package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.SearchService;
import io.github.kxng0109.importtruth.model.Symbol;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Searches indexed dependency names by substring.
 */
public final class SearchTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "search_api";

	/** Default and maximum row counts. */
	static final int DEFAULT_LIMIT = 5;
	static final int MAX_LIMIT = 25;

	private final SearchService search;

	/**
	 * Creates the tool.
	 *
	 * @param search backing service, never null
	 * @throws NullPointerException if {@code search} is {@code null}
	 */
	public SearchTool(SearchService search) {
		this.search = Objects.requireNonNull(search, "search");
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return search tool definition
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
										"query", Map.of("type", "string"),
										"limit", Map.of("type", "integer", "default", DEFAULT_LIMIT)),
								"required",
								List.of("projectPath", "query")))
				.description("Searches indexed dependency names by substring.")
				.build();
	}

	/**
	 * Handles a search call.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one line per match, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Object project = arguments.get("projectPath");
		Object query = arguments.get("query");
		if (!(project instanceof String projectPath) || projectPath.isBlank()
				|| !(query instanceof String text) || text.isBlank()) {
			return error("projectPath and query are required strings");
		}
		int limit = limitOf(arguments.get("limit"));
		List<String> lines = new ArrayList<>();
		try {
			for (Symbol match : search.search(Paths.get(projectPath), text, limit)) {
				StringBuilder row = new StringBuilder("hit: ").append(match.fqn())
						.append(" (").append(match.kind().name().toLowerCase()).append(')');
				if (match.deprecated()) {
					row.append(" deprecated");
				}
				lines.add(row.toString());
			}
		} catch (Exception failure) {
			return error("search failed: " + failure.getMessage());
		}
		if (lines.isEmpty()) {
			lines.add("no matches for " + text);
		}
		McpSchema.TextContent content = McpSchema.TextContent.builder(String.join("\n", lines)).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(content), false, null, null);
	}

	private static McpSchema.CallToolResult error(String message) {
		McpSchema.TextContent text = McpSchema.TextContent.builder(message).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), true, null, null);
	}

	static int limitOf(Object raw) {
		if (raw instanceof Number number) {
			return Math.min(Math.max(number.intValue(), 1), MAX_LIMIT);
		}
		return DEFAULT_LIMIT;
	}
}
