package io.github.kxng0109.importtruth.adapter.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the class inventory of an archive: the classes it holds,
 * the ones a public API index leaves out, and the way a
 * multi-release archive collapses.
 */
@DisplayName("JarClassInventory")
final class JarClassInventoryTest {

	private static final String PUBLIC_CLASS =
			"package com.example; public class Public { public void show() {} }";

	private static final String PACKAGE_PRIVATE =
			"package com.example; class Hidden { public void show() {} }";

	@TempDir
	private Path work;

	@TempDir
	private static Path shared;

	private static Path classes;

	/** Compiles every fixture once; the classes are read only. */
	@BeforeAll
	static void compileFixtures() throws Exception {
		classes = compileIn(shared, Map.of(
				"com.example.Public", PUBLIC_CLASS,
				"com.example.Hidden", PACKAGE_PRIVATE));
	}

	@Test
	@DisplayName("holds a package private class that a public API index leaves out")
	void holdsAPackagePrivateClass() throws Exception {
		Path jar = work.resolve("plain.jar");
		build(jar, List.of("com/example/Public.class", "com/example/Hidden.class"));

		Set<String> names = JarClassInventory.classesIn(jar);

		assertThat(names).as("public class").contains("com.example.Public");
		assertThat(names).as("package private class").contains("com.example.Hidden");
	}

	@Test
	@DisplayName("collapses a multi release archive to one name per class")
	void collapsesAMultiReleaseArchive() throws Exception {
		Path jar = work.resolve("multi-release.jar");
		build(jar, List.of(
				"com/example/Public.class",
				"META-INF/versions/17/com/example/Public.class",
				"META-INF/versions/9/com/example/Older.class",
				"META-INF/versions/8/com/example/TooOld.class",
				"META-INF/versions/99/com/example/TooNew.class"));

		Set<String> names = JarClassInventory.classesIn(jar);

		assertThat(names).as("inventory")
				.containsExactlyInAnyOrder("com.example.Public", "com.example.Older");
		assertThat(names).as("release below the first multi release").doesNotContain("com.example.TooOld");
		assertThat(names).as("release above the running runtime").doesNotContain("com.example.TooNew");
	}

	@Test
	@DisplayName("leaves out the entries that are not class files")
	void leavesOutTheEntriesThatAreNotClassFiles() throws Exception {
		Path jar = work.resolve("mixed.jar");
		build(jar, List.of("com/example/Public.class", "META-INF/MANIFEST.MF", "com/example/notes.txt"));

		Set<String> names = JarClassInventory.classesIn(jar);

		assertThat(names).as("inventory").containsExactly("com.example.Public");
	}

	@Test
	@DisplayName("leaves out a versioned entry that names no release")
	void leavesOutAVersionedEntryThatNamesNoRelease() throws Exception {
		Path jar = work.resolve("broken.jar");
		build(jar, List.of(
				"com/example/Public.class",
				"META-INF/versions/17.class",
				"META-INF/versions/x/com/example/Public.class",
				"com/example/"));

		Set<String> names = JarClassInventory.classesIn(jar);

		assertThat(names).as("inventory").containsExactly("com.example.Public");
	}

	private void build(Path jar, List<String> entries) throws Exception {
		try (OutputStream out = Files.newOutputStream(jar);
				JarOutputStream zip = new JarOutputStream(out)) {
			for (String entry : entries) {
				zip.putNextEntry(new JarEntry(entry));
				if (entry.endsWith("/")) {
					zip.closeEntry();
					continue;
				}
				if (entry.endsWith(".class")) {
					String member = entry.startsWith("META-INF/versions/")
							? entry.substring(entry.indexOf('/', "META-INF/versions/".length()) + 1)
							: entry;
					Path file = classes.resolve(member);
					if (Files.exists(file)) {
						Files.copy(file, zip);
					} else {
						zip.write(("placeholder for " + entry).getBytes(StandardCharsets.UTF_8));
					}
				} else {
					zip.write("content".getBytes(StandardCharsets.UTF_8));
				}
				zip.closeEntry();
			}
		}
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
