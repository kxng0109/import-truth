package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks many files in one call. Each file keeps the exact lines the
 * single-file tool would print, prefixed only where the single answer
 * would be ambiguous in a batch.
 */
public final class CheckFilesTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "check_files";

	/** Maximum files per call. Keeps answers small enough to read. */
	static final int MAX_FILES = 50;

	private final CheckFileTool check;

	/**
	 * Creates the tool.
	 *
	 * @param store    index store, never null
	 * @param indexer  library extractor, never null
	 * @param resolver dependency resolver, never null
	 * @param jdk      JDK index, never null
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public CheckFilesTool(
			JarIndexStore store, LibraryIndexer indexer, DependencyResolver resolver, JdkIndex jdk) {
		this.check = new CheckFileTool(
				Objects.requireNonNull(store, "store"),
				Objects.requireNonNull(indexer, "indexer"),
				Objects.requireNonNull(resolver, "resolver"),
				Objects.requireNonNull(jdk, "jdk"));
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return batch check tool definition
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
										"filePaths", Map.of("type", "array", "items", Map.of("type", "string"))),
								"required",
								List.of("projectPath", "filePaths")))
				.description("Checks many Java files' imports against the resolved classpath in one call."
						+ " One resolver, index, or pack failure fails the whole call with isError=true;"
						+ " invalid items become inline ERROR lines"
						+ " (an all-invalid list returns success carrying only ERROR lines);"
						+ " clean files render as \"clean: <path>\".")
				.build();
	}

	/**
	 * Handles a batch check call. One resolver, index, or pack failure fails
	 * the whole call with {@code isError=true}; invalid items become inline
	 * {@code ERROR} lines, so an all-invalid list returns success carrying
	 * only {@code ERROR} lines. Clean files render as {@code "clean: <path>"}.
	 * The structured content holds one answer per requested file, in request
	 * order, each with the findings of that file.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one block per file, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<List<?>> raw = McpArgs.strings(arguments, "filePaths");
		if (project.isEmpty()) {
			String message = "projectPath is a required string";
			return McpResults.err(message, McpResults.failure(message));
		}
		if (raw.isEmpty()) {
			String message = "filePaths is a required non-empty array";
			return McpResults.err(message, McpResults.failure(message));
		}
		List<?> files = raw.get();
		if (files.size() > MAX_FILES) {
			String message = "at most " + MAX_FILES + " files per call";
			return McpResults.err(message, McpResults.failure(message));
		}
		String projectPath = project.get();
		List<Path> valid = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		List<Request> requests = new ArrayList<>();
		for (Object item : files) {
			if (!McpArgs.usableItem(item)) {
				lines.add("ERROR file path must be a non-blank single-line string");
				requests.add(new Request(null, "file path must be a non-blank single-line string"));
				continue;
			}
			String filePath = (String) item;
			try {
				Path path = Paths.get(filePath);
				valid.add(path);
				requests.add(new Request(path, null));
			} catch (InvalidPathException invalid) {
				lines.add("ERROR invalid file path: " + filePath);
				requests.add(new Request(null, "invalid file path: " + filePath));
			}
		}
		try {
			Map<Path, CheckFileTool.FileAnswer> answers = valid.isEmpty()
					? Map.of()
					: check.checkFiles(Paths.get(projectPath), valid);
			for (Map.Entry<Path, CheckFileTool.FileAnswer> entry : answers.entrySet()) {
				if (entry.getValue().lines().isEmpty()) {
					lines.add("clean: " + entry.getKey());
				} else {
					lines.addAll(entry.getValue().lines());
				}
			}
			return McpResults.ok(lines, results(requests, answers));
		} catch (Exception failure) {
			String message = "check failed: " + failure;
			return McpResults.err(message, McpResults.failure(message));
		}
	}

	/**
	 * Wraps the per file answers in the structured content of a batch.
	 * The list keeps the request order, so an answer lines up with the
	 * file that was asked about. A file that could not be asked about
	 * answers with an error, never with a verdict.
	 *
	 * @param requests one request per requested file, never null
	 * @param answers  answers of the usable files, never null
	 * @return structured content, never null
	 */
	private static Map<String, Object> results(
			List<Request> requests, Map<Path, CheckFileTool.FileAnswer> answers) {
		List<Map<String, Object>> entries = new ArrayList<>(requests.size());
		for (Request request : requests) {
			if (request.error() != null) {
				entries.add(McpResults.failure(request.error()));
				continue;
			}
			entries.add(CheckFileTool.structured(answers.get(request.path())));
		}
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("results", entries);
		return content;
	}

	/**
	 * One requested file: the path to check, or the reason the file
	 * could not be read as a path.
	 *
	 * @param path  path to check, {@code null} when the item is unusable
	 * @param error reason the item is unusable, {@code null} when it is a path
	 */
	private record Request(Path path, String error) {
	}
}
