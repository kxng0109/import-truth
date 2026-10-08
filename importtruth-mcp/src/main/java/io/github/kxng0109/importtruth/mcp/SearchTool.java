package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.SearchService;
import io.github.kxng0109.importtruth.model.Symbol;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<String> query = McpArgs.string(arguments, "query");
		if (project.isEmpty() || query.isEmpty()) {
			return McpResults.err("projectPath and query are required strings");
		}
		String projectPath = project.get();
		String text = query.get();
		int limit = limitOf(arguments.get("limit"));
		List<String> lines = new ArrayList<>();
		try {
			for (Symbol match : search.search(Paths.get(projectPath), text, limit)) {
				StringBuilder row = new StringBuilder("hit: ").append(match.fqn())
						.append(" (").append(match.kind().name()).append(')');
				if (match.deprecated()) {
					row.append(" deprecated");
				}
				lines.add(row.toString());
			}
		} catch (Exception failure) {
			return McpResults.err("search failed: " + failure);
		}
		if (lines.isEmpty()) {
			lines.add("no matches for " + text);
		}
		return McpResults.ok(lines);
	}

	static int limitOf(Object raw) {
		return McpArgs.boundedLimit(raw, DEFAULT_LIMIT, MAX_LIMIT);
	}
}
