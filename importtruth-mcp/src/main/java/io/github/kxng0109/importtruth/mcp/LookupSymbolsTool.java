package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.LookupService;
import io.github.kxng0109.importtruth.model.LookupResult;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Paths;
import java.util.ArrayList;
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
	 * single-symbol tool exactly.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one block per symbol, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<List<?>> raw = McpArgs.strings(arguments, "symbols");
		if (project.isEmpty()) {
			return McpResults.err("projectPath is a required string");
		}
		if (raw.isEmpty()) {
			return McpResults.err("symbols is a required non-empty array");
		}
		List<?> symbols = raw.get();
		if (symbols.size() > MAX_SYMBOLS) {
			return McpResults.err("at most " + MAX_SYMBOLS + " symbols per call");
		}
		String projectPath = project.get();
		List<String> valid = new ArrayList<>();
		for (Object item : symbols) {
			if (item instanceof String name && !name.isBlank()) {
				valid.add(name);
			}
		}
		Map<String, LookupResult> answers;
		try {
			answers = service.lookupBatch(Paths.get(projectPath), valid);
		} catch (Exception failure) {
			List<String> errors = new ArrayList<>();
			for (Object item : symbols) {
				if (!(item instanceof String name) || name.isBlank()) {
					errors.add("ERROR symbol must be a non-blank string");
				} else {
					errors.add("ERROR " + name + ": lookup failed: " + failure);
				}
			}
			return McpResults.ok(errors);
		}
		List<String> lines = new ArrayList<>();
		for (Object item : symbols) {
			if (!(item instanceof String name) || name.isBlank()) {
				lines.add("ERROR symbol must be a non-blank string");
				continue;
			}
			lines.addAll(lookup.lines(name, answers.get(name)));
		}
		return McpResults.ok(lines);
	}
}
