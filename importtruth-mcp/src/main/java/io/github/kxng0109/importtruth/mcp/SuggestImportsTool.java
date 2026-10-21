package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.SuggestService;
import io.github.kxng0109.importtruth.model.Packages;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Suggests dependency names for many wanted imports in one call.
 * Each item keeps one block so batches stay readable; bad items
 * become inline error lines while good items still answer.
 */
public final class SuggestImportsTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "suggest_imports";

	/** Maximum names per call. Keeps answers small enough to read. */
	static final int MAX_NAMES = 50;

	/** Default and maximum suggestion counts. */
	static final int DEFAULT_LIMIT = 5;
	static final int MAX_LIMIT = 25;

	private final SuggestService suggest;

	/**
	 * Creates the tool.
	 *
	 * @param suggest backing service, never null
	 * @throws NullPointerException if {@code suggest} is {@code null}
	 */
	public SuggestImportsTool(SuggestService suggest) {
		this.suggest = Objects.requireNonNull(suggest, "suggest");
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return suggest tool definition
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
										"names", Map.of("type", "array", "items", Map.of("type", "string")),
										"limit", Map.of("type", "integer", "default", DEFAULT_LIMIT)),
								"required",
								List.of("projectPath", "names")))
				.description("Suggests dependency names for many wanted imports in one call."
						+ " Resolver failures fail the whole call with isError=true;"
						+ " blank or mistyped items become inline ERROR lines"
						+ " (an all-invalid list returns success carrying only ERROR lines);"
						+ " empty matches render as \"no suggestions for <name>\".")
				.build();
	}

	/**
	 * Handles a batch suggest call. Resolver failures fail the whole
	 * call with {@code isError=true}; blank or mistyped items become
	 * inline {@code ERROR} lines inside a success result. The structured
	 * content holds one answer per requested name, in request order.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one block per name, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<List<?>> raw = McpArgs.strings(arguments, "names");
		if (project.isEmpty()) {
			String message = "projectPath is a required string";
			return McpResults.err(message, McpResults.failure(message));
		}
		if (raw.isEmpty()) {
			String message = "names is a required non-empty array";
			return McpResults.err(message, McpResults.failure(message));
		}
		List<?> items = raw.get();
		if (items.size() > MAX_NAMES) {
			String message = "at most " + MAX_NAMES + " names per call";
			return McpResults.err(message, McpResults.failure(message));
		}
		int limit = limitOf(arguments.get("limit"));
		List<String> valid = new ArrayList<>();
		for (Object item : items) {
			if (McpArgs.usableItem(item)) {
				valid.add((String) item);
			}
		}
		Map<String, List<String>> answers;
		if (valid.isEmpty()) {
			answers = Map.of();
		} else {
			try {
				answers = suggest.suggestBatch(Paths.get(project.get()), valid, limit);
			} catch (Exception failure) {
				String message = "suggest failed: " + failure;
				return McpResults.err(message, McpResults.failure(message));
			}
		}
		List<String> lines = new ArrayList<>();
		List<Map<String, Object>> answersPerName = new ArrayList<>();
		for (Object item : items) {
			if (!McpArgs.usableItem(item)) {
				lines.add("ERROR name must be a non-blank single-line string");
				answersPerName.add(McpResults.failure("name must be a non-blank single-line string"));
				continue;
			}
			String name = (String) item;
			List<String> found = answers.getOrDefault(name, List.of());
			if (found.isEmpty()) {
				lines.add("no suggestions for " + name);
			} else {
				lines.add("suggestions for " + name + ":");
				for (String candidate : found) {
					lines.add("maybe: " + candidate);
				}
			}
			answersPerName.add(answer(name, found));
		}
		return McpResults.ok(lines, results(answersPerName));
	}

	/**
	 * Builds the machine readable answer of one wanted name. The status
	 * says whether anything was suggested. Every suggestion carries the
	 * importable name, the identifier the import binds, and how the
	 * candidate matched the wanted name.
	 *
	 * @param name  wanted fully qualified name, never null
	 * @param found suggested names, best first, never null
	 * @return structured content, never null
	 */
	private static Map<String, Object> answer(String name, List<String> found) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(found, "found");
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("status", found.isEmpty() ? "NOT_FOUND" : "FOUND");
		content.put("name", name);
		List<Map<String, Object>> suggestions = new ArrayList<>(found.size());
		for (String candidate : found) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("name", candidate);
			entry.put("descriptor", Packages.simpleName(candidate));
			entry.put("match", match(name, candidate));
			suggestions.add(entry);
		}
		content.put("suggestions", suggestions);
		return content;
	}

	/**
	 * Classifies how a candidate matched the wanted name, with the
	 * buckets the ranker uses: the exact simple name first, the wanted
	 * package second, everything else after.
	 *
	 * @param wanted    wanted fully qualified name, never null
	 * @param candidate suggested fully qualified name, never null
	 * @return {@code EXACT}, {@code PACKAGE}, or {@code OTHER}
	 */
	private static String match(String wanted, String candidate) {
		String wantSimple = Packages.simpleName(plain(wanted));
		if (Packages.simpleName(candidate).equals(wantSimple)) {
			return "EXACT";
		}
		String wantPackage = Packages.packageName(plain(wanted));
		if (!wantPackage.isEmpty() && candidate.startsWith(wantPackage + ".")) {
			return "PACKAGE";
		}
		return "OTHER";
	}

	/**
	 * Strips type arguments and array suffixes so a wanted name is
	 * compared the way the ranker compares it. The core keeps the same
	 * rule private to its own package.
	 *
	 * @param name wanted name, never null
	 * @return plain name, never null
	 */
	private static String plain(String name) {
		StringBuilder kept = new StringBuilder();
		int depth = 0;
		for (int i = 0; i < name.length(); i++) {
			char current = name.charAt(i);
			if (current == '<') {
				depth++;
			} else if (current == '>') {
				depth = Math.max(0, depth - 1);
			} else if (depth == 0) {
				kept.append(current);
			}
		}
		String plain = kept.toString();
		while (plain.endsWith("[]")) {
			plain = plain.substring(0, plain.length() - 2);
		}
		return plain;
	}

	/**
	 * Wraps the per name answers in the structured content of a batch.
	 * The list keeps the request order, so an answer lines up with the
	 * name that was asked about.
	 *
	 * @param answers one answer per requested name, never null
	 * @return structured content, never null
	 */
	private static Map<String, Object> results(List<Map<String, Object>> answers) {
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("results", answers);
		return content;
	}

	static int limitOf(Object raw) {
		return McpArgs.boundedLimit(raw, DEFAULT_LIMIT, MAX_LIMIT);
	}
}
