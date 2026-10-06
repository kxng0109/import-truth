package io.github.kxng0109.importtruth.adapter.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.TimeUnit;

/**
 * Verifies multi-release selection, type kinds, and archive limits
 * against hand-built jars.
 */
@DisplayName("Multi-release and kinds")
final class MultiReleaseAndKindsTest {

	@TempDir
	private Path work;

	@TempDir
	private static Path shared;

	private static Path baseClasses;
	private static Path nineClasses;
	private static Path tenClasses;
	private static Path kindsClasses;

	/** Compiles every fixture once; classes are read-only and shared. */
	@BeforeAll
	static void compileFixtures() throws Exception {
		baseClasses = compileIn(shared, Map.of("com.mr.Feature",
				"package com.mr; public class Feature { public void base() {} }"));
		nineClasses = compileIn(shared, Map.of("com.mr.Feature",
				"package com.mr; public class Feature { public void nine() {} }"));
		tenClasses = compileIn(shared, Map.of("com.mr.Feature",
				"package com.mr; public class Feature { public void ten() {} }"));
		kindsClasses = compileIn(shared, Map.of(
				"com.example.Anno", "package com.example; public @interface Anno {}",
				"com.example.State", "package com.example; public enum State { ON, OFF }",
				"com.example.Point", "package com.example; public record Point(int x) {}",
				"com.example.Marked",
				"package com.example; public class Marked {"
						+ " @Deprecated public void old() {}"
						+ " @Deprecated public String legacy = \"x\";"
						+ " @SuppressWarnings(\"all\") public void plain() {}"
						+ " @Valued(n = 4, flag = true) public int count = 0; }",
				"com.example.Hidden", "package com.example; class Hidden { public void show() {} }",
				"com.example.Valued", "package com.example; public @interface Valued { int n(); boolean flag(); }",
				"com.example.Odd",
				"package com.example; public @interface Odd { int since(); String forRemoval(); }",
				"com.example.Rated",
				"package com.example; public class Rated { @Valued(n = 1, flag = true) public void run() {}"
						+ " @Odd(since = 2, forRemoval = \"x\") public void odd() {}"
						+ " @Valued(n = 3, flag = false) public int score = 0; }"));
	}

	@Test
	@Tag("slow")
	@DisplayName("prefers the newest compatible versioned entry")
	void prefersVersionedEntries() throws Exception {
		Path base = baseClasses;
		Path nine = nineClasses;
		Path ten = tenClasses;
		Path jar = work.resolve("mr.jar");
		try (OutputStream out = Files.newOutputStream(jar);
				JarOutputStream zip = new JarOutputStream(out)) {
			put(zip, "com/mr/Feature.class", base, "com/mr/Feature.class");
			put(zip, "META-INF/versions/10/com/mr/Feature.class", ten, "com/mr/Feature.class");
			put(zip, "META-INF/versions/9/com/mr/Feature.class", nine, "com/mr/Feature.class");
			put(zip, "META-INF/versions/8/com/mr/Feature.class", base, "com/mr/Feature.class");
			put(zip, "META-INF/versions/x/com/mr/Feature.class", base, "com/mr/Feature.class");
			put(zip, "META-INF/versions/99/com/mr/Feature.class", nine, "com/mr/Feature.class");
			put(zip, "META-INF/versions/9", "unversioned".getBytes(StandardCharsets.UTF_8));
			put(zip, "META-INF/versions/9Foo.class", base, "com/mr/Feature.class");
			put(zip, "module-info.class", base, "com/mr/Feature.class");
			put(zip, "META-INF/versions/9/module-info.class", base, "com/mr/Feature.class");
			byte[] big = new byte[AsmLibraryIndexer.MAX_CLASS_BYTES + 1];
			put(zip, "com/mr/Big.class", big);
			zip.putNextEntry(new JarEntry("com/mr/"));
			zip.closeEntry();
		}

		List<Symbol> symbols = new AsmLibraryIndexer().index(jar);

		assertThat(symbols.stream().anyMatch(s -> s.fqn().equals("com.mr.Feature.ten")))
				.as("newest compatible wins")
				.isTrue();
		assertThat(symbols.stream().anyMatch(s -> s.fqn().equals("com.mr.Feature.nine")))
				.as("older version replaced")
				.isFalse();
		assertThat(symbols.stream().anyMatch(s -> s.fqn().equals("com.mr.Feature.base")))
				.as("base method replaced")
				.isFalse();
		assertThat(symbols.stream().anyMatch(s -> s.fqn().contains("Big")))
				.as("oversized entry skipped")
				.isFalse();
	}

	@Test
	@Tag("slow")
	@DisplayName("records annotation, enum, and record kinds with member deprecation")
	void recordsKinds() throws Exception {
		Path jar = jarFrom(kindsClasses);

		List<Symbol> symbols = new AsmLibraryIndexer().index(jar);

		assertThat(kindOf(symbols, "com.example.Anno")).as("annotation kind").isEqualTo(SymbolKind.ANNOTATION);
		assertThat(kindOf(symbols, "com.example.State")).as("enum kind").isEqualTo(SymbolKind.ENUM);
		assertThat(kindOf(symbols, "com.example.Point")).as("record kind").isEqualTo(SymbolKind.RECORD);
		assertThat(symbols.stream()
						.filter(s -> s.fqn().equals("com.example.Marked.old"))
						.findFirst()
						.orElseThrow(() -> new AssertionError("Missing method")))
				.as("deprecated method")
				.matches(s -> s.deprecated() && !s.forRemoval() && s.deprecatedSince().isEmpty());
		assertThat(symbols.stream()
						.filter(s -> s.fqn().equals("com.example.Marked.legacy"))
						.findFirst()
						.orElseThrow(() -> new AssertionError("Missing field")))
				.as("deprecated field")
				.matches(Symbol::deprecated);
		assertThat(symbols.stream().anyMatch(s -> s.fqn().equals("com.example.Marked.count")))
				.as("plain field recorded")
				.isTrue();
		assertThat(symbols.stream().anyMatch(s -> s.fqn().startsWith("com.example.Hidden")))
				.as("package-private type skipped")
				.isFalse();
		assertThat(symbols.stream().anyMatch(s -> s.fqn().equals("com.example.Rated.run")))
				.as("custom annotation tolerated")
				.isTrue();
	}

	@Test
	@Tag("slow")
	@DisplayName("refuses archives with too many entries")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void refusesHugeArchives() throws Exception {
		Path jar = work.resolve("huge.jar");
		try (OutputStream out = Files.newOutputStream(jar);
				JarOutputStream zip = new JarOutputStream(out)) {
			for (int i = 0; i <= AsmLibraryIndexer.MAX_ENTRIES; i++) {
				zip.putNextEntry(new JarEntry("f" + i + ".txt"));
				zip.write(0);
				zip.closeEntry();
			}
		}

		assertThatThrownBy(() -> new AsmLibraryIndexer().index(jar))
				.as("entry cap enforced")
				.isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("skips synthetic types and tolerates malformed annotations")
	void skipsSyntheticAndMalformed() throws Exception {
		Path jar = work.resolve("crafted.jar");
		try (OutputStream out = Files.newOutputStream(jar);
				JarOutputStream zip = new JarOutputStream(out)) {
			put(zip, "com/synth/Gen.class", synthetic());
			put(zip, "com/mr/Bent.class", malformed());
		}

		List<Symbol> symbols = new AsmLibraryIndexer().index(jar);

		assertThat(symbols.stream().anyMatch(s -> s.fqn().startsWith("com.synth.Gen")))
				.as("synthetic type and members skipped")
				.isFalse();
		assertThat(symbols.stream()
						.filter(s -> s.fqn().equals("com.mr.Bent"))
						.findFirst()
						.orElseThrow(() -> new AssertionError("Missing type")))
				.as("malformed values ignored, flag stands")
				.matches(s -> s.deprecated() && s.deprecatedSince().isEmpty() && !s.forRemoval());
	}

	private static byte[] synthetic() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "com/synth/Gen", null,
				"java/lang/Object", null);
		MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
		run.visitCode();
		run.visitInsn(Opcodes.RETURN);
		run.visitMaxs(0, 1);
		run.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] malformed() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/mr/Bent", null, "java/lang/Object", null);
		AnnotationVisitor broken = writer.visitAnnotation("Ljava/lang/Deprecated;", true);
		broken.visit("since", Integer.valueOf(1));
		broken.visit("forRemoval", "yes");
		broken.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static SymbolKind kindOf(List<Symbol> symbols, String fqn) {
		return symbols.stream()
				.filter(s -> s.fqn().equals(fqn))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing symbol: " + fqn))
				.kind();
	}

	private static Path compileIn(Path root, Map<String, String> sources) throws Exception {
		Path classes = Files.createTempDirectory(root, "classes");
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("system compiler present").isNotNull();
		List<String> files = new ArrayList<>();
		for (Map.Entry<String, String> source : sources.entrySet()) {
			Path file = classes.resolve(source.getKey().replace('.', '/') + ".java");
			Files.createDirectories(file.getParent());
			Files.write(file, source.getValue().getBytes(StandardCharsets.UTF_8));
			files.add(file.toString());
		}
		List<String> args = new ArrayList<>(List.of("-d", classes.toString()));
		args.addAll(files);
		assertThat(compiler.run(null, null, null, args.toArray(new String[0])))
				.as("fixture compilation")
				.isEqualTo(0);
		return classes;
	}

	private Path jarFrom(Path classes) throws Exception {
		Path jar = Files.createTempFile(work, "fixture", ".jar");
		try (OutputStream out = Files.newOutputStream(jar);
				JarOutputStream zip = new JarOutputStream(out);
				Stream<Path> walk = Files.walk(classes)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
				String name = classes.relativize(file).toString().replace('\\', '/');
				zip.putNextEntry(new JarEntry(name));
				Files.copy(file, zip);
				zip.closeEntry();
			}
		}
		return jar;
	}

	private static void put(JarOutputStream zip, String name, Path classes, String member) throws Exception {
		zip.putNextEntry(new JarEntry(name));
		Files.copy(classes.resolve(member), zip);
		zip.closeEntry();
	}

	private static void put(JarOutputStream zip, String name, byte[] bytes) throws Exception {
		zip.putNextEntry(new JarEntry(name));
		zip.write(bytes);
		zip.closeEntry();
	}
}
