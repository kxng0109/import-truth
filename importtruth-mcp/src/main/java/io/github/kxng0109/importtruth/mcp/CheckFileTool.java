package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.FileCheckService;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.ProjectPackages;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.ImportVerdict;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.PolicyHit;
import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;
import io.github.kxng0109.importtruth.policy.PackLoader;
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
		Object project = arguments.get("projectPath");
		Object file = arguments.get("filePath");
		if (!(project instanceof String projectPath) || projectPath.isBlank()
				|| !(file instanceof String filePath) || filePath.isBlank()) {
			return error("projectPath and filePath are required strings");
		}
		try {
			List<String> lines = check(Paths.get(projectPath), Paths.get(filePath));
			if (lines.isEmpty()) {
				lines.add("clean");
			}
			McpSchema.TextContent text = McpSchema.TextContent.builder(String.join("\n", lines)).build();
			return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), false, null, null);
		} catch (Exception failure) {
			return error("check failed: " + failure.getMessage());
		}
	}

	private List<String> check(Path projectDir, Path file) throws IOException {
		FileCheckService check = new FileCheckService(store, indexer, resolver, jdk);
		PolicyEngine policy = loadPack(projectDir);
		CheckResult result = check.check(projectDir, file);
		List<Finding> findings = new ArrayList<>(result.findings());
		if (result.healthy()) {
			for (ImportVerdict verdict : result.verdicts()) {
				if (!verdict.resolved()) {
					continue;
				}
				Optional<PolicyHit> hit = policy.evaluate(verdict.target(), true);
				if (hit.isPresent()) {
					findings.add(new Finding(display(projectDir, file), (int) verdict.line(),
							FindingKind.POLICY, hit.get().detail(), hit.get().suggestion()));
				}
			}
		}
		List<String> lines = new ArrayList<>(findings.size());
		for (Finding finding : findings) {
			StringBuilder line = new StringBuilder(finding.file()).append(':').append(finding.line())
					.append(' ').append(finding.kind().name())
					.append(' ').append(finding.detail());
			if (!finding.suggestion().isEmpty()) {
				line.append(" (").append(finding.suggestion()).append(')');
			}
			lines.add(line.toString());
		}
		return lines;
	}

	private PolicyEngine loadPack(Path projectDir) throws IOException {
		Path override = projectDir.resolve(".importtruth.yml");
		PolicyPack pack;
		if (Files.exists(override)) {
			try (InputStream in = Files.newInputStream(override)) {
				pack = PackLoader.load("project", in);
			}
		} else {
			try (InputStream in = CheckFileTool.class.getResourceAsStream("/packs/jackson3.yaml")) {
				pack = PackLoader.load("jackson3", in);
			}
		}
		List<Path> dbs = new ArrayList<>();
		for (Path jar : resolver.resolve(projectDir, false)) {
			dbs.add(store.ensureIndexed(jar, indexer));
		}
		Set<String> own = ProjectPackages.of(projectDir);
		List<PolicyRule> active = PolicyValidator.activeRules(
				pack,
				name -> packageResolves(dbs, own, name),
				name -> typeResolves(dbs, name));
		return new PolicyEngine(new PolicyPack(pack.name(), active));
	}

	private boolean typeResolves(List<Path> dbs, String name) {
		try {
			return !store.findAcross(dbs, name).isEmpty() || jdk.exists(name);
		} catch (IOException failed) {
			return false;
		}
	}

	private boolean packageResolves(List<Path> dbs, Set<String> own, String name) {
		for (String pkg : own) {
			if (pkg.equals(name) || pkg.startsWith(name + ".")) {
				return true;
			}
		}
		try {
			for (Path db : dbs) {
				if (!store.searchIn(db, name + ".", 1).isEmpty()) {
					return true;
				}
			}
		} catch (IOException failed) {
			return false;
		}
		return jdk.packageExists(name);
	}

	private static String display(Path projectDir, Path file) {
		try {
			return projectDir.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
		} catch (IllegalArgumentException notRelative) {
			return file.getFileName().toString();
		}
	}

	private static McpSchema.CallToolResult error(String message) {
		McpSchema.TextContent text = McpSchema.TextContent.builder(message).build();
		return new McpSchema.CallToolResult(List.<McpSchema.Content>of(text), true, null, null);
	}
}
