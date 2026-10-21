package io.github.kxng0109.importtruth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.mcp.AnalyzeDependenciesTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that the command answers with the lines the MCP tool answers
 * with, so the two can never drift apart, and that a failed analysis
 * exits 1.
 */
@DisplayName("DepsCommand")
final class DepsCommandTest {

	/** The build file of a one module project. */
	private static final String POM = "<project><modelVersion>4.0.0</modelVersion>"
			+ "<groupId>t</groupId><artifactId>t</artifactId><version>1</version></project>";

	/** The own code: it names one class of one dependency. */
	private static final String APP = "package app;"
			+ " public class App { private dep.Widget widget;"
			+ " public dep.Widget widget() { return widget; } }";

	/** The first dependency: it names a class of the second one. */
	private static final String WIDGET = "package dep;"
			+ " public class Widget { private dep.Marker marker;"
			+ " public dep.Marker marker() { return marker; } }";

	/** The second dependency: nothing names it but the first one. */
	private static final String MARKER = "package dep; public class Marker { }";

	/** The third dependency: nothing names it at all. */
	private static final String ORPHAN = "package dep; public class Orphan { }";

	/** The caveat every answer with a candidate carries. */
	private static final String CAVEAT = "caveat: a class-file scan cannot see reflection, ServiceLoader, "
			+ "Class.forName with a computed name, MethodHandles, or resources; "
			+ "UNREFERENCED is a candidate, not a verdict";

	@TempDir
	private Path work;

	@TempDir
	private static Path shared;

	private static Path fixtures;

	private Path project;
	private Path alpha;
	private Path beta;
	private Path gamma;

	/** Compiles every fixture once; the classes are read only. */
	@BeforeAll
	static void compileFixtures() throws Exception {
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("app.App", APP);
		sources.put("dep.Widget", WIDGET);
		sources.put("dep.Marker", MARKER);
		sources.put("dep.Orphan", ORPHAN);
		fixtures = compileIn(shared, sources);
	}

	@BeforeEach
	void setUp() throws Exception {
		project = work.resolve("project");
		Path classes = project.resolve("target/classes/app");
		Files.createDirectories(classes);
		Files.write(project.resolve("pom.xml"), POM.getBytes(StandardCharsets.UTF_8));
		Files.copy(fixtures.resolve("app/App.class"), classes.resolve("App.class"));
		alpha = jar("alpha-1.0.jar", "dep/Widget.class");
		beta = jar("beta-1.0.jar", "dep/Marker.class");
		gamma = jar("gamma-1.0.jar", "dep/Orphan.class");
	}

	@Test
	@DisplayName("answers with the lines the MCP tool answers with")
	void answersWithTheLinesOfTheTool() throws Exception {
		DependencyResolver resolver = resolverOf(alpha, beta, gamma);
		DepsCommand command = new DepsCommand(resolver);

		List<String> printed = run(command, project, null);
		List<String> expected = linesOf(new AnalyzeDependenciesTool(resolver)
				.call(Map.of("projectPath", project.toString())));

		assertThat(printed).as("the answer of the command")
				.containsExactlyElementsOf(expected);
		assertThat(printed).as("the three verdicts and the caveat")
				.containsExactly(
						"USED DIRECT alpha-1.0.jar 1 class referenced",
						"USED INDIRECT beta-1.0.jar via alpha-1.0.jar",
						"UNREFERENCED gamma-1.0.jar 0 of 1 classes referenced",
						CAVEAT);
	}

	@Test
	@DisplayName("answers with the lines the MCP tool answers with for the named classes")
	void answersWithTheLinesOfTheToolForNamedClasses() throws Exception {
		Path classes = work.resolve("classes");
		Files.createDirectories(classes);
		Files.copy(fixtures.resolve("app/App.class"), classes.resolve("App.class"));
		DependencyResolver resolver = resolverOf(alpha, beta, gamma);
		DepsCommand command = new DepsCommand(resolver);

		List<String> printed = run(command, project, classes);
		List<String> expected = linesOf(new AnalyzeDependenciesTool(resolver).call(Map.of(
				"projectPath", project.toString(),
				"classesPath", classes.toString())));

		assertThat(printed).as("the answer of the command")
				.containsExactlyElementsOf(expected);
		assertThat(printed).as("the named classes decide")
				.contains(
						"USED DIRECT alpha-1.0.jar 1 class referenced",
						"USED INDIRECT beta-1.0.jar via alpha-1.0.jar");
	}

	@Test
	@DisplayName("fails with exit one when the analysis fails")
	void failsWithExitOneWhenTheAnalysisFails() {
		DependencyResolver resolver = (projectDir, allowNetwork) -> {
			throw new IOException("the classpath could not be resolved");
		};
		DepsCommand command = new DepsCommand(resolver);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8),
				new PrintStream(err, true, StandardCharsets.UTF_8), project, null);

		assertThat(exit).as("the exit code").isEqualTo(1);
		assertThat(out.toString(StandardCharsets.UTF_8)).as("no verdict").isEmpty();
		assertThat(err.toString(StandardCharsets.UTF_8)).as("the diagnostic")
				.contains("analyze failed")
				.contains("the classpath could not be resolved");
	}

	@Test
	@DisplayName("answers with nothing when the project has no dependencies")
	void answersWithNothingWhenTheProjectHasNoDependencies() throws Exception {
		DepsCommand command = new DepsCommand((projectDir, allowNetwork) -> List.of());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8),
				new PrintStream(err, true, StandardCharsets.UTF_8), project, null);

		assertThat(exit).as("the exit code").isEqualTo(0);
		assertThat(out.toString(StandardCharsets.UTF_8)).as("no lines").isEmpty();
		assertThat(err.toString(StandardCharsets.UTF_8)).as("no diagnostic").isEmpty();
	}

	@Test
	@DisplayName("rejects null sinks and paths")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNulls() throws Exception {
		DepsCommand command = new DepsCommand(resolverOf(alpha, beta, gamma));

		assertThatThrownBy(() -> command.run(null, System.err, project, null)).as("null out")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> command.run(System.out, null, project, null)).as("null err")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> command.run(System.out, System.err, null, null)).as("null project")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new DepsCommand(null)).as("null resolver")
				.isInstanceOf(NullPointerException.class);
	}

	private static DependencyResolver resolverOf(Path... jars) {
		List<Path> resolved = List.of(jars);
		return (projectDir, allowNetwork) -> resolved;
	}

	private List<String> run(DepsCommand command, Path projectDir, Path classesPath) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8),
				new PrintStream(err, true, StandardCharsets.UTF_8), projectDir, classesPath);

		assertThat(exit).as("the exit code").isEqualTo(0);
		assertThat(err.toString(StandardCharsets.UTF_8)).as("no diagnostic").isEmpty();
		return out.toString(StandardCharsets.UTF_8).lines().toList();
	}

	private static List<String> linesOf(CallToolResult result) {
		assertThat(result.isError()).as("the tool answered").isFalse();
		return ((TextContent) result.content().get(0)).text().lines().toList();
	}

	private Path jar(String name, String... members) throws IOException {
		Path file = work.resolve(name);
		try (OutputStream out = Files.newOutputStream(file);
				JarOutputStream zip = new JarOutputStream(out)) {
			for (String member : members) {
				zip.putNextEntry(new JarEntry(member));
				Files.copy(fixtures.resolve(member), zip);
				zip.closeEntry();
			}
		}
		return file;
	}

	private static Path compileIn(Path root, Map<String, String> sources) throws Exception {
		Path output = Files.createTempDirectory(root, "classes");
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("system compiler present").isNotNull();
		List<String> files = new ArrayList<>();
		for (Map.Entry<String, String> source : sources.entrySet()) {
			Path file = output.resolve(source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(file.getParent());
			Files.write(file, source.getValue().getBytes(StandardCharsets.UTF_8));
			files.add(file.toString());
		}
		List<String> args = new ArrayList<>(List.of("-d", output.toString()));
		args.addAll(files);
		assertThat(compiler.run(null, null, null, args.toArray(new String[0])))
				.as("fixture compilation")
				.isEqualTo(0);
		return output;
	}
}
