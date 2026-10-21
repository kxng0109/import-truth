package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.model.LookupResult;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

	private final LookupService service;
	private final LookupTool lookup;

	/**
	 * Creates the tool.
	 *
	 * @param lookup backing service, never null
	 * @throws NullPointerException if {@code lookup} is {@code null}
	 */
	public LookupSymbolsTool(LookupService lookup) {
		this.service = Objects.requireNonNull(lookup, "lookup");
		this.lookup = new LookupTool(this.service);
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
				.description("Checks many fully qualified names against the project classpath in one call."
						+ " Resolver failures stay per symbol as ERROR lines in a success result;"
						+ " oversize, blank, or invalid batches fail the whole call;"
						+ " success lines match the single-symbol tool exactly.")
				.build();
	}

	/**
	 * Handles a batch lookup call. Resolver failures stay per symbol as
	 * {@code ERROR} lines in a success result, while oversize, blank, or
	 * invalid batches fail the whole call. Success lines match the
	 * single-symbol tool exactly, and the structured content holds one
	 * answer per requested symbol, in request order.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one block per symbol, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<List<?>> raw = McpArgs.strings(arguments, "symbols");
		if (project.isEmpty()) {
			String message = "projectPath is a required string";
			return McpResults.err(message, McpResults.failure(message));
		}
		if (raw.isEmpty()) {
			String message = "symbols is a required non-empty array";
			return McpResults.err(message, McpResults.failure(message));
		}
		List<?> symbols = raw.get();
		if (symbols.size() > MAX_SYMBOLS) {
			String message = "at most " + MAX_SYMBOLS + " symbols per call";
			return McpResults.err(message, McpResults.failure(message));
		}
		String projectPath = project.get();
		List<String> valid = new ArrayList<>();
		for (Object item : symbols) {
			if (McpArgs.usableItem(item)) {
				valid.add((String) item);
			}
		}
		Map<String, LookupResult> answers;
		try {
			answers = service.lookupBatch(Paths.get(projectPath), valid);
		} catch (Exception failure) {
			List<String> errors = new ArrayList<>();
			List<Map<String, Object>> failed = new ArrayList<>();
			for (Object item : symbols) {
				if (!McpArgs.usableItem(item)) {
					errors.add("ERROR symbol must be a non-blank single-line string");
					failed.add(McpResults.failure("symbol must be a non-blank single-line string"));
				} else {
					errors.add("ERROR " + item + ": lookup failed: " + failure);
					failed.add(McpResults.failure((String) item, "lookup failed: " + failure));
				}
			}
			return McpResults.ok(errors, results(failed));
		}
		List<String> lines = new ArrayList<>();
		List<Map<String, Object>> answersPerSymbol = new ArrayList<>();
		for (Object item : symbols) {
			if (!McpArgs.usableItem(item)) {
				lines.add("ERROR symbol must be a non-blank single-line string");
				answersPerSymbol.add(McpResults.failure("symbol must be a non-blank single-line string"));
				continue;
			}
			String name = (String) item;
			lines.addAll(lookup.lines(name, answers.get(name)));
			answersPerSymbol.add(lookup.structured(name, answers.get(name)));
		}
		return McpResults.ok(lines, results(answersPerSymbol));
	}

	/**
	 * Wraps the per symbol answers in the structured content of a
	 * batch. The list keeps the request order, so an answer lines up
	 * with the symbol that was asked about.
	 *
	 * @param answers one answer per requested symbol, never null
	 * @return structured content, never null
	 */
	private static Map<String, Object> results(List<Map<String, Object>> answers) {
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("results", answers);
		return content;
	}
}
