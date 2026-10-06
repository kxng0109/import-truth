package io.github.kxng0109.importtruth.core;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Reads import declarations with the JDK's own parser: immune to comments,
 * string literals, and shared-line imports by construction.
 */
public final class JavaImports {

	private JavaImports() {
	}

	/**
	 * Parses the file's imports.
	 *
	 * @param file source file, never null
	 * @return imports plus a health flag, never null
	 * @throws NullPointerException if {@code file} is {@code null}
	 */
	public static ImportScan of(Path file) {
		Objects.requireNonNull(file, "file");
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			return new ImportScan(false, List.of());
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager manager =
				compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> units = manager.getJavaFileObjects(file.toFile());
			JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, units);
			SourcePositions positions = Trees.instance(task).getSourcePositions();
			List<ImportRef> imports = new ArrayList<>();
			for (CompilationUnitTree tree : task.parse()) {
				for (ImportTree importTree : tree.getImports()) {
					String name = importTree.getQualifiedIdentifier().toString();
					boolean wildcard = name.endsWith(".*");
					String target = wildcard ? name.substring(0, name.length() - 2) : name;
					long line = tree.getLineMap().getLineNumber(positions.getStartPosition(tree, importTree));
					imports.add(new ImportRef(target, importTree.isStatic(), wildcard, Math.max(line, 1)));
				}
			}
			return new ImportScan(healthy(diagnostics), imports);
		} catch (Exception failed) {
			return new ImportScan(false, List.of());
		}
	}

	private static boolean healthy(DiagnosticCollector<JavaFileObject> diagnostics) {
		return diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
	}

	/**
	 * One import declaration.
	 *
	 * @param target   dotted name without trailing wildcard, never blank
	 * @param isStatic true for static imports
	 * @param wildcard true for on-demand imports
	 * @param line     one-based line number, positive
	 */
	public record ImportRef(String target, boolean isStatic, boolean wildcard, long line) {

		/**
		 * Creates the reference.
		 *
		 * @throws NullPointerException if {@code target} is {@code null}
		 * @throws IllegalArgumentException if {@code target} is blank or {@code line} is not positive
		 */
		public ImportRef {
			Objects.requireNonNull(target, "target");
			if (target.isBlank() || line <= 0) {
				throw new IllegalArgumentException("target must not be blank and line must be positive");
			}
		}
	}

	/**
	 * Parse outcome.
	 *
	 * @param healthy false when the file has errors; imports are partial then
	 * @param imports declarations found, never null
	 */
	public record ImportScan(boolean healthy, List<ImportRef> imports) {

		/**
		 * Creates the scan.
		 *
		 * @throws NullPointerException if {@code imports} is {@code null}
		 */
		public ImportScan {
			Objects.requireNonNull(imports, "imports");
		}
	}
}
