package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.core.JavaImports.ImportRef;
import io.github.kxng0109.importtruth.core.JavaImports.ImportScan;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.ImportVerdict;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Packages;
import io.github.kxng0109.importtruth.model.Symbol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Checks one source file's imports against the resolved classpath, the JDK,
 * and the project's own packages. Existence first: a name that resolves
 * nowhere project-owned is a miss; anything else is at most a candidate.
 * Silent on success: clean files yield no findings.
 */
public final class FileCheckService {

	private final JarIndexStore store;
	private final LibraryIndexer indexer;
	private final DependencyResolver resolver;
	private final JdkIndex jdk;

	/**
	 * Creates the service.
	 *
	 * @param store    index store, never null
	 * @param indexer  library extractor, never null
	 * @param resolver dependency resolver, never null
	 * @param jdk      JDK index, never null
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public FileCheckService(JarIndexStore store, LibraryIndexer indexer, DependencyResolver resolver, JdkIndex jdk) {
		this.store = Objects.requireNonNull(store, "store");
		this.indexer = Objects.requireNonNull(indexer, "indexer");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.jdk = Objects.requireNonNull(jdk, "jdk");
	}

	/**
	 * Checks the file.
	 *
	 * @param projectDir project root, never null
	 * @param file       source file, never null
	 * @return the outcome, never null
	 * @throws IOException when resolution or indexing fails
	 */
	public CheckResult check(Path projectDir, Path file) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(file, "file");
		List<Path> jars = resolver.resolve(projectDir, false);
		List<Path> dbs = CheckOrchestrator.indexAll(store, indexer, jars);
		Set<String> own = ProjectPackages.of(projectDir);
		return checkWith(projectDir, file, dbs, own);
	}

	/**
	 * Checks one file against already-resolved indexes.
	 *
	 * @param projectDir project root, never null
	 * @param file       source file, never null
	 * @param dbs        index files, never null
	 * @param own        own-project packages, never null
	 * @return the outcome, never null
	 * @throws IOException when index reads fail
	 */
	public CheckResult checkWith(Path projectDir, Path file, List<Path> dbs, Set<String> own)
			throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(dbs, "dbs");
		Objects.requireNonNull(own, "own");
		ImportScan scan = JavaImports.of(file);
		if (!scan.healthy()) {
			return new CheckResult(false, List.of(), List.of());
		}
		String display = DisplayNames.relativizeOrFileName(projectDir, file);
		List<Finding> findings = new ArrayList<>();
		List<ImportVerdict> verdicts = new ArrayList<>();
		for (ImportRef ref : scan.imports()) {
			checkImport(dbs, own, display, ref, verdicts).ifPresent(findings::add);
		}
		return new CheckResult(true, findings, verdicts);
	}

	private Optional<Finding> checkImport(
			List<Path> dbs, Set<String> own, String display, ImportRef ref, List<ImportVerdict> verdicts)
			throws IOException {
		if (ref.wildcard()) {
			return checkWildcard(dbs, own, display, ref, verdicts);
		}
		if (!store.findAcross(dbs, ref.target()).isEmpty() || jdk.exists(ref.target())) {
			verdicts.add(new ImportVerdict(ref.target(), ref.line(), true));
			return Optional.empty();
		}
		verdicts.add(new ImportVerdict(ref.target(), ref.line(), false));
		if (isOwnOrGenerated(own, ref.target())) {
			return Optional.of(candidate(display, ref, "project-owned or generated, unverified"));
		}
		return Optional.of(
				new Finding(display, (int) ref.line(), FindingKind.MISSING,
						"import " + ref.target() + " resolves nowhere", suggestion(dbs, ref.target())));
	}

	private Optional<Finding> checkWildcard(
			List<Path> dbs, Set<String> own, String display, ImportRef ref, List<ImportVerdict> verdicts)
			throws IOException {
		if (packageExists(dbs, own, ref.target())) {
			verdicts.add(new ImportVerdict(ref.target(), ref.line(), true));
			return Optional.empty();
		}
		verdicts.add(new ImportVerdict(ref.target(), ref.line(), false));
		if (isOwnOrGenerated(own, ref.target())) {
			return Optional.of(candidate(display, ref, "project-owned or generated, unverified"));
		}
		return Optional.of(
				new Finding(display, (int) ref.line(), FindingKind.MISSING,
						"package " + ref.target() + " resolves nowhere", suggestion(dbs, ref.target())));
	}

	private boolean packageExists(List<Path> dbs, Set<String> own, String name) throws IOException {
		for (String pkg : own) {
			if (Packages.coveredBy(name, pkg)) {
				return true;
			}
		}
		for (Path db : dbs) {
			if (!store.searchIn(db, name + ".", 1).isEmpty()) {
				return true;
			}
		}
		return jdk.packageExists(name);
	}

	private static boolean isOwnOrGenerated(Set<String> own, String name) {
		for (String pkg : own) {
			if (Packages.contains(pkg, name)) {
				return true;
			}
		}
		return false;
	}

	private static Finding candidate(String display, ImportRef ref, String detail) {
		return new Finding(display, (int) ref.line(), FindingKind.CANDIDATE,
				"import " + ref.target() + " " + detail, "");
	}

	private String suggestion(List<Path> dbs, String name) throws IOException {
		List<String> ranked = Suggestions.collect(store, dbs, name, 20, 3);
		return ranked.isEmpty() ? "" : "maybe: " + String.join(", ", ranked);
	}
}
