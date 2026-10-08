package io.github.kxng0109.importtruth.cli;

import io.github.kxng0109.importtruth.core.CheckOrchestrator;
import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.DisplayNames;
import io.github.kxng0109.importtruth.core.FileCheckService;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.ProjectPackages;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.Findings;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;
import io.github.kxng0109.importtruth.policy.PackLoader;
import io.github.kxng0109.importtruth.policy.PolicyCache;
import io.github.kxng0109.importtruth.policy.PolicyEngine;
import io.github.kxng0109.importtruth.policy.PolicyValidator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checks one file: existence verdicts first, policy hits for resolved
 * imports second. Silent on success: clean files print nothing.
 */
public final class CheckCommand {

	private final JarIndexStore store;
	private final LibraryIndexer indexer;
	private final DependencyResolver resolver;
	private final JdkIndex jdk;
	private final PolicyCache engines = new PolicyCache();

	/**
	 * Creates the command.
	 *
	 * @param store    index store, never null
	 * @param indexer  library extractor, never null
	 * @param resolver dependency resolver, never null
	 * @param jdk      JDK index, never null
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public CheckCommand(JarIndexStore store, LibraryIndexer indexer, DependencyResolver resolver, JdkIndex jdk) {
		this.store = Objects.requireNonNull(store, "store");
		this.indexer = Objects.requireNonNull(indexer, "indexer");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.jdk = Objects.requireNonNull(jdk, "jdk");
	}

	/**
	 * Runs the check.
	 *
	 * @param out        findings sink, never null
	 * @param err        diagnostics sink, never null
	 * @param projectDir project root, never null
	 * @param file       source file, never null
	 * @return 1 when a missing import exists, else 0
	 * @throws IOException when resolution, indexing, or pack loading fails
	 */
	public int run(PrintStream out, PrintStream err, Path projectDir, Path file) throws IOException {
		Objects.requireNonNull(out, "out");
		Objects.requireNonNull(err, "err");
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(file, "file");
		FileCheckService check = new FileCheckService(store, indexer, resolver, jdk);
		List<Path> jars = resolver.resolve(projectDir, false);
		List<Path> dbs = CheckOrchestrator.indexAll(store, indexer, jars);
		Set<String> own = ProjectPackages.of(projectDir);
		PolicyEngine policy = loadPack(projectDir, err, jars, dbs, own);
		CheckResult result = check.checkWith(projectDir, file, dbs, own);
		if (!result.healthy()) {
			return 0;
		}
		List<Finding> findings = CheckOrchestrator.applyPolicy(
				result,
				target -> policy.evaluate(target, true),
				DisplayNames.relativizeOrFileName(projectDir, file));
		for (Finding finding : findings) {
			out.println(Findings.format(finding));
		}
		return findings.stream().anyMatch(f -> f.kind() == FindingKind.MISSING) ? 1 : 0;
	}

	private PolicyEngine loadPack(
			Path projectDir, PrintStream err, List<Path> jars, List<Path> dbs, Set<String> own)
			throws IOException {
		PolicyPack pack = PackLoader.loadProjectPack(CheckCommand.class, projectDir);
		return engines.engine(pack, CheckOrchestrator.scopeKey(jars, dbs, own), validated -> {
			List<PolicyRule> active = PolicyValidator.activeRules(
					validated,
					name -> CheckOrchestrator.packageResolves(store, jdk, dbs, own, name),
					name -> CheckOrchestrator.typeResolves(store, jdk, dbs, name));
			if (active.size() != validated.rules().size()) {
				err.println("policy: " + (validated.rules().size() - active.size())
						+ " rule(s) disabled, targets missing");
			}
			return active;
		});
	}
}
