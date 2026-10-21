package io.github.kxng0109.importtruth.adapter.java;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kxng0109.importtruth.model.ClassReferenceScanner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

/**
 * Verifies reference extraction against freshly compiled fixtures.
 * <p>
 * The rule that matters most is the one about names: a class named
 * only inside a string literal is not a reference the bytecode
 * makes, and reporting it would keep a dependency alive that
 * nothing uses.
 */
@DisplayName("AsmClassReferenceScanner")
final class AsmClassReferenceScannerTest {

	private static final String INVISIBLE =
			"package com.example;"
			+ " import java.lang.annotation.ElementType;"
			+ " import java.lang.annotation.Retention;"
			+ " import java.lang.annotation.RetentionPolicy;"
			+ " import java.lang.annotation.Target;"
			+ " @Retention(RetentionPolicy.CLASS)"
			+ " @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD,"
			+ " ElementType.PARAMETER, ElementType.TYPE_USE})"
			+ " public @interface Invisible {}";

	private static final String TARGETS =
			"package com.example;"
			+ " public final class Targets {"
			+ " private Targets() {}"
			+ " public static String greet() { return \"hello\"; } }";

	private static final String CONSTANTS =
			"package com.example;"
			+ " public final class Constants {"
			+ " private Constants() {}"
			+ " public static final java.util.List<String> NAMES = java.util.List.of(\"a\"); }";

	private static final String OUTER =
			"package com.example;"
			+ " public final class Outer implements Sealed {"
			+ " public static class Inner { public String value() { return \"inner\"; } } }";

	private static final String SEALED =
			"package com.example;"
			+ " public sealed interface Sealed permits Outer {}";

	private static final String COMPONENT =
			"package com.example;"
			+ " import java.lang.annotation.ElementType;"
			+ " import java.lang.annotation.Retention;"
			+ " import java.lang.annotation.RetentionPolicy;"
			+ " import java.lang.annotation.Target;"
			+ " @Retention(RetentionPolicy.CLASS)"
			+ " @Target(ElementType.RECORD_COMPONENT)"
			+ " public @interface Component {}";

	private static final String BASE =
			"package com.example;"
			+ " public class Base<T> {}";

	private static final String PAIR =
			"package com.example;"
			+ " public record Pair(@Component String left, int right) {}";

	private static final String FIXTURE =
			"package com.example;"
			+ " import java.util.Map;"
			+ " @Invisible"
			+ " public class Fixture {"
			+ " @Invisible public String field;"
			+ " public Map.Entry<String, String> entry;"
			+ " public String callTarget() { return Targets.greet(); }"
			+ " public int readTarget() { return Constants.NAMES.size(); }"
			+ " public Runnable lambda() { return () -> System.out.println(\"lambda\"); }"
			+ " public String literal() { return \"com.example.OnlyInAString\"; }"
			+ " public Class<?> byName() throws Exception {"
			+ "   return Class.forName(\"com.example.OnlyByName\"); }"
			+ " @Invisible public String annotated(@Invisible String argument) { return argument; }"
			+ " public String nested() { return new Outer.Inner().value(); }"
			+ " public <T extends Comparable<T>> T identity(T value) { return value; }"
			+ " public static class Holder extends Base<@Invisible String> {}"
			+ " public Class<?> classLiteral() { return Targets.class; }"
			+ " public int[][] grid() { return new int[2][3]; }"
			+ " public String guarded() {"
			+ "   try { return Targets.greet(); }"
			+ "   catch (IllegalStateException failure) { return \"failed\"; }"
			+ "   finally { String.valueOf(Constants.NAMES); } }"
			+ " public String arrays() {"
			+ "   Object value = \"text\";"
			+ "   if (value instanceof String[] parts) { return parts[0]; }"
			+ "   return \"\"; }"
			+ " public String anonymous() {"
			+ "   return new Object() {"
			+ "     @Override public String toString() { return \"anonymous\"; }"
			+ "   }.toString(); } }";

	@TempDir
	private Path work;

	@TempDir
	private static Path shared;

	private static Path classes;

	/** Compiles every fixture once; the classes are read only. */
	@BeforeAll
	static void compileFixtures() throws Exception {
		classes = compileIn(shared, Map.of(
				"com.example.Invisible", INVISIBLE,
				"com.example.Component", COMPONENT,
				"com.example.Base", BASE,
				"com.example.Targets", TARGETS,
				"com.example.Constants", CONSTANTS,
				"com.example.Outer", OUTER,
				"com.example.Sealed", SEALED,
				"com.example.Pair", PAIR,
				"com.example.Fixture", FIXTURE));
	}

	@Test
	@DisplayName("reports the owner of a called method")
	void reportsTheOwnerOfACalledMethod() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("owner of the called method").contains("com.example.Targets");
	}

	@Test
	@DisplayName("reports the owner of a read field")
	void reportsTheOwnerOfAReadField() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("owner of the read field").contains("com.example.Constants");
	}

	@Test
	@DisplayName("reports the bootstrap owner of a lambda")
	void reportsTheBootstrapOwnerOfALambda() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("bootstrap owner of the lambda")
				.contains("java.lang.invoke.LambdaMetafactory");
	}

	@Test
	@DisplayName("reports the type of a runtime invisible annotation")
	void reportsTheTypeOfARuntimeInvisibleAnnotation() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("type of the invisible annotation").contains("com.example.Invisible");
	}

	@Test
	@DisplayName("never reports a class named only in a string literal")
	void neverReportsAClassNamedOnlyInAStringLiteral() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("class named only in a string").doesNotContain("com.example.OnlyInAString");
	}

	@Test
	@DisplayName("never reports the target of a class loaded by name")
	void neverReportsTheTargetOfAClassForNameCall() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("class loaded by name").doesNotContain("com.example.OnlyByName");
	}

	@Test
	@DisplayName("never reports the scanned class itself")
	void neverReportsTheScannedClassItself() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("the scanned class").doesNotContain("com.example.Fixture");
	}

	@Test
	@DisplayName("names a nested type with a dollar sign")
	void namesNestedTypesWithADollarSign() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("nested type").contains("com.example.Outer$Inner");
	}

	@Test
	@DisplayName("names a type that only a generic signature holds")
	void namesTypesHeldOnlyByAGenericSignature() throws Exception {
		Set<String> names = scanFixture();

		assertThat(names).as("nested type of a generic signature").contains("java.util.Map$Entry");
	}

	@Test
	@DisplayName("reads a directory of class files and ignores the other files")
	void readsADirectoryOfClassFiles() throws Exception {
		Files.writeString(classes.resolve("notes.txt"), "not a class file");

		Set<String> names = new AsmClassReferenceScanner().referencesOf(classes);

		assertThat(names).as("scanned directory").contains("com.example.Targets");
		assertThat(names).as("scanned directory").contains("java.lang.Record");
		assertThat(names).as("class named only in a string").doesNotContain("com.example.OnlyInAString");
	}

	@Test
	@DisplayName("reads a class file that names a nested type the specification way")
	void readsASignatureWithASpecifiedNestedType() throws Exception {
		Path file = work.resolve("com/example/Synthesized.class");
		Files.createDirectories(file.getParent());
		Files.write(file, synthesized());

		Set<String> names = new AsmClassReferenceScanner().referencesOf(file);

		assertThat(names).as("nested type of a dotted signature").contains("com.example.Outer$Inner");
		assertThat(names).as("class of a constant").contains("com.example.Target");
	}

	@Test
	@DisplayName("is registered as a service")
	void isRegisteredAsAService() {
		List<ClassReferenceScanner> scanners = ServiceLoader.load(ClassReferenceScanner.class).stream()
				.map(ServiceLoader.Provider::get)
				.toList();

		assertThat(scanners).as("registered scanners").isNotEmpty();
		assertThat(scanners.get(0)).as("registered implementation")
				.isInstanceOf(AsmClassReferenceScanner.class);
	}

	private static Set<String> scanFixture() throws Exception {
		return new AsmClassReferenceScanner()
				.referencesOf(classes.resolve("com/example/Fixture.class"));
	}

	/**
	 * Builds a class that holds the shapes a compiler never writes: a
	 * signature that names a nested type the specification way, and
	 * constants that carry a class, a method type, a method handle
	 * and a dynamic constant.
	 *
	 * @return the class file bytes
	 */
	private static byte[] synthesized() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Synthesized", null,
				"java/lang/Object", null);
		FieldVisitor field = writer.visitField(Opcodes.ACC_PUBLIC, "dotted", "Ljava/lang/Object;",
				"Lcom/example/Outer<TT;>.Inner;", null);
		field.visitEnd();
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"constants", "()V", null, null);
		method.visitCode();
		method.visitLdcInsn(Type.getType("Lcom/example/Target;"));
		method.visitLdcInsn(Type.getMethodType("(Lcom/example/Target;)Lcom/example/Target;"));
		method.visitLdcInsn(new Handle(Opcodes.H_INVOKESTATIC, "com/example/Target", "call", "()V", false));
		method.visitLdcInsn(new ConstantDynamic("value", "Lcom/example/Target;",
				new Handle(Opcodes.H_INVOKESTATIC, "com/example/Target", "bootstrap",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)"
								+ "Ljava/lang/Object;", false),
				"argument"));
		method.visitInsn(Opcodes.RETURN);
		method.visitMaxs(0, 0);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
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
