package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Searches indexed dependency names by substring.
 */
public final class SearchService {

	private final JarIndexStore store;
	private final LibraryIndexer indexer;
	private final MavenResolver resolver;

	/**
	 * Creates the service.
	 *
	 * @param store    index store, never null
	 * @param indexer  library extractor, never null
	 * @param resolver dependency resolver, never null
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public SearchService(JarIndexStore store, LibraryIndexer indexer, MavenResolver resolver) {
		this.store = Objects.requireNonNull(store, "store");
		this.indexer = Objects.requireNonNull(indexer, "indexer");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
	}

	/**
	 * Searches dependency names for the substring.
	 *
	 * @param projectDir project root, never null
	 * @param query      substring, never null
	 * @param limit      maximum rows, positive
	 * @return matches, never null
	 * @throws IOException when resolution or indexing fails
	 */
	public List<Symbol> search(Path projectDir, String query, int limit) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(query, "query");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		List<Symbol> matches = new ArrayList<>();
		for (Path jar : resolver.resolve(projectDir, false)) {
			Path db = store.ensureIndexed(jar, indexer);
			matches.addAll(store.searchIn(db, query, limit));
			if (matches.size() >= limit) {
				return matches.subList(0, limit);
			}
		}
		return matches;
	}
}
