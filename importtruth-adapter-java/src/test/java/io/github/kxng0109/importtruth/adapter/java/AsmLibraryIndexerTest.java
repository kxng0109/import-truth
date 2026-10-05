package io.github.kxng0109.importtruth.adapter.java;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies bytecode extraction against freshly compiled fixtures.
 */
@DisplayName("AsmLibraryIndexer")
final class AsmLibraryIndexerTest {

	private static final String OLD =
			"package com.example; @Deprecated(since = \"1.2\", forRemoval = true) public class Old {"
					+ " public void run() {} public void run(String arg) {} private void secret() {}"
					+ " public static final String VERSION = \"1\";"
					+ " public static class Holder { public void hold() {} } }";
	private static final String BOX =
			"package com.example; public class Box<T> { public void put(T item) {} }";
	private static final String STRING_BOX =
			"package com.example; public class StringBox extends Box<String> {"
					+ " @Override public void put(String item) {} public void take(String... args) {} }";

	@TempDir
	private Path work;

	@Test
	@DisplayName("records deprecation, overloads, inner types, and skips hidden members")
	void extractsPublicShapeOnly() throws Exception {
		Path jar = jarOf(Map.of("com.example.Old", OLD, "com.example.Box", BOX, "com.example.StringBox", STRING_BOX));

		List<Symbol> symbols = new AsmLibraryIndexer().index(jar);

		Symbol old = find(symbols, "com.example.Old");
		assertThat(old.kind()).as("type kind").isEqualTo(SymbolKind.CLASS);
		assertThat(old.deprecated()).as("deprecated flag").isTrue();
		assertThat(old.deprecatedSince()).as("deprecated since").isEqualTo("1.2");
		assertThat(old.forRemoval()).as("for removal").isTrue();
		assertThat(find(symbols, "com.example.Old.run").signature())
				.as("no-arg overload descriptor")
				.isEqualTo("()V");
		assertThat(symbols.stream().filter(s -> s.fqn().equals("com.example.Old.run")).count())
				.as("overload count")
				.isEqualTo(2L);
		assertThat(find(symbols, "com.example.Old.VERSION").kind()).as("field kind").isEqualTo(SymbolKind.FIELD);
		assertThat(symbols.stream().noneMatch(s -> s.fqn().equals("com.example.Old.secret")))
				.as("private method excluded")
				.isTrue();
		assertThat(find(symbols, "com.example.Old$Holder").kind()).as("inner class kind").isEqualTo(SymbolKind.CLASS);
		assertThat(find(symbols, "com.example.StringBox.take").signature())
				.as("varargs descriptor")
				.isEqualTo("([Ljava/lang/String;)V");
		assertThat(symbols.stream().noneMatch(SymbolTestPredicates::isBridgePut))
				.as("bridge method excluded")
				.isTrue();
	}

	private static Symbol find(List<Symbol> symbols, String fqn) {
		return symbols.stream()
				.filter(s -> s.fqn().equals(fqn))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Missing symbol: " + fqn));
	}

	private Path jarOf(Map<String, String> sources) throws Exception {
		Path classes = work.resolve("classes");
		Files.createDirectories(classes);
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
		Path jar = work.resolve("fixture.jar");
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

	/** Keeps bridge-method assertions readable without inline lambdas. */
	private static final class SymbolTestPredicates {

		private SymbolTestPredicates() {
		}

		static boolean isBridgePut(Symbol symbol) {
			return symbol.fqn().equals("com.example.StringBox.put")
					&& "(Ljava/lang/Object;)V".equals(symbol.signature());
		}
	}
}
