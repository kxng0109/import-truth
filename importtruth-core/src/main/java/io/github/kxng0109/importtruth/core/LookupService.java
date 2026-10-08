package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.Confidence;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.LookupResult;
import io.github.kxng0109.importtruth.model.Symbol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Answers whether a symbol resolves on a project's classpath: indexed
 * dependencies first, the running JDK second, nearest names last.
 */
public final class LookupService {

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
	public LookupService(JarIndexStore store, LibraryIndexer indexer, DependencyResolver resolver, JdkIndex jdk) {
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
		String name = plainSymbol(symbol);
		if (name.isBlank()) {
			return new LookupResult(false, Confidence.CANDIDATE, List.of(), List.of(), false);
		}
		List<Path> jars = resolver.resolve(projectDir, false);
		List<Path> dbs = CheckOrchestrator.indexAll(store, indexer, jars);
		List<Symbol> matches = store.findAcross(dbs, name);
		if (!matches.isEmpty()) {
			return new LookupResult(true, Confidence.DEFINITE, matches, List.of(), false);
		}
		int target = JdkTarget.of(projectDir).orElse(-1);
		if (target >= 0 ? jdk.existsIn(name, target) : jdk.exists(name)) {
			return new LookupResult(true, Confidence.DEFINITE, List.of(), List.of(), true);
		}
		return new LookupResult(false, Confidence.CANDIDATE, List.of(), suggestions(dbs, name), false);
	}

	/**
	 * Strips type arguments and array suffixes so lookups accept
	 * source spellings like {@code Map<String, List<String>>}.
	 *
	 * @param symbol raw symbol, never null
	 * @return plain name, never null
	 */
	static String plainSymbol(String symbol) {
		String compact = symbol.replaceAll("\\s+", "");
		StringBuilder kept = new StringBuilder();
		int depth = 0;
		for (int i = 0; i < compact.length(); i++) {
			char current = compact.charAt(i);
			if (current == '<') {
				depth++;
			} else if (current == '>') {
				depth = Math.max(0, depth - 1);
			} else if (depth == 0) {
				kept.append(current);
			}
		}
		String plain = kept.toString();
		while (plain.endsWith("[]")) {
			plain = plain.substring(0, plain.length() - 2);
		}
		return plain;
	}

	private List<String> suggestions(List<Path> dbs, String symbol) throws IOException {
		return Suggestions.collect(store, dbs, symbol, 20, 5);
	}
}
