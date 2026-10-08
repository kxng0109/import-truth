package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.LibraryIndexer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Suggests dependency names for wanted imports: one shared resolve
 * plus index pass per batch, case insensitive substring candidates,
 * ranked exact first. Database names only; the JDK stays exact only
 * in the lookup path.
 */
public final class SuggestService {

	private final JarIndexStore store;
	private final LibraryIndexer indexer;
	private final DependencyResolver resolver;

	/**
	 * Creates the service.
	 *
	 * @param store index store, never null
	 * @param indexer library extractor, never null
	 * @param resolver dependency resolver, never null
	 * @throws NullPointerException when any argument is {@code null}
	 */
	public SuggestService(JarIndexStore store, LibraryIndexer indexer, DependencyResolver resolver) {
		this.store = Objects.requireNonNull(store, "store");
		this.indexer = Objects.requireNonNull(indexer, "indexer");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
	}

	/**
	 * Suggests names for one wanted import.
	 *
	 * @param projectDir project root, never null
	 * @param name wanted fully qualified name, never null
	 * @param limit maximum names, positive
	 * @return best names first, never null
	 * @throws IOException when resolution or indexing fails
	 */
	public List<String> suggest(Path projectDir, String name, int limit) throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(name, "name");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		List<Path> dbs = CheckOrchestrator.indexAll(store, indexer, resolver.resolve(projectDir, false));
		return suggestionsFor(dbs, name, limit);
	}

	/**
	 * Suggests names for many wanted imports with one shared resolve
	 * plus index pass.
	 *
	 * @param projectDir project root, never null
	 * @param names wanted fully qualified names, never null
	 * @param limit maximum names per input, positive
	 * @return suggestions per input in order, never null
	 * @throws IOException when resolution or indexing fails
	 */
	public Map<String, List<String>> suggestBatch(Path projectDir, List<String> names, int limit)
			throws IOException {
		Objects.requireNonNull(projectDir, "projectDir");
		Objects.requireNonNull(names, "names");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		List<Path> dbs = CheckOrchestrator.indexAll(store, indexer, resolver.resolve(projectDir, false));
		Map<String, List<String>> answers = new LinkedHashMap<>();
		for (String name : names) {
			answers.put(name, suggestionsFor(dbs, name, limit));
		}
		return answers;
	}

	private List<String> suggestionsFor(List<Path> dbs, String name, int limit) throws IOException {
		if (name == null || name.isBlank()) {
			return List.of();
		}
		String plain = LookupService.plainSymbol(name);
		if (plain.isBlank()) {
			return List.of();
		}
		return Suggestions.collectInsensitive(store, dbs, plain, 20, limit);
	}
}
