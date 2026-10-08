package io.github.kxng0109.importtruth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.CheckResult;
import io.github.kxng0109.importtruth.model.Finding;
import io.github.kxng0109.importtruth.model.FindingKind;
import io.github.kxng0109.importtruth.model.ImportVerdict;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.PolicyHit;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies orchestrator scope keys and policy application.
 */
@DisplayName("CheckOrchestrator")
final class CheckOrchestratorTest {

	@TempDir
	private Path files;

	private final List<JarIndexStore> openStores = new ArrayList<>();

	@AfterEach
	void closeStores() {
		for (JarIndexStore store : openStores) {
			try {
				store.close();
			} catch (Exception ignored) {
				// Best effort: temp cleanup reclaims the rest.
			}
		}
		openStores.clear();
	}

	@Test
	@DisplayName("keys scopes and applies policy hits")
	@SuppressWarnings("DataFlowIssue")
	void keysAndApplies() throws Exception {
		Path jar = files.resolve("a.jar");
		Files.write(jar, "bytes".getBytes(StandardCharsets.UTF_8));
		Path db = files.resolve("a.mv.db");
		Files.write(db, "index".getBytes(StandardCharsets.UTF_8));

		String key = CheckOrchestrator.scopeKey(List.of(jar), List.of(db), Set.of("com.example"));
		String changed = CheckOrchestrator.scopeKey(List.of(jar), List.of(db), Set.of("com.other"));

		assertThat(key).as("key mentions jar").contains("a.jar");
		assertThat(changed).as("own packages affect key").isNotEqualTo(key);

		CheckResult result = new CheckResult(true,
				List.of(new Finding("A.java", 1, FindingKind.MISSING, "import a.B resolves nowhere", "")),
				List.of(new ImportVerdict("com.example.Old", 2, true),
						new ImportVerdict("com.example.Gone", 3, false)));
		List<Finding> findings = CheckOrchestrator.applyPolicy(result,
				target -> target.equals("com.example.Old")
						? Optional.of(new PolicyHit("renamed", "use com.example.New"))
						: Optional.empty(),
				"A.java");

		assertThat(findings).as("missing plus one policy").hasSize(2);
		assertThat(findings.get(1).kind()).as("policy kind").isEqualTo(FindingKind.POLICY);
		assertThatThrownBy(() -> CheckOrchestrator.applyPolicy(null, target -> Optional.empty(), "A.java"))
				.as("null result").isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("indexes jars and resolves types and packages")
	void indexesAndResolves() throws Exception {
		Path jar = files.resolve("dep.jar");
		try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/"));
			zip.closeEntry();
		}
		JarIndexStore store = new JarIndexStore(files.resolve("index"));
		openStores.add(store);
		LibraryIndexer indexer = jarFile -> List.of(
				new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, false, "", false));
		JdkIndex jdk = new JdkIndex();

		List<Path> dbs = CheckOrchestrator.indexAll(store, indexer, List.of(jar));

		assertThat(dbs).as("one index").hasSize(1);
		assertThat(CheckOrchestrator.typeResolves(store, jdk, dbs, "com.example.Widget"))
				.as("indexed type resolves").isTrue();
		assertThat(CheckOrchestrator.typeResolves(store, jdk, dbs, "java.util.List"))
				.as("JDK type resolves").isTrue();
		assertThat(CheckOrchestrator.typeResolves(store, jdk, dbs, "com.example.Missing"))
				.as("absent type misses").isFalse();
		assertThat(CheckOrchestrator.packageResolves(store, jdk, dbs, Set.of("com.acme"), "com.acme"))
				.as("own package resolves").isTrue();
		assertThat(CheckOrchestrator.packageResolves(store, jdk, dbs, Set.of(), "com.example"))
				.as("indexed package resolves").isTrue();
		assertThat(CheckOrchestrator.packageResolves(store, jdk, dbs, Set.of(), "java.util"))
				.as("JDK package resolves").isTrue();
		assertThat(CheckOrchestrator.packageResolves(store, jdk, dbs, Set.of(), "com.example.missing"))
				.as("absent package misses").isFalse();
	}

	@Test
	@DisplayName("returns false when indexes fail")
	void toleratesIndexFailures() throws Exception {
		Path missing = files.resolve("ghost.mv.db");
		JarIndexStore store = new JarIndexStore(files.resolve("broken-index"));
		openStores.add(store);
		JdkIndex jdk = new JdkIndex();

		assertThat(CheckOrchestrator.typeResolves(store, jdk, List.of(missing), "com.example.Widget"))
				.as("broken type query tolerated").isFalse();
		assertThat(CheckOrchestrator.packageResolves(store, jdk, List.of(missing), Set.of(), "com.example"))
				.as("broken package query tolerated").isFalse();
	}

	@Test
	@DisplayName("rejects null orchestration inputs")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullInputs() {
		assertThatThrownBy(() -> CheckOrchestrator.indexAll(null, jar -> List.of(), List.of()))
				.as("null store").isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> CheckOrchestrator.scopeKey(null, List.of(), Set.of()))
				.as("null jars").isInstanceOf(NullPointerException.class);
	}
}
