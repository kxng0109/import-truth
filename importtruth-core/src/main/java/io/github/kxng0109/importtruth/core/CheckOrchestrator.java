package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.index.JarStamp;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.ImportVerdict;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Packages;
import io.github.kxng0109.importtruth.model.PolicyHit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

	private CheckOrchestrator() {
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
			return !store.findAcross(dbs, name).isEmpty() || jdkHit;
		} catch (IOException failed) {
			return false;
		}
	}

	/**
	 * Tests whether a package resolves in own code, indexes, or the target JDK.
	 */
	public static boolean packageResolves(
			JarIndexStore store, JdkIndex jdk, List<Path> dbs, Set<String> own, String name, int target) {
		for (String pkg : own) {
			if (Packages.coveredBy(name, pkg)) {
				return true;
			}
		}
		try {
			for (Path db : dbs) {
				if (!store.searchIn(db, name + ".", 1).isEmpty()) {
					return true;
				}
			}
		} catch (IOException failed) {
			return false;
		}
		return target >= 0 ? jdk.packageExistsIn(name, target) : jdk.packageExists(name);
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
