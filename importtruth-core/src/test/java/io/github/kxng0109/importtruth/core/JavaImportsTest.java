package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.core.JavaImports.ImportRef;
import io.github.kxng0109.importtruth.core.JavaImports.ImportScan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies import parsing: exact lines, shared-line imports, validation,
 * and unhealthy files.
 */
@DisplayName("JavaImports")
final class JavaImportsTest {

	@TempDir
	private Path files;

	@Test
	@DisplayName("reads exact lines including shared-line imports")
	void readsExactLines() throws Exception {
		Path file = files.resolve("Two.java");
		Files.write(file, ("package com.example;\nimport java.util.List;import java.util.Map;\n"
				+ "public class Two { List<String> a; Map<String, String> b; }")
				.getBytes(StandardCharsets.UTF_8));

		ImportScan scan = JavaImports.of(file);

		assertThat(scan.healthy()).as("healthy file").isTrue();
		assertThat(scan.imports()).as("both imports found").hasSize(2);
		assertThat(scan.imports().get(0).target()).as("first target").isEqualTo("java.util.List");
		assertThat(scan.imports().get(0).line()).as("first line").isEqualTo(2);
		assertThat(scan.imports().get(1).target()).as("second target").isEqualTo("java.util.Map");
		assertThat(scan.imports().get(1).line()).as("second line").isEqualTo(2);
	}

	@Test
	@DisplayName("rejects bad references")
	void rejectsBadReferences() {
		assertThatThrownBy(() -> new ImportRef(" ", false, false, 1))
				.as("blank rejection")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ImportRef("a.B", false, false, 0))
				.as("bad line rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("evicts eldest file scans when full")
	void evictsEldestFiles() throws Exception {
		for (int i = 0; i < 257; i++) {
			Path file = files.resolve("File" + i + ".java");
			Files.write(file, ("package com.example; import java.util.List; public class File" + i + " { }")
					.getBytes(StandardCharsets.UTF_8));
			assertThat(JavaImports.of(file).healthy()).as("file parsed").isTrue();
		}

		Path first = files.resolve("File0.java");
		assertThat(JavaImports.of(first).healthy()).as("evicted file re-parses").isTrue();
	}

	@Test
	@DisplayName("marks directories unhealthy")
	void marksDirectoriesUnhealthy() {
		ImportScan scan = JavaImports.of(files);

		assertThat(scan.healthy()).as("directory unhealthy").isFalse();
		assertThat(scan.imports()).as("no imports").isEmpty();
	}

	@Test
	@DisplayName("marks missing files unhealthy")
	void marksMissingFilesUnhealthy() {
		ImportScan scan = JavaImports.of(files.resolve("Nope.java"));

		assertThat(scan.healthy()).as("missing file unhealthy").isFalse();
		assertThat(scan.imports()).as("no imports").isEmpty();
	}

	@Test
	@DisplayName("reuses scans for unchanged content")
	void reusesUnchangedScans() throws Exception {
		Path file = files.resolve("Cached.java");
		Files.write(file, "package com.example;\nimport java.util.List;\npublic class Cached { }"
				.getBytes(StandardCharsets.UTF_8));

		ImportScan first = JavaImports.of(file);
		ImportScan second = JavaImports.of(file);

		assertThat(second).as("same cached instance").isSameAs(first);

		Files.write(file, "package com.example;\nimport java.util.Map;\npublic class Cached { }"
				.getBytes(StandardCharsets.UTF_8));
		ImportScan third = JavaImports.of(file);

		assertThat(third).as("changed content reparsed").isNotSameAs(first);
		assertThat(third.imports()).as("new imports").hasSize(1);
		assertThat(third.imports().get(0).target()).as("new target").isEqualTo("java.util.Map");
	}

	@Test
	@DisplayName("batch parse agrees with the per file parse file for file")
	void batchAgreesWithPerFile() throws Exception {
		List<String> sources = List.of(
				"package com.example;\nimport java.util.List;\npublic class One { List<String> a; }",
				"package com.example;\nimport java.util.Map;import java.util.Set;\n"
						+ "public class Two { Map<String,String> m; }",
				"package com.example; import java.io.*; public class Three { }",
				"package com.example; import static java.util.Arrays.asList; public class Four { }",
				"package com.example; import java.util.List; public class Five { void x( { } }");
		List<Path> single = new ArrayList<>();
		List<Path> batch = new ArrayList<>();
		for (int i = 0; i < sources.size(); i++) {
			Path one = files.resolve("single").resolve("Class" + i + ".java");
			Path two = files.resolve("batch").resolve("Class" + i + ".java");
			Files.createDirectories(one.getParent());
			Files.createDirectories(two.getParent());
			Files.write(one, sources.get(i).getBytes(StandardCharsets.UTF_8));
			Files.write(two, sources.get(i).getBytes(StandardCharsets.UTF_8));
			single.add(one);
			batch.add(two);
		}

		Map<Path, ImportScan> scans = JavaImports.ofAll(batch);

		for (int i = 0; i < single.size(); i++) {
			ImportScan one = JavaImports.of(single.get(i));
			ImportScan parsed = scans.get(batch.get(i));
			assertThat(parsed).as("answer of " + batch.get(i).getFileName()).isNotNull();
			assertThat(parsed.healthy()).as("health of " + single.get(i).getFileName())
					.isEqualTo(one.healthy());
			assertThat(parsed.imports()).as("imports of " + single.get(i).getFileName())
					.isEqualTo(one.imports());
		}
	}

	@Test
	@DisplayName("a broken file does not cost the batch its other files")
	void brokenFileKeepsTheRest() throws Exception {
		Path good = files.resolve("Good.java");
		Path bad = files.resolve("Bad.java");
		Files.write(good, "package com.example; import java.util.List; public class Good { }"
				.getBytes(StandardCharsets.UTF_8));
		Files.write(bad, "package com.example; import java.util.List; public class Bad { void x( { } }"
				.getBytes(StandardCharsets.UTF_8));

		Map<Path, ImportScan> scans = JavaImports.ofAll(List.of(good, bad));

		assertThat(scans.get(good).healthy()).as("good file healthy").isTrue();
		assertThat(scans.get(good).imports()).as("good file imports").hasSize(1);
		assertThat(scans.get(bad).healthy()).as("broken file unhealthy").isFalse();
		assertThat(JavaImports.of(bad).healthy()).as("per file agrees").isFalse();
	}

	@Test
	@DisplayName("a missing file is answered on its own")
	void missingFileAnswersOnItsOwn() throws Exception {
		Path good = files.resolve("There.java");
		Path missing = files.resolve("Gone.java");
		Files.write(good, "package com.example; import java.util.List; public class There { }"
				.getBytes(StandardCharsets.UTF_8));

		Map<Path, ImportScan> scans = JavaImports.ofAll(List.of(good, missing));

		assertThat(scans.get(good).healthy()).as("good file healthy").isTrue();
		assertThat(scans.get(missing).healthy()).as("missing file unhealthy").isFalse();
		assertThat(scans.get(missing).imports()).as("missing file imports").isEmpty();
	}

	@Test
	@DisplayName("parses a batch with one compiler task and one file manager")
	void parsesBatchWithOneTask() throws Exception {
		List<Path> batch = new ArrayList<>();
		List<Path> single = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Path file = files.resolve("Batch" + i + ".java");
			Files.write(file, ("package com.example; import java.util.List; public class Batch" + i + " { }")
					.getBytes(StandardCharsets.UTF_8));
			batch.add(file);
			Path one = files.resolve("Single" + i + ".java");
			Files.write(one, ("package com.example; import java.util.List; public class Single" + i + " { }")
					.getBytes(StandardCharsets.UTF_8));
			single.add(one);
		}
		int tasks = JavaImports.TASKS.get();
		int managers = JavaImports.MANAGERS.get();

		JavaImports.ofAll(batch);

		assertThat(JavaImports.TASKS.get() - tasks).as("one compiler task for the batch").isEqualTo(1);
		assertThat(JavaImports.MANAGERS.get() - managers).as("one file manager for the batch").isEqualTo(1);

		int singleTasks = JavaImports.TASKS.get();
		int singleManagers = JavaImports.MANAGERS.get();
		for (Path file : single) {
			JavaImports.of(file);
		}

		assertThat(JavaImports.TASKS.get() - singleTasks).as("one task per file").isEqualTo(5);
		assertThat(JavaImports.MANAGERS.get() - singleManagers).as("one manager per file").isEqualTo(5);
	}

	@Test
	@DisplayName("serves a repeated batch from the content cache")
	void servesRepeatedBatchFromCache() throws Exception {
		Path file = files.resolve("Cached.java");
		Files.write(file, "package com.example; import java.util.List; public class Cached { }"
				.getBytes(StandardCharsets.UTF_8));

		ImportScan first = JavaImports.ofAll(List.of(file)).get(file);
		ImportScan second = JavaImports.ofAll(List.of(file)).get(file);

		assertThat(second).as("the cached scan").isSameAs(first);
	}

	@Test
	@DisplayName("answers a file whose name the URI cannot carry on its own")
	void answersUncarryableNameOnItsOwn() throws Exception {
		Path good = files.resolve("Good.java");
		Path odd = files.resolve("Odd Name.java");
		Files.write(good, "package com.example; import java.util.List; public class Good { }"
				.getBytes(StandardCharsets.UTF_8));
		Files.write(odd, "package com.example; import java.util.List; public class Odd { }"
				.getBytes(StandardCharsets.UTF_8));

		Map<Path, ImportScan> scans = JavaImports.ofAll(List.of(good, odd));

		assertThat(scans.get(good).healthy()).as("the good file is healthy").isTrue();
		assertThat(scans.get(odd).healthy()).as("the odd file is unanswered").isFalse();
		assertThat(JavaImports.of(odd).healthy()).as("the per file path agrees").isFalse();
		assertThat(JavaImports.ofAll(List.of(odd)).get(odd).healthy())
				.as("a batch of one odd file is unanswered")
				.isFalse();
	}

	@Test
	@DisplayName("reparses a batch whose file changed")
	void reparsesChangedBatch() throws Exception {
		Path file = files.resolve("Changed.java");
		Files.write(file, "package com.example; import java.util.List; public class Changed { }"
				.getBytes(StandardCharsets.UTF_8));
		JavaImports.ofAll(List.of(file));

		Files.write(file, "package com.example; import java.util.Map; public class Changed { }"
				.getBytes(StandardCharsets.UTF_8));
		ImportScan after = JavaImports.ofAll(List.of(file)).get(file);

		assertThat(after.imports()).as("the new imports").hasSize(1);
		assertThat(after.imports().get(0).target()).as("the new target").isEqualTo("java.util.Map");
	}

	@Test
	@DisplayName("falls back to the single file path when an error belongs to no file")
	void fallsBackWhenErrorBelongsToNoFile() throws Exception {
		Path file = files.resolve("Stray.java");
		Files.write(file, "package com.example; import java.util.List; public class Stray { }"
				.getBytes(StandardCharsets.UTF_8));
		JavaImports.strayErrors = true;
		try {
			ImportScan scan = JavaImports.ofAll(List.of(file)).get(file);

			assertThat(scan.healthy()).as("the file answers on its own").isTrue();
			assertThat(scan.imports()).as("the imports answer").hasSize(1);
		} finally {
			JavaImports.strayErrors = false;
		}
	}

	@Test
	@DisplayName("rejects null batch inputs")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullBatchInputs() {
		assertThatThrownBy(() -> JavaImports.ofAll(null))
				.as("null list rejected")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> JavaImports.ofAll(Arrays.asList((Path) null)))
				.as("null file rejected")
				.isInstanceOf(NullPointerException.class);
	}
}
