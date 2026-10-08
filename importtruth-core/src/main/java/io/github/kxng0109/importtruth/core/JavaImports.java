package io.github.kxng0109.importtruth.core;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Reads import declarations with the JDK's own parser: immune to comments,
 * string literals, and shared-line imports by construction.
 */
public final class JavaImports {

	/**
	 * Parsed scans by file plus the exact content parsed. Entries are
	 * small source texts, one per distinct file checked in the session.
	 */
	private static final Map<Path, Entry> CACHE = new ConcurrentHashMap<>();

	private JavaImports() {
	}

	/**
	 * Parses the file's imports, reusing the cached scan when the
	 * content is unchanged.
	 *
	 * @param file source file, never null
	 * @return imports plus a health flag, never null
	 * @throws NullPointerException if {@code file} is {@code null}
	 */
	public static ImportScan of(Path file) {
		Objects.requireNonNull(file, "file");
		Path key = file.toAbsolutePath().normalize();
		try {
			String content = Files.readString(file, StandardCharsets.UTF_8);
			Entry remembered = CACHE.get(key);
			if (remembered != null && remembered.content().equals(content)) {
				return remembered.scan();
			}
			ImportScan scan = parse(file.getFileName().toString(), content);
			CACHE.put(key, new Entry(content, scan));
			return scan;
		} catch (IOException | RuntimeException failed) {
			return new ImportScan(false, List.of());
		}
	}

	private static ImportScan parse(String unitName, String content) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			return new ImportScan(false, List.of());
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager manager =
				compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			JavaFileObject unit = new SimpleJavaFileObject(
					URI.create("string:///" + unitName), JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return content;
				}
			};
			JavacTask task = (JavacTask) compiler.getTask(
					null, manager, diagnostics, List.of("-proc:none"), null, List.of(unit));
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
		}
	}

	private static boolean healthy(DiagnosticCollector<JavaFileObject> diagnostics) {
		for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
			if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
				return false;
			}
		}
		return true;
	}

	private record Entry(String content, ImportScan scan) {
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
