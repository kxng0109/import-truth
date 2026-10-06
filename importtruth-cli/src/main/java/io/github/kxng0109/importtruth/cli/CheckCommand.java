package io.github.kxng0109.importtruth.cli;

import io.github.kxng0109.importtruth.core.DependencyResolver;
import io.github.kxng0109.importtruth.core.FileCheckService;
import io.github.kxng0109.importtruth.core.JdkIndex;
import io.github.kxng0109.importtruth.core.ProjectPackages;
import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.ImportVerdict;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.PolicyHit;
import io.github.kxng0109.importtruth.model.PolicyPack;
import io.github.kxng0109.importtruth.model.PolicyRule;
import io.github.kxng0109.importtruth.policy.PackLoader;
import io.github.kxng0109.importtruth.policy.PolicyEngine;
import io.github.kxng0109.importtruth.policy.PolicyValidator;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
		PolicyEngine policy = loadPack(projectDir, err, check);
		CheckResult result = check.check(projectDir, file);
		if (!result.healthy()) {
			return 0;
		}
		List<Finding> findings = new ArrayList<>(result.findings());
		if (policy != null) {
			for (ImportVerdict verdict : result.verdicts()) {
				if (!verdict.resolved()) {
					continue;
				}
				Optional<PolicyHit> hit = policy.evaluate(verdict.target(), true);
				if (hit.isPresent()) {
					findings.add(new Finding(display(projectDir, file), (int) verdict.line(),
							FindingKind.POLICY, hit.get().detail(), hit.get().suggestion()));
				}
			}
		}
		for (Finding finding : findings) {
			StringBuilder line = new StringBuilder(finding.file()).append(':').append(finding.line())
					.append(' ').append(finding.kind().name())
					.append(' ').append(finding.detail());
			if (!finding.suggestion().isEmpty()) {
				line.append(" (").append(finding.suggestion()).append(')');
			}
			out.println(line);
		}
		return findings.stream().anyMatch(f -> f.kind() == FindingKind.MISSING) ? 1 : 0;
	}

	private PolicyEngine loadPack(Path projectDir, PrintStream err, FileCheckService check) throws IOException {
		Path override = projectDir.resolve(".importtruth.yml");
		PolicyPack pack;
		if (Files.exists(override)) {
			try (InputStream in = Files.newInputStream(override)) {
				pack = PackLoader.load("project", in);
			}
		} else {
			try (InputStream in = CheckCommand.class.getResourceAsStream("/packs/jackson3.yaml")) {
				pack = PackLoader.load("jackson3", in);
			}
		}
		List<Path> dbs = new ArrayList<>();
		for (Path jar : resolver.resolve(projectDir, false)) {
			dbs.add(store.ensureIndexed(jar, indexer));
		}
		Set<String> own = ProjectPackages.of(projectDir);
		List<PolicyRule> active = PolicyValidator.activeRules(
				pack,
				name -> packageResolves(dbs, own, name),
				name -> typeResolves(dbs, name));
		if (active.size() != pack.rules().size()) {
			err.println("policy: " + (pack.rules().size() - active.size()) + " rule(s) disabled, targets missing");
		}
		return new PolicyEngine(new PolicyPack(pack.name(), active));
	}

	private boolean typeResolves(List<Path> dbs, String name) {
		try {
			return !store.findAcross(dbs, name).isEmpty() || jdk.exists(name);
		} catch (IOException failed) {
			return false;
		}
	}

	private boolean packageResolves(List<Path> dbs, Set<String> own, String name) {
		for (String pkg : own) {
			if (pkg.equals(name) || pkg.startsWith(name + ".")) {
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
		return jdk.packageExists(name);
	}

	private static String display(Path projectDir, Path file) {
		try {
			return projectDir.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
		} catch (IllegalArgumentException notRelative) {
			return file.getFileName().toString();
		}
	}
}
