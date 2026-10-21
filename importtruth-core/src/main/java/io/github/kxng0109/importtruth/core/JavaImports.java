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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
 * A batch is parsed by one compiler task, so the compiler is set up once
 * for the whole batch instead of once per file.
 */
public final class JavaImports {

	/**
	 * Parsed scans by file plus the exact content parsed. Bounded least
	 * recently used retention: file sweeps evict eldest entries instead
	 * of pinning every source text for the process lifetime.
	 */
	private static final int MAX_FILES = 256;
	private static final Map<Path, Entry> CACHE =
			Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Path, JavaImports.Entry> eldest) {
					return size() > MAX_FILES;
				}
			});

	/**
	 * Counts the compiler tasks created. Not final: a test reads it to
	 * prove a batch costs one task and not one per file.
	 */
	static final AtomicInteger TASKS = new AtomicInteger();

	/**
	 * Counts the file managers created. Not final: a test reads it to
	 * prove a batch costs one manager and not one per file.
	 */
	static final AtomicInteger MANAGERS = new AtomicInteger();

	/**
	 * Makes the batch treat an error as belonging to no file. Not
	 * final: a test reaches the path the safety net takes.
	 */
	static boolean strayErrors = false;

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

	/**
	 * Parses a whole batch with one file manager and one compiler
	 * task. Every file gets the answer the single file path gives it,
	 * health flag included: an error in one file never marks another
	 * one, and a file that cannot be read or parsed is answered on its
	 * own without costing the others their result.
	 *
	 * @param files source files, never null
	 * @return scans per file, in request order, never null
	 * @throws NullPointerException if {@code files} or any file is {@code null}
	 */
	public static Map<Path, ImportScan> ofAll(List<Path> files) {
		Objects.requireNonNull(files, "files");
		Map<Path, ImportScan> answered = new LinkedHashMap<>();
		List<Path> pending = new ArrayList<>();
		List<String> contents = new ArrayList<>();
		for (Path file : files) {
			Objects.requireNonNull(file, "file");
			Path key = file.toAbsolutePath().normalize();
			String content;
			try {
				content = Files.readString(file, StandardCharsets.UTF_8);
			} catch (IOException | RuntimeException failed) {
				answered.put(file, new ImportScan(false, List.of()));
				continue;
			}
			Entry remembered = CACHE.get(key);
			if (remembered != null && remembered.content().equals(content)) {
				answered.put(file, remembered.scan());
				continue;
			}
			pending.add(file);
			contents.add(content);
		}
		if (!pending.isEmpty()) {
			Map<Path, ImportScan> parsed;
			try {
				parsed = parseAll(pending, contents);
			} catch (IOException | RuntimeException failed) {
				parsed = null;
			}
			if (parsed == null) {
				// The batch could not answer, so every file is asked
				// on its own, the way the single file path asks.
				for (Path file : pending) {
					answered.put(file, of(file));
				}
			} else {
				answered.putAll(parsed);
			}
			for (int i = 0; i < pending.size(); i++) {
				Path file = pending.get(i);
				CACHE.put(file.toAbsolutePath().normalize(),
						new Entry(contents.get(i), answered.get(file)));
			}
		}
		Map<Path, ImportScan> ordered = new LinkedHashMap<>();
		for (Path file : files) {
			ordered.put(file, answered.getOrDefault(file, new ImportScan(false, List.of())));
		}
		return ordered;
	}

	private static ImportScan parse(String unitName, String content) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			return new ImportScan(false, List.of());
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		MANAGERS.incrementAndGet();
		try (StandardJavaFileManager manager =
				compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			JavaFileObject unit = new SimpleJavaFileObject(
					URI.create("string:///" + unitName), JavaFileObject.Kind.SOURCE) {
				@Override
				public CharSequence getCharContent(boolean ignoreEncodingErrors) {
					return content;
				}
			};
			TASKS.incrementAndGet();
			JavacTask task = (JavacTask) compiler.getTask(
					null, manager, diagnostics, List.of("-proc:none"), null, List.of(unit));
			SourcePositions positions = Trees.instance(task).getSourcePositions();
			List<ImportRef> imports = new ArrayList<>();
			for (CompilationUnitTree tree : task.parse()) {
				imports.addAll(importsOf(tree, positions));
			}
			return new ImportScan(healthy(diagnostics), imports);
		}
	}

	/**
	 * Parses the pending files with one file manager and one task.
	 * The health flag of a file is the one the single file path gives
	 * it, so a batch and a per file parse agree file for file.
	 *
	 * @param files    source files, never null
	 * @param contents file contents, parallel to the files, never null
	 * @return scans per file, or null when the batch could not answer
	 * @throws IOException when the compiler task fails
	 */
	private static Map<Path, ImportScan> parseAll(List<Path> files, List<String> contents)
			throws IOException {
		Map<Path, ImportScan> scans = new LinkedHashMap<>();
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		MANAGERS.incrementAndGet();
		Map<JavaFileObject, Path> owners = new IdentityHashMap<>();
		Map<String, Path> byName = new HashMap<>();
		List<JavaFileObject> units = new ArrayList<>();
		List<Path> parsed = new ArrayList<>();
		for (int i = 0; i < files.size(); i++) {
			JavaFileObject unit;
			try {
				unit = unit(files.get(i), contents.get(i));
			} catch (RuntimeException rejected) {
				// The single file path rejects the same file the same
				// way: a name the URI cannot carry is not a parse.
				scans.put(files.get(i), new ImportScan(false, List.of()));
				continue;
			}
			units.add(unit);
			parsed.add(files.get(i));
			owners.put(unit, files.get(i));
			byName.put(unit.getName(), files.get(i));
		}
		if (units.isEmpty()) {
			return scans;
		}
		try (StandardJavaFileManager manager =
				compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			TASKS.incrementAndGet();
			JavacTask task = (JavacTask) compiler.getTask(
					null, manager, diagnostics, List.of("-proc:none"), null, units);
			SourcePositions positions = Trees.instance(task).getSourcePositions();
			List<CompilationUnitTree> trees = new ArrayList<>();
			for (CompilationUnitTree tree : task.parse()) {
				trees.add(tree);
			}
			Set<Path> broken = new HashSet<>();
			boolean unattributed = strayErrors;
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
				if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
					continue;
				}
				Path owner = owner(diagnostic.getSource(), owners, byName);
				if (owner == null) {
					unattributed = true;
					continue;
				}
				broken.add(owner);
			}
			if (unattributed) {
				// An error that belongs to no file would mark every
				// file unhealthy, and an unhealthy file answers
				// nothing. The single file path knows the guilty
				// file, so the caller asks it.
				return null;
			}
			for (int i = 0; i < trees.size(); i++) {
				Path file = owner(trees.get(i).getSourceFile(), owners, byName);
				if (file == null && i < parsed.size()) {
					file = parsed.get(i);
				}
				if (file == null) {
					continue;
				}
				scans.put(file, new ImportScan(!broken.contains(file), importsOf(trees.get(i), positions)));
			}
			for (Path file : parsed) {
				scans.putIfAbsent(file, new ImportScan(true, List.of()));
			}
			return scans;
		}
	}

	/**
	 * Reads the imports of one compilation unit.
	 *
	 * @param tree      parsed unit, never null
	 * @param positions source positions of the task, never null
	 * @return imports in source order, never null
	 */
	private static List<ImportRef> importsOf(CompilationUnitTree tree, SourcePositions positions) {
		List<ImportRef> imports = new ArrayList<>();
		for (ImportTree importTree : tree.getImports()) {
			String name = importTree.getQualifiedIdentifier().toString();
			boolean wildcard = name.endsWith(".*");
			String target = wildcard ? name.substring(0, name.length() - 2) : name;
			long line = tree.getLineMap().getLineNumber(positions.getStartPosition(tree, importTree));
			imports.add(new ImportRef(target, importTree.isStatic(), wildcard, Math.max(line, 1)));
		}
		return imports;
	}

	/**
	 * Builds the compiler unit of one file. The URI carries the whole
	 * path, so two files of the same name in one batch stay apart.
	 *
	 * @param file    source file, never null
	 * @param content file content, never null
	 * @return the compiler unit, never null
	 */
	private static JavaFileObject unit(Path file, String content) {
		String text = file.toAbsolutePath().toString().replace('\\', '/');
		URI uri = URI.create("string:///" + text);
		return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return content;
			}
		};
	}

	/**
	 * Finds the file a compiler object belongs to.
	 *
	 * @param source compiler object, may be null
	 * @param owners the units by identity, never null
	 * @param byName  the units by name, never null
	 * @return the file, or null when the object belongs to none
	 */
	private static Path owner(JavaFileObject source, Map<JavaFileObject, Path> owners, Map<String, Path> byName) {
		if (source == null) {
			return null;
		}
		Path owner = owners.get(source);
		return owner != null ? owner : byName.get(source.getName());
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
