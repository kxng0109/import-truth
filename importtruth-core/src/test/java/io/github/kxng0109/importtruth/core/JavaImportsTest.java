package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.core.JavaImports.ImportRef;
import io.github.kxng0109.importtruth.core.JavaImports.ImportScan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
