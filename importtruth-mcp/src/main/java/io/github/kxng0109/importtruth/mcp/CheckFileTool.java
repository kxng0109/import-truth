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
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.policy.PackLoader;
import io.github.kxng0109.importtruth.policy.PolicyCache;
import io.github.kxng0109.importtruth.policy.PolicyEngine;
import io.github.kxng0109.importtruth.policy.PolicyValidator;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
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
	 * Handles a check call. The rendered lines stay as they were; the
	 * structured content carries the same answer for a machine, with
	 * every finding spelled out. A file that could not be parsed
	 * answers with an error, never with a verdict.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one line per finding, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		Optional<String> file = McpArgs.string(arguments, "filePath");
		if (project.isEmpty() || file.isEmpty()) {
			String message = "projectPath and filePath are required strings";
			return McpResults.err(message, McpResults.failure(message));
		}
		String projectPath = project.get();
		String filePath = file.get();
		try {
			FileAnswer answer = checkFile(Paths.get(projectPath), Paths.get(filePath));
			List<String> lines = answer.lines().isEmpty() ? List.of("clean") : answer.lines();
			return McpResults.ok(lines, structured(answer));
		} catch (Exception failure) {
			String message = "check failed: " + failure;
			return McpResults.err(message, McpResults.failure(message));
		}
	}

	private FileAnswer checkFile(Path projectDir, Path file) throws IOException {
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
	Map<Path, FileAnswer> checkFiles(Path projectDir, List<Path> files) throws IOException {
		FileCheckService check = new FileCheckService(store, indexer, resolver, jdk);
		List<Path> jars = resolver.resolve(projectDir, false);
		List<Path> dbs = CheckOrchestrator.indexAll(store, indexer, jars);
		Set<String> own = ProjectPackages.of(projectDir);
		PolicyEngine policy = loadPack(projectDir, jars, dbs, own);
		Map<Path, FileAnswer> answers = new LinkedHashMap<>();
		// One batch parse for every file: the per file path would build
		// a compiler and a file manager per file.
		Map<Path, CheckResult> results = check.checkAll(projectDir, files, dbs, own);
		for (Path file : files) {
			answers.put(file, checkOne(check, policy, projectDir, file, dbs, own, results.get(file)));
		}
		return answers;
	}

	private FileAnswer checkOne(
			FileCheckService check,
			PolicyEngine policy,
			Path projectDir,
			Path file,
			List<Path> dbs,
			Set<String> own,
			CheckResult result)
			throws IOException {
		if (result == null) {
			return new FileAnswer(file,
					List.of("ERROR " + DisplayNames.relativizeOrFileName(projectDir, file)
							+ ": file could not be parsed"),
					List.of(),
					"file could not be parsed");
		}
		if (!result.healthy()) {
			return new FileAnswer(file,
					List.of("ERROR " + DisplayNames.relativizeOrFileName(projectDir, file)
							+ ": file could not be parsed"),
					List.of(),
					"file could not be parsed");
		}
		List<Finding> findings = CheckOrchestrator.applyPolicy(
				result,
				target -> policy.evaluate(target, true),
				DisplayNames.relativizeOrFileName(projectDir, file));
		List<String> lines = new ArrayList<>(findings.size());
		for (Finding finding : findings) {
			lines.add(Findings.format(finding));
		}
		return new FileAnswer(file, lines, findings, null);
	}

	/**
	 * Builds the machine readable answer of one checked file. The
	 * status says whether the file is clean, carries findings, or could
	 * not be checked at all. A failure is never a verdict.
	 *
	 * @param answer answer of one file, never null
	 * @return structured content, never null
	 */
	static Map<String, Object> structured(FileAnswer answer) {
		Objects.requireNonNull(answer, "answer");
		if (answer.failure() != null) {
			Map<String, Object> content = new LinkedHashMap<>(McpResults.failure(answer.failure()));
			content.put("file", answer.file().toString());
			return content;
		}
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("status", answer.findings().isEmpty() ? "CLEAN" : "FINDINGS");
		content.put("file", answer.file().toString());
		content.put("findingCount", answer.findings().size());
		List<Map<String, Object>> findings = new ArrayList<>(answer.findings().size());
		for (Finding finding : answer.findings()) {
			findings.add(finding(finding));
		}
		content.put("findings", findings);
		return content;
	}

	/**
	 * Builds the machine readable entry of one finding. The kind says
	 * what is wrong: a missing import resolves nowhere, a policy hit
	 * sits on a resolved import, and a candidate may be project-owned
	 * or generated. The resolved key is present only when the answer
	 * is known, so an unverified import never reads as resolved.
	 *
	 * @param finding finding, never null
	 * @return structured entry, never null
	 */
	private static Map<String, Object> finding(Finding finding) {
		Objects.requireNonNull(finding, "finding");
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("kind", finding.kind().name());
		FindingKind kind = finding.kind();
		if (kind == FindingKind.MISSING) {
			entry.put("resolved", false);
		} else if (kind == FindingKind.POLICY) {
			entry.put("resolved", true);
		}
		entry.put("line", finding.line());
		entry.put("text", finding.detail());
		if (!finding.suggestion().isEmpty()) {
			entry.put("suggestion", finding.suggestion());
		}
		return entry;
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

	/**
	 * The answer of one checked file: the rendered lines and the
	 * findings behind them.
	 *
	 * @param file     file that was checked, never null
	 * @param lines    rendered lines, empty when the file is clean, never null
	 * @param findings findings behind the lines, never null
	 * @param failure  why the file could not be checked, {@code null} when it could
	 */
	record FileAnswer(Path file, List<String> lines, List<Finding> findings, String failure) {

		/**
		 * Creates the answer.
		 *
		 * @throws NullPointerException if {@code file}, {@code lines}, or {@code findings} is {@code null}
		 */
		FileAnswer {
			Objects.requireNonNull(file, "file");
			Objects.requireNonNull(lines, "lines");
			Objects.requireNonNull(findings, "findings");
		}
	}
}
