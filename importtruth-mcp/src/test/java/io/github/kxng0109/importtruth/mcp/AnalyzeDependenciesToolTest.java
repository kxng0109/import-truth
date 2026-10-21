package io.github.kxng0109.importtruth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.adapter.java.JarClassInventory;
import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.UsageService;
import io.github.kxng0109.importtruth.model.DependencyUsage;
import io.github.kxng0109.importtruth.model.UsageStatus;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Verifies the dependency analysis tool against real bytecode: the
 * three verdicts, the caveat, and the paths where nothing may be
 * called a candidate for removal.
 */
@DisplayName("AnalyzeDependenciesTool")
final class AnalyzeDependenciesToolTest {

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

	/** A second class of the first dependency. */
	private static final String OTHER = "package dep; public class Other { }";

	/** Own code that names two classes of one dependency. */
	private static final String TWO = "package app;"
			+ " public class Two { private dep.Widget widget; private dep.Other other;"
			+ " public dep.Widget widget() { return widget; }"
			+ " public dep.Other other() { return other; } }";

	/** The caveat every answer with a candidate carries. */
	private static final String CAVEAT = "caveat: a class-file scan cannot see reflection, ServiceLoader, "
			+ "Class.forName with a computed name, MethodHandles, or resources; "
			+ "UNREFERENCED is a candidate, not a verdict";

	/** The number of classes of the dependency that carries many references. */
	private static final int MANY = 30;

	/** The number of names the answer carries for one dependency. */
	private static final int CAP = 25;

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
		sources.put("app.Two", TWO);
		sources.put("dep.Widget", WIDGET);
		sources.put("dep.Marker", MARKER);
		sources.put("dep.Orphan", ORPHAN);
		sources.put("dep.Other", OTHER);
		for (int index = 0; index < MANY; index++) {
			sources.put("dep.C" + index, "package dep; public class C" + index + " { }");
		}
		sources.put("app.Many", manyApp());
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
	@DisplayName("reports the direct, the indirect, and the unreferenced dependency line for line")
	void reportsTheThreeVerdictsLineForLine() throws Exception {
		AnalyzeDependenciesTool tool = tool(alpha, beta, gamma);

		CallToolResult result = tool.call(Map.of("projectPath", project.toString()));

		assertThat(result.isError()).as("error flag").isFalse();
		assertThat(textOf(result)).as("the rendered answer")
				.isEqualTo("USED DIRECT alpha-1.0.jar 1 class referenced"
						+ "\n"
						+ "USED INDIRECT beta-1.0.jar via alpha-1.0.jar"
						+ "\n"
						+ "UNREFERENCED gamma-1.0.jar 0 of 1 classes referenced"
						+ "\n"
						+ CAVEAT);
	}

	@Test
	@DisplayName("carries the machine readable answer")
	void carriesTheMachineReadableAnswer() throws Exception {
		AnalyzeDependenciesTool tool = tool(alpha, beta, gamma);

		Map<String, Object> structured = structuredOf(tool.call(Map.of("projectPath", project.toString())));

		assertThat(structured).as("status").containsEntry("status", "OK");
		assertThat(structured).as("project").containsEntry("project", forward(project));
		assertThat(structured).as("scanned source").containsEntry("scannedSource", "classes");
		assertThat(structured).as("scanned class count").containsEntry("scannedClassCount", 1);
		assertThat(classpathOf(structured)).as("scanned classpath")
				.containsExactly(forward(project.resolve("target/classes")));
		assertThat(structured).as("caveat").containsEntry("caveat", CAVEAT);
		List<?> dependencies = (List<?>) structured.get("dependencies");
		assertThat(dependencies).as("one entry per dependency").hasSize(3);
		Map<String, Object> direct = entryOf(dependencies, 0);
		assertThat(direct).as("the direct dependency")
				.containsEntry("name", "alpha-1.0.jar")
				.containsEntry("status", "USED_DIRECT")
				.containsEntry("referencedClassCount", 1)
				.containsEntry("totalClassCount", 1)
				.containsEntry("referencedClasses", List.of("dep.Widget"))
				.doesNotContainKey("via")
				.doesNotContainKey("reason")
				.doesNotContainKey("referencedClassesTruncated")
				.doesNotContainKey("referencedClassesOmitted");
		Map<String, Object> indirect = entryOf(dependencies, 1);
		assertThat(indirect).as("the indirect dependency")
				.containsEntry("name", "beta-1.0.jar")
				.containsEntry("status", "USED_INDIRECT")
				.containsEntry("referencedClassCount", 1)
				.containsEntry("totalClassCount", 1)
				.containsEntry("via", List.of("alpha-1.0.jar"));
		Map<String, Object> unreferenced = entryOf(dependencies, 2);
		assertThat(unreferenced).as("the unreferenced dependency")
				.containsEntry("name", "gamma-1.0.jar")
				.containsEntry("status", "UNREFERENCED")
				.containsEntry("referencedClassCount", 0)
				.containsEntry("totalClassCount", 1)
				.doesNotContainKey("referencedClasses")
				.doesNotContainKey("via")
				.doesNotContainKey("reason");
	}

	@Test
	@DisplayName("never calls a dependency unused")
	void neverCallsADependencyUnused() throws Exception {
		AnalyzeDependenciesTool tool = tool(alpha, beta, gamma);

		CallToolResult result = tool.call(Map.of("projectPath", project.toString()));
		String text = textOf(result);
		Map<String, Object> structured = structuredOf(result);

		assertThat(text.toLowerCase(Locale.ROOT)).as("the rendered text").doesNotContain("unused");
		assertThat(structured.toString().toLowerCase(Locale.ROOT)).as("the machine readable answer")
				.doesNotContain("unused");
	}

	@Test
	@DisplayName("never names a dependency a candidate when the project has no compiled classes")
	void neverNamesACandidateWithoutCompiledClasses() throws Exception {
		Path empty = work.resolve("empty");
		Files.createDirectories(empty);
		Files.write(empty.resolve("pom.xml"), POM.getBytes(StandardCharsets.UTF_8));
		AnalyzeDependenciesTool tool = tool(alpha, beta, gamma);

		CallToolResult result = tool.call(Map.of("projectPath", empty.toString()));
		String text = textOf(result);
		Map<String, Object> structured = structuredOf(result);

		assertThat(result.isError()).as("error flag").isFalse();
		assertThat(text).as("every dependency is unverified").contains("UNVERIFIED alpha-1.0.jar");
		assertThat(text).as("no dependency is a candidate").doesNotContain("UNREFERENCED");
		assertThat(text).as("the caveat belongs to a candidate").doesNotContain("caveat:");
		assertThat(structured).as("status").containsEntry("status", "UNVERIFIED");
		assertThat(structured).as("no scanned source").doesNotContainKey("scannedSource");
		assertThat(structured).as("no scanned class count").doesNotContainKey("scannedClassCount");
		assertThat(structured).as("no scanned classpath").doesNotContainKey("scannedClasspath");
	}

	@Test
	@DisplayName("answers unverified for a dependency that cannot be read")
	void answersUnverifiedForAnUnreadableJar() throws Exception {
		Path broken = work.resolve("broken-1.0.jar");
		Files.write(broken, "not an archive".getBytes(StandardCharsets.UTF_8));
		AnalyzeDependenciesTool tool = tool(alpha, broken, gamma);

		CallToolResult result = tool.call(Map.of("projectPath", project.toString()));
		String text = textOf(result);

		assertThat(text).as("the broken dependency").contains("UNVERIFIED broken-1.0.jar the JAR could not be read");
		assertThat(text).as("the other dependencies are unaffected").contains("USED DIRECT alpha-1.0.jar");
		assertThat(text).as("the other dependencies are unaffected").contains("UNREFERENCED gamma-1.0.jar");
	}

	@Test
	@DisplayName("scans the classes the caller names")
	void scansTheClassesTheCallerNames() throws Exception {
		AnalyzeDependenciesTool tool = tool(alpha, beta, gamma);
		Path classes = work.resolve("classes");
		Files.createDirectories(classes);
		Files.copy(fixtures.resolve("app/App.class"), classes.resolve("App.class"));

		CallToolResult result = tool.call(Map.of(
				"projectPath", project.toString(),
				"classesPath", classes.toString()));

		assertThat(textOf(result)).as("the named classes decide")
				.contains("USED DIRECT alpha-1.0.jar")
				.contains("USED INDIRECT beta-1.0.jar via alpha-1.0.jar");
	}

	@Test
	@DisplayName("reports how many classes of a dependency the code reaches")
	void reportsHowManyClassesTheCodeReaches() throws Exception {
		Path pair = jar("pair-1.0.jar", "dep/Widget.class", "dep/Other.class");
		Path own = projectWith("Two");
		AnalyzeDependenciesTool tool = tool(pair);

		CallToolResult result = tool.call(Map.of("projectPath", own.toString()));

		assertThat(textOf(result)).as("the number of classes")
				.contains("USED DIRECT pair-1.0.jar 2 classes referenced");
	}

	@Test
	@DisplayName("never names a dependency a candidate when the closure cannot be completed")
	void neverNamesACandidateWhenTheClosureCannotBeCompleted() throws Exception {
		Path mixed = work.resolve("mixed-1.0.jar");
		try (OutputStream out = Files.newOutputStream(mixed);
				JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new JarEntry("dep/Widget.class"));
			Files.copy(fixtures.resolve("dep/Widget.class"), zip);
			zip.closeEntry();
			zip.putNextEntry(new JarEntry("dep/Broken.class"));
			zip.write("not a class file".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		AnalyzeDependenciesTool tool = tool(mixed, beta);

		CallToolResult result = tool.call(Map.of("projectPath", project.toString()));
		String text = textOf(result);
		Map<String, Object> structured = structuredOf(result);

		assertThat(text).as("the dependency the closure could not reach")
				.contains("UNVERIFIED beta-1.0.jar");
		assertThat(text).as("no dependency is a candidate").doesNotContain("UNREFERENCED");
		Map<String, Object> entry = entryOf((List<?>) structured.get("dependencies"), 1);
		assertThat(entry).as("the class count of a dependency that was read")
				.containsEntry("totalClassCount", 1);
	}

	@Test
	@DisplayName("rejects a call without a usable project path")
	void rejectsACallWithoutAProjectPath() {
		AnalyzeDependenciesTool tool = tool(alpha, beta, gamma);

		assertThat(tool.call(Map.of()).isError()).as("missing project rejected").isTrue();
		assertThat(tool.call(Map.of("projectPath", " ")).isError()).as("blank project rejected").isTrue();
		assertThat(tool.call(Map.of("projectPath", 42)).isError()).as("mistyped project rejected").isTrue();
		assertThat(tool.call(Map.of("projectPath", "a\nb")).isError()).as("forged line rejected").isTrue();
		assertThat(tool.call(Map.of("projectPath", project.toString(), "classesPath", "a\nb")).isError())
				.as("forged classes path rejected")
				.isTrue();
	}

	@Test
	@DisplayName("marks a failed analysis as an error and never as a verdict")
	void marksAFailedAnalysisAsAnError() {
		AnalyzeDependenciesTool tool = new AnalyzeDependenciesTool((projectDir, allowNetwork) -> {
			throw new IOException("the classpath could not be resolved");
		});

		CallToolResult result = tool.call(Map.of("projectPath", project.toString()));
		Map<String, Object> structured = structuredOf(result);

		assertThat(result.isError()).as("error flag").isTrue();
		assertThat(structured).as("status").containsEntry("status", "ERROR");
		assertThat(structured).as("no verdict").doesNotContainKey("dependencies");
	}

	@Test
	@DisplayName("reads the classes of an archive the way the adapter reads them")
	void readsTheClassesOfAnArchiveTheWayTheAdapterReadsThem() throws Exception {
		Path multi = work.resolve("multi-1.0.jar");
		try (OutputStream out = Files.newOutputStream(multi);
				JarOutputStream zip = new JarOutputStream(out)) {
			for (String member : List.of("dep/Widget.class", "dep/Marker.class")) {
				zip.putNextEntry(new JarEntry(member));
				Files.copy(fixtures.resolve(member), zip);
				zip.closeEntry();
			}
			zip.putNextEntry(new JarEntry("META-INF/versions/9/dep/Widget.class"));
			Files.copy(fixtures.resolve("dep/Widget.class"), zip);
			zip.closeEntry();
			zip.putNextEntry(new JarEntry("META-INF/versions/17/dep/Widget.class"));
			Files.copy(fixtures.resolve("dep/Widget.class"), zip);
			zip.closeEntry();
			zip.putNextEntry(new JarEntry("META-INF/versions/8/dep/Marker.class"));
			Files.copy(fixtures.resolve("dep/Marker.class"), zip);
			zip.closeEntry();
			zip.putNextEntry(new JarEntry("META-INF/versions/99/dep/Marker.class"));
			Files.copy(fixtures.resolve("dep/Marker.class"), zip);
			zip.closeEntry();
			zip.putNextEntry(new JarEntry("META-INF/services/dep.Service"));
			zip.write("dep.Marker".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		Set<String> mine = UsageService.classesIn(multi);
		Set<String> theirs = JarClassInventory.classesIn(multi);

		assertThat(mine).as("the core rule").containsExactlyInAnyOrderElementsOf(theirs);
		assertThat(mine).as("one name per class").containsExactlyInAnyOrder("dep.Widget", "dep.Marker");
	}

	@Test
	@DisplayName("caps the referenced class names and says how many were left out")
	void capsTheReferencedClassNames() throws Exception {
		Path many = jar("many-1.0.jar", manyMembers());
		Path own = projectWith("Many");
		AnalyzeDependenciesTool tool = tool(many);

		CallToolResult result = tool.call(Map.of("projectPath", own.toString()));

		Map<String, Object> entry = entryOf((List<?>) structuredOf(result).get("dependencies"), 0);
		assertThat(entry).as("the dependency")
				.containsEntry("referencedClassCount", MANY)
				.containsEntry("referencedClassesTruncated", true)
				.containsEntry("referencedClassesOmitted", MANY - CAP);
		assertThat((List<?>) entry.get("referencedClasses")).as("the names").hasSize(CAP);
		assertThat(textOf(result)).as("the rendered answer")
				.contains("USED DIRECT many-1.0.jar " + MANY + " classes referenced");
	}

	@Test
	@DisplayName("keeps every name of a dependency that names no more than the cap")
	void keepsEveryNameUpToTheCap() {
		Map<String, Object> entry = entryOf((List<?>) AnalyzeDependenciesTool.structured(report(CAP)).get("dependencies"), 0);

		assertThat(entry).as("the dependency")
				.containsEntry("referencedClassCount", CAP)
				.doesNotContainKey("referencedClassesTruncated")
				.doesNotContainKey("referencedClassesOmitted");
		assertThat((List<?>) entry.get("referencedClasses")).as("the names").hasSize(CAP);
	}

	@Test
	@DisplayName("caps the names of a dependency that names one class more than the cap")
	void capsOneNameOverTheCap() {
		Map<String, Object> entry = entryOf((List<?>) AnalyzeDependenciesTool.structured(report(CAP + 1)).get("dependencies"), 0);

		assertThat(entry).as("the dependency")
				.containsEntry("referencedClassCount", CAP + 1)
				.containsEntry("referencedClassesTruncated", true)
				.containsEntry("referencedClassesOmitted", 1);
		assertThat((List<?>) entry.get("referencedClasses")).as("the names").hasSize(CAP);
	}

	private AnalyzeDependenciesTool tool(Path... jars) {
		List<Path> resolved = List.of(jars);
		DependencyResolver resolver = (projectDir, allowNetwork) -> resolved;
		return new AnalyzeDependenciesTool(resolver);
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

	private Path projectWith(String simpleName) throws IOException {
		Path dir = work.resolve("project-" + simpleName);
		Path classes = dir.resolve("target/classes/app");
		Files.createDirectories(classes);
		Files.write(dir.resolve("pom.xml"), POM.getBytes(StandardCharsets.UTF_8));
		Files.copy(fixtures.resolve("app/" + simpleName + ".class"), classes.resolve(simpleName + ".class"));
		return dir;
	}

	/**
	 * Builds the source of a class that names every class of the many.
	 *
	 * @return the source, never null
	 */
	private static String manyApp() {
		StringBuilder source = new StringBuilder("package app; public class Many {"
				+ " public Object[] all() { return new Object[] { ");
		for (int index = 0; index < MANY; index++) {
			source.append("new dep.C").append(index).append("(), ");
		}
		source.append("}; } }");
		return source.toString();
	}

	/**
	 * Lists the members of the archive that holds many classes.
	 *
	 * @return the member names, never null
	 */
	private static String[] manyMembers() {
		String[] names = new String[MANY];
		for (int index = 0; index < MANY; index++) {
			names[index] = "dep/C" + index + ".class";
		}
		return names;
	}

	/**
	 * Builds the answer of an analysis whose one dependency names the
	 * given number of classes.
	 *
	 * @param referencedCount number of referenced classes
	 * @return the answer, never null
	 */
	private static UsageService.Report report(int referencedCount) {
		List<String> names = new ArrayList<>();
		for (int index = 0; index < referencedCount; index++) {
			names.add("dep.C" + index);
		}
		DependencyUsage usage = new DependencyUsage("many-1.0.jar", Paths.get("many-1.0.jar"),
				UsageStatus.USED_DIRECT, names, List.of(), referencedCount, "");
		return new UsageService.Report(Paths.get("project"), true, 1, List.of(Paths.get("classes")),
				List.of(usage), "");
	}

	private static String forward(Path path) {
		return path.toAbsolutePath().normalize().toString().replace('\\', '/');
	}

	private static String textOf(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> structuredOf(CallToolResult result) {
		assertThat(result.structuredContent()).as("structured content present").isInstanceOf(Map.class);
		return (Map<String, Object>) result.structuredContent();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> entryOf(List<?> dependencies, int index) {
		return (Map<String, Object>) dependencies.get(index);
	}

	@SuppressWarnings("unchecked")
	private static List<String> classpathOf(Map<String, Object> structured) {
		return (List<String>) structured.get("scannedClasspath");
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
