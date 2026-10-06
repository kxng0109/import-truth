package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies file checking: clean files silent, misses flagged with lines,
 * wildcards package-checked, broken files skipped.
 */
@DisplayName("FileCheckService")
final class FileCheckServiceTest {

	@TempDir
	private Path project;

	@TempDir
	private Path state;

	@Test
	@DisplayName("silent on clean imports")
	void silentOnClean() throws Exception {
		FileCheckService check = service();
		Path file = source("com/example/Clean.java",
				"package com.example; import java.util.List; import com.example.Widget;"
						+ " public class Clean { List<Widget> items; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("no findings when clean").isEmpty();
	}

	@Test
	@DisplayName("flags a missing import with its line")
	void flagsMissingWithLine() throws Exception {
		FileCheckService check = service();
		Path file = source("com/other/Missing.java",
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("missing kind").isEqualTo(FindingKind.MISSING);
		assertThat(result.findings().get(0).line()).as("finding line").isEqualTo(2);
		assertThat(result.findings().get(0).detail()).as("detail names target").contains("org.example.Nope");
	}

	@Test
	@DisplayName("checks wildcards at package granularity")
	void checksWildcardPackages() throws Exception {
		FileCheckService check = service();
		Path file = source("com/example/Wild.java",
				"package com.example; import java.util.*; import com.nothing.*;"
						+ " public class Wild { java.util.List<String> items; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("only the absent package flagged").hasSize(1);
		assertThat(result.findings().get(0).detail()).as("detail names package").contains("com.nothing");
	}

	@Test
	@DisplayName("treats unresolved own-package names as candidates")
	void treatsOwnPackageAsCandidate() throws Exception {
		FileCheckService check = service();
		Path file = source("com/acme/Service.java",
				"package com.acme; import com.acme.Other; public class Service { Other other; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("candidate kind").isEqualTo(FindingKind.CANDIDATE);
	}

	@Test
	@DisplayName("skips unparseable files silently")
	void skipsUnparseable() throws Exception {
		FileCheckService check = service();
		Path file = source("com/example/Broken.java",
				"package com.example; import java.util.List; public class Broken { void x( { } }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("broken file unhealthy").isFalse();
		assertThat(result.findings()).as("no findings when unhealthy").isEmpty();
	}

	@Test
	@DisplayName("resolves own and dependency wildcards silently")
	void resolvesOwnWildcards() throws Exception {
		FileCheckService check = service();
		Path file = source("com/acme/OwnWild.java",
				"package com.acme; import com.acme.*; import com.example.Widget.*; import static com.example.Widget.*;"
						+ " public class OwnWild { }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("wildcards resolved").isEmpty();
	}

	@Test
	@DisplayName("treats bare own-package names as candidates")
	void treatsBareOwnNamesAsCandidates() throws Exception {
		FileCheckService check = service();
		source("com/acme/Service.java",
				"package com.acme; import com.acme.Other; public class Service { Other other; }");
		Path file = source("com/other/SelfRef.java",
				"package com.other; import com.acme; public class SelfRef { }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("candidate kind").isEqualTo(FindingKind.CANDIDATE);
	}

	@Test
	@DisplayName("caps suggestions at three names")
	void capsSuggestions() throws Exception {
		FileCheckService check = service();
		Path file = source("com/other/Sugg.java",
				"package com.other;\nimport org.example.Widget;\npublic class Sugg { Widget w; }");

		CheckResult result = check.check(project, file);

		assertThat(result.healthy()).as("healthy file").isTrue();
		assertThat(result.findings()).as("one finding").hasSize(1);
		assertThat(result.findings().get(0).kind()).as("missing kind").isEqualTo(FindingKind.MISSING);
		assertThat(result.findings().get(0).suggestion()).as("three suggestions")
				.isEqualTo("maybe: com.example.Widget, com.example.Widget.help, com.example.Widget.build");
	}

	@Test
	@DisplayName("falls back to file names outside the project")
	void fallsBackOutsideProject() throws Exception {
		FileCheckService check = service();
		Path outside = Files.createTempFile("Elsewhere", ".java");
		try {
			Files.write(outside,
					"import org.example.Nope; public class Elsewhere { Nope n; }".getBytes(StandardCharsets.UTF_8));

			CheckResult result = check.check(Paths.get("."), outside);

			assertThat(result.healthy()).as("healthy file").isTrue();
			assertThat(result.findings()).as("one finding").hasSize(1);
			assertThat(result.findings().get(0).file()).as("file name fallback")
					.isEqualTo(outside.getFileName().toString());
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	private FileCheckService service() throws IOException {
		Path jar = project.resolve("dep.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
		}
		FakeIndexer indexer = new FakeIndexer();
		JarIndexStore store = new JarIndexStore(state.resolve("index"));
		DependencyResolver resolver = new DependencyResolver() {
			@Override
			public List<Path> resolve(Path projectDir, boolean allowNetwork) {
				return List.of(jar);
			}
		};
		return new FileCheckService(store, indexer, resolver, new JdkIndex());
	}

	private Path source(String relative, String content) throws IOException {
		Path file = project.resolve("src/main/java").resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	/** Canned dependency symbols, no bytecode involved. */
	private static final class FakeIndexer implements LibraryIndexer {

		@Override
		public List<Symbol> index(Path jar) {
			return List.of(
					new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false),
					new Symbol("com.example.Widget.build", SymbolKind.METHOD, "()V",
							"com.example.Widget", false, "", false),
					new Symbol("com.example.Widget.help", SymbolKind.METHOD, "()V",
							"com.example.Widget", false, "", false),
					new Symbol("com.example.Widget.version", SymbolKind.FIELD, "Ljava/lang/String;",
							"com.example.Widget", false, "", false));
		}
	}
}
