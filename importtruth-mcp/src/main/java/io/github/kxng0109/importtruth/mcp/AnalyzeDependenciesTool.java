package io.github.kxng0109.importtruth.mcp;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.UsageService;
import io.github.kxng0109.importtruth.model.DependencyUsage;
import io.github.kxng0109.importtruth.model.UsageStatus;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reports which dependency files the project's own compiled classes
 * reference.
 * <p>
 * The answer holds a verdict per dependency, and the standing caveat
 * whenever a dependency no reference reaches is among them. The caveat
 * is never optional: a scan of class files cannot see reflection, a
 * service loader, a class loaded by a computed name, a method handle
 * lookup, or a resource, so such a dependency is a candidate for
 * removal and never a verdict.
 */
public final class AnalyzeDependenciesTool {

	/** Tool name. Matches the strict tool-name pattern. */
	public static final String TOOL_NAME = "analyze_dependencies";

	/** The most referenced class names one dependency carries in the answer. */
	private static final int MAX_REFERENCED_CLASSES = 25;

	private final UsageService usage;

	/**
	 * Creates the tool.
	 *
	 * @param resolver dependency resolver, never null
	 * @throws NullPointerException when {@code resolver} is {@code null}
	 */
	public AnalyzeDependenciesTool(DependencyResolver resolver) {
		this.usage = new UsageService(Objects.requireNonNull(resolver, "resolver"));
	}

	/**
	 * Builds the tool definition served to the model.
	 *
	 * @return analysis tool definition
	 */
	public McpSchema.Tool definition() {
		return McpSchema.Tool.builder(
						TOOL_NAME,
						Map.of(
								"type", "object",
								"properties",
								Map.of(
										"projectPath", Map.of("type", "string"),
										"classesPath", Map.of("type", "string")),
								"required",
								List.of("projectPath")))
				.description("Reports which dependency jars the compiled classes reference and names "
						+ "the ones no reference reaches; a class-file scan cannot see reflection, "
						+ "ServiceLoader, Class.forName with a computed name, MethodHandles, or "
						+ "resources, so such a dependency is a candidate for removal and never a "
						+ "verdict.")
				.build();
	}

	/**
	 * Handles an analysis call.
	 *
	 * <p>The path of the compiled classes is read against the project
	 * root, so a path written relative to the project finds its
	 * classes. A failure answers with an error, never with a verdict.
	 *
	 * @param arguments tool arguments, never null
	 * @return successful result with one line per dependency, or an error result
	 */
	public McpSchema.CallToolResult call(Map<String, Object> arguments) {
		Objects.requireNonNull(arguments, "arguments");
		Optional<String> project = McpArgs.string(arguments, "projectPath");
		if (project.isEmpty()) {
			String message = "projectPath is a required string";
			return McpResults.err(message, McpResults.failure(message));
		}
		Optional<String> classes = McpArgs.string(arguments, "classesPath");
		if (arguments.containsKey("classesPath") && classes.isEmpty()) {
			// A path that cannot be read would otherwise fall back to
			// the build output, and the answer would be about classes
			// the caller never asked about.
			String message = "classesPath must be a string on one line";
			return McpResults.err(message, McpResults.failure(message));
		}
		UsageService.Report report;
		try {
			report = usage.analyze(Paths.get(project.get()), classes.map(Paths::get).orElse(null));
		} catch (Exception failure) {
			String message = "analyze failed: " + failure;
			return McpResults.err(message, McpResults.failure(message));
		}
		return McpResults.ok(lines(report), structured(report));
	}

	/**
	 * Renders the answer of one analysis, one line per dependency. The
	 * caveat rides along whenever a dependency no reference reaches is
	 * among them, because that dependency is a candidate for removal
	 * and never a verdict.
	 * <p>
	 * The rendering is public, so the command line answers with the
	 * very lines this tool answers with. A second rendering would drift
	 * from this one, and a drifted answer is a wrong answer.
	 *
	 * @param report analysis answer, never null
	 * @return the answer lines, never null
	 */
	public static List<String> lines(UsageService.Report report) {
		Objects.requireNonNull(report, "report");
		List<String> lines = new ArrayList<>();
		for (DependencyUsage usage : report.dependencies()) {
			lines.add(line(usage));
		}
		if (carriesCandidate(report)) {
			lines.add(UsageService.CAVEAT);
		}
		return lines;
	}

	/**
	 * Builds the machine readable answer of one analysis. A key is
	 * present only when its value is known: the scanned roots are
	 * absent when nothing was read, and the caveat is absent when no
	 * dependency is a candidate for removal.
	 *
	 * @param report analysis answer, never null
	 * @return structured content, never null
	 */
	static Map<String, Object> structured(UsageService.Report report) {
		Objects.requireNonNull(report, "report");
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("status", report.scanned() ? "OK" : "UNVERIFIED");
		content.put("project", portable(report.project()));
		if (report.scanned()) {
			content.put("scannedSource", "classes");
			content.put("scannedClassCount", report.scannedClassCount());
			List<String> classpath = new ArrayList<>();
			for (Path root : report.scannedClasspath()) {
				classpath.add(portable(root));
			}
			content.put("scannedClasspath", classpath);
		}
		List<Map<String, Object>> dependencies = new ArrayList<>();
		for (DependencyUsage usage : report.dependencies()) {
			dependencies.add(dependency(usage));
		}
		content.put("dependencies", dependencies);
		if (carriesCandidate(report)) {
			content.put("caveat", UsageService.CAVEAT);
		}
		return content;
	}

	/**
	 * Renders one dependency as one line.
	 *
	 * @param usage usage of one dependency, never null
	 * @return the answer line, never null
	 */
	private static String line(DependencyUsage usage) {
		Objects.requireNonNull(usage, "usage");
		return switch (usage.status()) {
			case USED_DIRECT -> "USED DIRECT " + usage.name() + " " + usage.referencedClasses().size()
					+ (usage.referencedClasses().size() == 1 ? " class referenced" : " classes referenced");
			case USED_INDIRECT -> "USED INDIRECT " + usage.name() + " via " + String.join(", ", usage.via());
			case UNREFERENCED -> "UNREFERENCED " + usage.name() + " 0 of " + usage.totalClassCount()
					+ " classes referenced";
			case UNVERIFIED -> "UNVERIFIED " + usage.name() + " " + usage.reason();
		};
	}

	/**
	 * Builds the machine readable entry of one dependency. The keys say
	 * what is known: the classes it carries when a reference reached
	 * them, the dependency that carries the reference when the use is
	 * indirect, the class count when the archive was read, and the
	 * reason when the dependency could not be checked.
	 * <p>
	 * The list of referenced class names is capped, because a tool
	 * that is read by a model must not answer with thousands of
	 * names. The names are sorted, so the same dependency always
	 * renders the same way. The count stays the true count, and the
	 * answer says how many names the cap left out.
	 *
	 * @param usage usage of one dependency, never null
	 * @return structured entry, never null
	 */
	private static Map<String, Object> dependency(DependencyUsage usage) {
		Objects.requireNonNull(usage, "usage");
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("name", usage.name());
		entry.put("path", portable(usage.path()));
		entry.put("status", usage.status().name());
		List<String> referenced = usage.referencedClasses();
		entry.put("referencedClassCount", referenced.size());
		if (!referenced.isEmpty()) {
			List<String> shown = referenced.stream().sorted().limit(MAX_REFERENCED_CLASSES).toList();
			entry.put("referencedClasses", new ArrayList<>(shown));
			if (shown.size() < referenced.size()) {
				entry.put("referencedClassesTruncated", true);
				entry.put("referencedClassesOmitted", referenced.size() - shown.size());
			}
		}
		if (usage.status() != UsageStatus.UNVERIFIED || usage.totalClassCount() > 0) {
			entry.put("totalClassCount", usage.totalClassCount());
		}
		if (usage.status() == UsageStatus.USED_INDIRECT) {
			entry.put("via", new ArrayList<>(usage.via()));
		}
		if (usage.status() == UsageStatus.UNVERIFIED) {
			entry.put("reason", usage.reason());
		}
		return entry;
	}

	/**
	 * Tests whether the answer holds a dependency no reference
	 * reaches. Such a dependency is a candidate for removal, and the
	 * answer must say so.
	 *
	 * @param report analysis answer, never null
	 * @return true when a dependency is a candidate for removal
	 */
	private static boolean carriesCandidate(UsageService.Report report) {
		return report.dependencies().stream()
				.anyMatch(usage -> usage.status() == UsageStatus.UNREFERENCED);
	}

	/**
	 * Renders a path the same way on every machine, so the answer can
	 * be read and compared anywhere.
	 *
	 * @param path path to render, never null
	 * @return the path, never null
	 */
	private static String portable(Path path) {
		Objects.requireNonNull(path, "path");
		return path.toString().replace('\\', '/');
	}
}
