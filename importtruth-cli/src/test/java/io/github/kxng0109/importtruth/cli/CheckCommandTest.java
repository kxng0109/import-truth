package io.github.kxng0109.importtruth.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.MavenResolver;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.nio.file.Paths;
import java.util.List;
import java.util.ServiceLoader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.util.concurrent.TimeUnit;

/**
 * Golden checks against this repository: clean silent, missing exits 1,
 * old Jackson flagged by policy.
 */
@DisplayName("CheckCommand")
final class CheckCommandTest {

	@TempDir
	private Path state;

	@TempDir
	private Path files;

	private final List<JarIndexStore> openStores = new ArrayList<>();

	@AfterEach
	void closeStores() {
		for (JarIndexStore store : openStores) {
			try {
				store.close();
			} catch (Exception ignored) {
				// Best effort: temp cleanup reclaims the rest.
			}
		}
		openStores.clear();
	}

	private JarIndexStore openStore(Path dir) throws IOException {
		JarIndexStore created = new JarIndexStore(dir);
		openStores.add(created);
		return created;
	}

	@Test
	@Tag("slow")
	@DisplayName("silent clean, loud missing, policy rename")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void goldenChecks() throws Exception {
		Path project = Paths.get(System.getProperty("user.dir")).getParent();
		CheckCommand command = command();
		// Fixtures live inside the checked project: checks reject
		// outside files by contract. Scratch dir is gitignored.
		Path scratch = project.resolve(".tmp").resolve("golden-checks");
		Files.createDirectories(scratch);
		try {
			Path clean = scratchFile(scratch, "Clean.java",
					"package com.example; import java.util.List; import tools.jackson.databind.ObjectMapper;"
							+ " public class Clean { List<ObjectMapper> items; }");
			Path missing = scratchFile(scratch, "Missing.java",
					"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");
			Path legacy = scratchFile(scratch, "Legacy.java",
					"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
							+ " public class Legacy { ObjectMapper mapper; }");

			assertThat(run(command, project, clean)).as("clean exit").isEqualTo(0);

			ByteArrayOutputStream missingOut = new ByteArrayOutputStream();
			int missingExit = command.run(new PrintStream(missingOut, true, StandardCharsets.UTF_8),
					System.err, project, missing);
			assertThat(missingExit).as("missing exit").isEqualTo(1);
			assertThat(missingOut.toString(StandardCharsets.UTF_8)).as("missing line")
					.contains("MISSING").contains("org.example.Nope");

			ByteArrayOutputStream legacyOut = new ByteArrayOutputStream();
			int legacyExit = command.run(new PrintStream(legacyOut, true, StandardCharsets.UTF_8),
					System.err, project, legacy);
			assertThat(legacyExit).as("policy exit stays zero").isEqualTo(0);
			assertThat(legacyOut.toString(StandardCharsets.UTF_8)).as("policy line")
					.contains("POLICY").contains("tools.jackson.databind.ObjectMapper");
		} finally {
			deleteTree(scratch);
		}
	}

	private static Path scratchFile(Path dir, String name, String content) throws Exception {
		Path file = dir.resolve(name);
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	private static void deleteTree(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private int run(CheckCommand command, Path project, Path file) throws Exception {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		return command.run(new PrintStream(sink, true, StandardCharsets.UTF_8), System.err, project, file);
	}

	@Test
	@DisplayName("rejects null sinks and paths")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNulls() throws Exception {
		CheckCommand command = fakeCommand();
		Path file = file("Null.java", "package com.other; public class Null { }");

		assertThatThrownBy(() -> command.run(null, System.err, files, file)).as("null out")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> command.run(System.out, System.err, null, file)).as("null project")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> command.run(System.out, null, files, file)).as("null err")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> command.run(System.out, System.err, files, null)).as("null file")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("stays silent on broken files")
	void staysSilentOnBroken() throws Exception {
		CheckCommand command = fakeCommand();
		Path broken = file("Broken.java",
				"package com.other; import java.util.List; public class Broken { void x( { } }");
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8), System.err, files, broken);

		assertThat(exit).as("broken exit").isEqualTo(0);
		assertThat(out.toString(StandardCharsets.UTF_8)).as("broken silent").isEmpty();
	}

	@Test
	@DisplayName("prefers the project override pack")
	void prefersOverridePack() throws Exception {
		Files.write(files.resolve(".importtruth.yml"),
				"allow:\n  - package: com.example.keep\n".getBytes(StandardCharsets.UTF_8));
		CheckCommand command = fakeCommand();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8), System.err, files, legacy);

		assertThat(exit).as("override exit").isEqualTo(0);
		assertThat(out.toString(StandardCharsets.UTF_8)).as("rename rule dropped").doesNotContain("POLICY");
	}

	@Test
	@DisplayName("warns when pack targets go missing")
	void warnsOnDisabledRules() throws Exception {
		CheckCommand command = emptyCommand();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8),
				new PrintStream(err, true, StandardCharsets.UTF_8), files, legacy);

		assertThat(err.toString(StandardCharsets.UTF_8)).as("disabled warning").contains("disabled");
		assertThat(exit).as("missing exit").isEqualTo(1);
	}

	@Test
	@DisplayName("keeps own packages alive")
	void keepsOwnPackagesAlive() throws Exception {
		Path own = files.resolve("src/main/java/com/fasterxml/jackson/databind");
		Files.createDirectories(own);
		Files.write(own.resolve("Foo.java"),
				"package com.fasterxml.jackson.databind; public class Foo { }".getBytes(StandardCharsets.UTF_8));
		CheckCommand command = fakeCommand();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8), System.err, files, legacy);

		assertThat(out.toString(StandardCharsets.UTF_8)).as("own package keeps the rule").contains("POLICY");
		assertThat(exit).as("policy exit stays zero").isEqualTo(0);
	}

	@Test
	@DisplayName("fails loudly on corrupt indexes")
	void failsLoudlyOnCorruptIndexes() throws Exception {
		CheckCommand seed = fakeCommand();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");
		ByteArrayOutputStream seedOut = new ByteArrayOutputStream();
		seed.run(new PrintStream(seedOut, true, StandardCharsets.UTF_8), System.err, files, legacy);
		closeStores();

		try (var indexes = Files.list(state.resolve("fake-index"))) {
			for (Path db : indexes.filter(p -> p.toString().endsWith(".mv.db")).toList()) {
				Files.write(db, "corrupt".getBytes(StandardCharsets.UTF_8));
			}
		}

		CheckCommand command = fakeCommand();
		ByteArrayOutputStream err = new ByteArrayOutputStream();

		assertThatThrownBy(() -> command.run(
				new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
				new PrintStream(err, true, StandardCharsets.UTF_8), files,
				legacy)).as("corrupt index throws").isInstanceOf(IOException.class);
		assertThat(err.toString(StandardCharsets.UTF_8)).as("rules disabled first")
				.contains("disabled");
	}

	@Test
	@DisplayName("validates pack targets against the JDK")
	void validatesJdkTargets() throws Exception {
		Files.write(files.resolve(".importtruth.yml"),
				"renames:\n  - from: com.example.Old\n    to: java.util.List\n".getBytes(StandardCharsets.UTF_8));
		CheckCommand command = fakeCommand();
		Path clean = file("Clean.java",
				"package com.other; import java.util.List; public class Clean { List<String> items; }");
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8), System.err, files, clean);

		assertThat(exit).as("clean exit").isEqualTo(0);
		assertThat(out.toString(StandardCharsets.UTF_8)).as("clean silent").isEmpty();
	}

	@Test
	@DisplayName("matches sub-packages of own code")
	void matchesOwnSubPackages() throws Exception {
		Path own = files.resolve("src/main/java/com/fasterxml/jackson/databind/impl");
		Files.createDirectories(own);
		Files.write(own.resolve("Bar.java"),
				"package com.fasterxml.jackson.databind.impl; public class Bar { }".getBytes(StandardCharsets.UTF_8));
		CheckCommand command = fakeCommand();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		int exit = command.run(new PrintStream(out, true, StandardCharsets.UTF_8), System.err, files, legacy);

		assertThat(exit).as("policy exit stays zero").isEqualTo(0);
		assertThat(out.toString(StandardCharsets.UTF_8)).as("sub-package keeps the rule")
				.contains("POLICY");
	}

	@Test
	@DisplayName("rejects files outside the project")
	void rejectsOutsideProject() throws Exception {
		CheckCommand command = fakeCommand();
		Path legacy = file("Legacy.java",
				"package com.other; import com.fasterxml.jackson.databind.ObjectMapper;"
						+ " public class Legacy { ObjectMapper mapper; }");
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		assertThatThrownBy(() -> command.run(new PrintStream(out, true, StandardCharsets.UTF_8), System.err,
				Paths.get("rel-proj"), legacy.toAbsolutePath()))
				.as("outside file rejected")
				.isInstanceOf(IOException.class)
				.hasMessageContaining("outside the project");
	}

	private CheckCommand fakeCommand() throws Exception {
		Path jar = files.resolve("fake-dep.jar");
		if (!Files.exists(jar)) {
			try (OutputStream out = Files.newOutputStream(jar);
					JarOutputStream zip = new JarOutputStream(out)) {
				zip.putNextEntry(new ZipEntry("META-INF/"));
				zip.closeEntry();
			}
		}
		JarIndexStore store = openStore(state.resolve("fake-index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.fasterxml.jackson.databind.ObjectMapper", SymbolKind.CLASS, null, null, false,
						"", false),
				new Symbol("tools.jackson.databind.ObjectMapper", SymbolKind.CLASS, null, null, false, "", false));
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
		return new CheckCommand(store, indexer, resolver, new JdkIndex());
	}

	private CheckCommand emptyCommand() throws Exception {
		Path jar = files.resolve("fake-dep.jar");
		if (!Files.exists(jar)) {
			try (OutputStream out = Files.newOutputStream(jar);
					JarOutputStream zip = new JarOutputStream(out)) {
				zip.putNextEntry(new ZipEntry("META-INF/"));
				zip.closeEntry();
			}
		}
		JarIndexStore store = openStore(state.resolve("empty-index"));
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Unrelated", SymbolKind.CLASS, null, null, false, "", false));
		DependencyResolver resolver = (projectDir, allowNetwork) -> List.of(jar);
		return new CheckCommand(store, indexer, resolver, new JdkIndex());
	}

	private CheckCommand command() throws Exception {
		JarIndexStore store = openStore(state.resolve("index"));
		LibraryIndexer indexer = ServiceLoader.load(LibraryIndexer.class).findFirst().orElseThrow(
				() -> new IllegalStateException("No LibraryIndexer on the test classpath"));
		DependencyResolver resolver = new MavenResolver(state);
		return new CheckCommand(store, indexer, resolver, new JdkIndex());
	}

	private Path file(String name, String content) throws Exception {
		Path file = files.resolve(name);
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}
}
