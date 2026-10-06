package io.github.kxng0109.importtruth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.MavenResolver;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ServiceLoader;

import org.junit.jupiter.api.DisplayName;
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

	@Test
	@DisplayName("silent clean, loud missing, policy rename")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void goldenChecks() throws Exception {
		Path project = Paths.get(System.getProperty("user.dir")).getParent();
		CheckCommand command = command();
		Path clean = file("Clean.java",
				"package com.example; import java.util.List; import tools.jackson.databind.ObjectMapper;"
						+ " public class Clean { List<ObjectMapper> items; }");
		Path missing = file("Missing.java",
				"package com.other;\nimport org.example.Nope;\npublic class Missing { Nope nope; }");
		Path legacy = file("Legacy.java",
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
	}

	private int run(CheckCommand command, Path project, Path file) throws Exception {
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		return command.run(new PrintStream(sink, true, StandardCharsets.UTF_8), System.err, project, file);
	}

	private CheckCommand command() throws Exception {
		JarIndexStore store = new JarIndexStore(state.resolve("index"));
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
