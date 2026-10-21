package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.index.JarStamp;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.ImportVerdict;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.PolicyHit;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;
import io.github.kxng0109.importtruth.model.TypeNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Shared check orchestration for the CLI and MCP check paths:
 * resolve, index, read packs, validate rules, apply policy hits.
 * Stateless; callers own rendering and exit codes.
 */
public final class CheckOrchestrator {

	/**
	 * Maximum retained scope keys. A session touches few classpaths at
	 * a time, and the keys hold paths, not file contents.
	 */
	static final int MAX_SCOPE_KEYS = 128;

	/**
	 * The memoised scope keys. Least recently used retention keeps the
	 * map bounded.
	 */
	private static final Map<Scope, String> SCOPE_KEYS =
			Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Scope, String> eldest) {
					return size() > MAX_SCOPE_KEYS;
				}
			});

	/**
	 * The number of memoised scope keys. Not final: a test reads it to
	 * prove the cache stays bounded.
	 *
	 * @return the number of retained scope keys
	 */
	static int cachedScopeKeys() {
		return SCOPE_KEYS.size();
	}

	private CheckOrchestrator() {
	}

	/**
	 * The inputs of one scope key. Two scopes with the same inputs
	 * describe the same classpath, the same own packages, the same
	 * build definition, and the same target release.
	 *
	 * @param jars   library files, never null
	 * @param dbs    index files, never null
	 * @param own    own-project packages, never null
	 * @param target target major release
	 * @param build  signature of the build definition, never null
	 */
	private record Scope(List<Path> jars, List<Path> dbs, Set<String> own, int target, String build) {
	}

	/**
	 * Indexes every jar, returning index files in order.
	 *
	 * @param store    index store, never null
	 * @param indexer  library extractor, never null
	 * @param jars     library files, never null
	 * @return index files, never null
	 * @throws IOException when indexing fails
	 */
	public static List<Path> indexAll(JarIndexStore store, LibraryIndexer indexer, List<Path> jars)
			throws IOException {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(indexer, "indexer");
		Objects.requireNonNull(jars, "jars");
		List<Path> dbs = new ArrayList<>(jars.size());
		for (Path jar : jars) {
			dbs.add(store.ensureIndexed(jar, indexer));
		}
		return dbs;
	}

	/**
	 * Keys one validation: jar identities, index files, own packages,
	 * and the running JDK. Any change revalidates.
	 *
	 * <p>The key is memoised. The index files carry the content hash of
	 * the jars they were built from, so an unchanged key means an
	 * unchanged classpath, and a repeated call stamps no jar again.
	 *
	 * @param jars library files, never null
	 * @param dbs  index files, never null
	 * @param own  own-project packages, never null
	 * @return scope key, never null
	 * @throws IOException when jar stats cannot be read
	 */
	public static String scopeKey(List<Path> jars, List<Path> dbs, Set<String> own, int target)
			throws IOException {
		Objects.requireNonNull(jars, "jars");
		Objects.requireNonNull(dbs, "dbs");
		Objects.requireNonNull(own, "own");
		// The own packages carry the project they came from, so the
		// build definition joins the key. A changed build file then
		// revalidates the policy rules even when the classpath did
		// not move.
		String build = ProjectPackages.buildSignature(own);
		Scope scope = new Scope(jars, dbs, own, target, build);
		String remembered = SCOPE_KEYS.get(scope);
		if (remembered != null) {
			return remembered;
		}
		String built = buildScopeKey(jars, dbs, own, target) + "\nbuild=" + build;
		SCOPE_KEYS.put(scope, built);
		return built;
	}

	/**
	 * Builds the scope key of a classpath.
	 *
	 * @param jars library files, never null
	 * @param dbs  index files, never null
	 * @param own  own-project packages, never null
	 * @param target target major release
	 * @return scope key, never null
	 * @throws IOException when jar stats cannot be read
	 */
	private static String buildScopeKey(List<Path> jars, List<Path> dbs, Set<String> own, int target)
			throws IOException {
		StringBuilder key = new StringBuilder();
		for (Path jar : jars) {
			key.append(jar.toAbsolutePath()).append(':').append(JarStamp.key(jar)).append('\n');
		}
		key.append("dbs=");
		for (Path db : dbs) {
			key.append(db.toAbsolutePath()).append(';');
		}
		key.append("\nown=");
		own.stream().sorted().forEach(pkg -> key.append(pkg).append(';'));
		key.append("\njdk=").append(System.getProperty("java.version", ""));
		key.append("\ntarget=").append(target);
		return key.toString();
	}

	/**
	 * Tests whether a type resolves in indexes or the target JDK.
	 */
	public static boolean typeResolves(
			JarIndexStore store, JdkIndex jdk, List<Path> dbs, String name, int target) {
		try {
			boolean jdkHit = target >= 0 ? jdk.existsIn(name, target) : jdk.exists(name);
			return existsSource(store, dbs, name) || jdkHit;
		} catch (IOException failed) {
			return false;
		}
	}

	/**
	 * Finds symbols under both spellings of a nested type name.
	 *
	 * @param store index store, never null
	 * @param dbs   index files, never null
	 * @param name  fully qualified name, never null
	 * @return matches, never null
	 * @throws IOException when index reads fail
	 */
	public static List<Symbol> findBoth(JarIndexStore store, List<Path> dbs, String name) throws IOException {
		List<Symbol> matches = store.findAcross(dbs, name);
		if (!matches.isEmpty()) {
			return matches;
		}
		String alternate = TypeNames.alternate(name);
		return alternate == null ? List.of() : store.findAcross(dbs, alternate);
	}

	/**
	 * Tests whether a name resolves as source code writes it. A
	 * nested type is written {@code Outer.Inner}; the class file
	 * spelling {@code Outer$Inner} is not a legal import, so an
	 * import check must not accept it. A top level class whose own
	 * name holds a dollar sign keeps it and still resolves.
	 *
	 * @param store index store, never null
	 * @param dbs   index files, never null
	 * @param name  fully qualified name, never null
	 * @return true when the name is present
	 * @throws IOException when index reads fail
	 */
	public static boolean existsSource(JarIndexStore store, List<Path> dbs, String name) throws IOException {
		return store.existsAcross(dbs, name);
	}

	/**
	 * Tests whether a package resolves in own code, indexes, or the target JDK.
	 */
	public static boolean packageResolves(
			JarIndexStore store, JdkIndex jdk, List<Path> dbs, Set<String> own, String name, int target) {
		try {
			return typeOrPackageResolves(store, jdk, dbs, own, name, target);
		} catch (IOException failed) {
			return false;
		}
	}

	/**
	 * Tests whether a name can be imported on demand: it must name a
	 * type, or a package that holds one. A member never qualifies.
	 *
	 * @param store index store, never null
	 * @param jdk   JDK index, never null
	 * @param dbs   index files, never null
	 * @param own   own-project packages, never null
	 * @param name  qualified name, never null
	 * @param target target major release
	 * @return true when the name resolves
	 * @throws IOException when index reads fail
	 */
	public static boolean typeOrPackageResolves(
			JarIndexStore store, JdkIndex jdk, List<Path> dbs, Set<String> own, String name, int target)
			throws IOException {
		if (ProjectPackages.covers(own, name)) {
			return true;
		}
		for (Path db : dbs) {
			if (holdsType(store.findIn(db, name))
					|| holdsType(store.searchPrefix(db, name + ".", 1, SymbolKind.types()))) {
				return true;
			}
		}
		return target >= 0 ? jdk.packageExistsIn(name, target) : jdk.packageExists(name);
	}

	/**
	 * Tests whether any match is a type. A member never makes a name
	 * importable on its own.
	 *
	 * @param matches indexed symbols, never null
	 * @return true when at least one match names a type
	 */
	private static boolean holdsType(List<Symbol> matches) {
		for (Symbol match : matches) {
			if (SymbolKind.isType(match.kind())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds policy hits for resolved verdicts.
	 *
	 * @param result      check outcome, never null
	 * @param evaluate    policy lookup by target, never null
	 * @param displayFile display name for new findings, never null
	 * @return combined findings, never null
	 */
	public static List<Finding> applyPolicy(
			CheckResult result, Function<String, Optional<PolicyHit>> evaluate, String displayFile) {
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(evaluate, "evaluate");
		Objects.requireNonNull(displayFile, "displayFile");
		List<Finding> findings = new ArrayList<>(result.findings());
		for (ImportVerdict verdict : result.verdicts()) {
			if (!verdict.resolved()) {
				continue;
			}
			Optional<PolicyHit> hit = evaluate.apply(verdict.target());
			if (hit.isPresent()) {
				findings.add(new Finding(displayFile, (int) verdict.line(),
						FindingKind.POLICY, hit.get().detail(), hit.get().suggestion()));
			}
		}
		return findings;
	}
}
