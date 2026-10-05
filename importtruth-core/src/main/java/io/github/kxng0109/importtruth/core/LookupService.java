package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.Confidence;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.LookupResult;
import io.github.kxng0109.importtruth.model.Symbol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Answers whether a symbol resolves on a project's classpath: indexed
 * dependencies first, the running JDK second, nearest names last.
 */
public final class LookupService {

	private final JarIndexStore store;
	private final LibraryIndexer indexer;
	private final MavenResolver resolver;
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
	public LookupService(JarIndexStore store, LibraryIndexer indexer, MavenResolver resolver, JdkIndex jdk) {
		this.store = Objects.requireNonNull(store, "store");
		this.indexer = Objects.requireNonNull(indexer, "indexer");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.jdk = Objects.requireNonNull(jdk, "jdk");
	}

	/**
	 * Looks the symbol up on the project's classpath.
	 *
	 * @param projectDir project root, never null
	 * @param symbol     fully qualified name, never null
	 * @return the answer, never null
	 * @throws IOException when resolution or indexing fails
	 */
	public LookupResult lookup(Path projectDir, String symbol) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(symbol, "symbol");
		List<Path> jars = resolver.resolve(projectDir, false);
		List<Path> dbs = new ArrayList<>(jars.size());
		for (Path jar : jars) {
			dbs.add(store.ensureIndexed(jar, indexer));
		}
		List<Symbol> matches = store.findAcross(dbs, symbol);
		if (!matches.isEmpty()) {
			return new LookupResult(true, Confidence.DEFINITE, matches, List.of(), false);
		}
		if (jdk.exists(symbol)) {
			return new LookupResult(true, Confidence.DEFINITE, List.of(), List.of(), true);
		}
		return new LookupResult(false, Confidence.CANDIDATE, List.of(), suggestions(dbs, symbol), false);
	}

	private List<String> suggestions(List<Path> dbs, String symbol) throws IOException {
		String simple = symbol.contains(".") ? symbol.substring(symbol.lastIndexOf('.') + 1) : symbol;
		Set<String> names = new LinkedHashSet<>();
		for (Path db : dbs) {
			for (Symbol match : store.searchIn(db, simple, 5)) {
				names.add(match.fqn());
				if (names.size() >= 5) {
					break;
				}
			}
			if (names.size() >= 5) {
				break;
			}
		}
		return List.copyOf(names);
	}
}
