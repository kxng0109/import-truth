package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.CheckOrchestrator;
import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.DisplayNames;
import io.github.kxng0109.importtruth.core.FileCheckService;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.JdkTarget;
import io.github.kxng0109.importtruth.core.ProjectPackages;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.Findings;
import io.github.kxng0109.importtruth.model.ImportVerdict;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.PolicyHit;
import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;
import io.github.kxng0109.importtruth.policy.PackLoader;
import io.github.kxng0109.importtruth.policy.PolicyCache;
import io.github.kxng0109.importtruth.policy.PolicyEngine;
import io.github.kxng0109.importtruth.policy.PolicyValidator;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checks one file's imports with existence verdicts and policy hits.
 */
public final class CheckFileTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "check_file";

	private final JarIndexStore store;
	private final LibraryIndexer indexer;
	private final DependencyResolver resolver;
	private final JdkIndex jdk;
	private final PolicyCache engines = new PolicyCache();

	/**
	 * Creates the tool.
	 *
	 * @param store    index store, never null
	 * @param indexer  library extractor, never null
	 * @param resolver dependency resolver, never null
	 * @param jdk      JDK index, never null
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public CheckFileTool(JarIndexStore store, LibraryIndexer indexer, DependencyResolver resolver, JdkIndex jdk) {
		this.store = Objects.requireNonNull(store, "store");
		this.indexer = Objects.requireNonNull(indexer, "indexer");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.jdk = Objects.requireNonNull(jdk, "jdk");
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return check tool definition
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
										"filePath", Map.of("type", "string")),
								"required",
								List.of("projectPath", "filePath")))
				.description("Checks one Java file's imports against the resolved classpath.")
				.build();
	}

	/**
	 * Handles a check call.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one line per finding, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<String> file = McpArgs.string(arguments, "filePath");
		if (project.isEmpty() || file.isEmpty()) {
			return McpResults.err("projectPath and filePath are required strings");
		}
		String projectPath = project.get();
		String filePath = file.get();
		try {
			List<String> lines = check(Paths.get(projectPath), Paths.get(filePath));
			if (lines.isEmpty()) {
				lines.add("clean");
			}
			return McpResults.ok(lines);
		} catch (Exception failure) {
			return McpResults.err("check failed: " + failure);
		}
	}

	private List<String> check(Path projectDir, Path file) throws IOException {
		return checkFiles(projectDir, List.of(file)).get(file);
	}

	/**
	 * Checks many files sharing one resolution, one index pass,
	 * and one policy load. Unhealthy files yield no lines.
	 *
	 * @param projectDir project root, never null
	 * @param files      source files, never null
	 * @return findings per file, in order, never null
	 * @throws IOException when resolution, indexing, or pack loading fails
	 */
	Map<Path, List<String>> checkFiles(Path projectDir, List<Path> files) throws IOException {
		FileCheckService check = new FileCheckService(store, indexer, resolver, jdk);
		List<Path> jars = resolver.resolve(projectDir, false);
		List<Path> dbs = CheckOrchestrator.indexAll(store, indexer, jars);
		Set<String> own = ProjectPackages.of(projectDir);
		PolicyEngine policy = loadPack(projectDir, jars, dbs, own);
		Map<Path, List<String>> answers = new LinkedHashMap<>();
		for (Path file : files) {
			answers.put(file, checkOne(check, policy, projectDir, file, dbs, own));
		}
		return answers;
	}

	private List<String> checkOne(
			FileCheckService check,
			PolicyEngine policy,
			Path projectDir,
			Path file,
			List<Path> dbs,
			Set<String> own)
			throws IOException {
		CheckResult result = check.checkWith(projectDir, file, dbs, own);
		if (!result.healthy()) {
			return List.of("ERROR " + DisplayNames.relativizeOrFileName(projectDir, file)
					+ ": file could not be parsed");
		}
		List<Finding> findings = CheckOrchestrator.applyPolicy(
				result,
				target -> policy.evaluate(target, true),
				DisplayNames.relativizeOrFileName(projectDir, file));
		List<String> lines = new ArrayList<>(findings.size());
		for (Finding finding : findings) {
			lines.add(Findings.format(finding));
		}
		return lines;
	}

	private PolicyEngine loadPack(Path projectDir, List<Path> jars, List<Path> dbs, Set<String> own)
			throws IOException {
		PolicyPack pack = PackLoader.loadProjectPack(CheckFileTool.class, projectDir);
		int target = JdkTarget.of(projectDir).orElse(-1);
		return engines.engine(pack, CheckOrchestrator.scopeKey(jars, dbs, own, target), validated ->
				PolicyValidator.activeRules(
						validated,
						name -> CheckOrchestrator.packageResolves(store, jdk, dbs, own, name, target),
						name -> CheckOrchestrator.typeResolves(store, jdk, dbs, name, target)));
	}

	private static McpSchema.CallToolResult error(String message) {
		McpSchema.TextContent text = McpSchema.TextContent.builder(message).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), true, null, null);
	}
}
