package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.index.JarIndexStore;
import io.github.kxng0109.importtruth.model.Packages;
import io.github.kxng0109.importtruth.model.Symbol;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ranks candidate names for a wanted import. Exact simple names first,
 * same-package names second, small typos third, everything else after.
 * Pure string work: no I/O, deterministic, allocation-light.
 */
public final class Suggestions {

	private Suggestions() {
	}

	/**
	 * Collects substring candidates across indexes and ranks them.
	 *
	 * @param store      index store, never null
	 * @param dbs        index files, never null
	 * @param want       wanted fully qualified name, never null
	 * @param collected  maximum candidates gathered, positive
	 * @param limit      maximum names returned, positive
	 * @return best names first, never null
	 * @throws IOException when index reads fail
	 */
	public static List<String> collect(
			JarIndexStore store, List<Path> dbs, String want, int collected, int limit) throws IOException {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(dbs, "dbs");
		Objects.requireNonNull(want, "want");
		if (collected <= 0 || limit <= 0) {
			throw new IllegalArgumentException("collected and limit must be positive");
		}
		String simple = Packages.simpleName(want);
		Set<String> names = new LinkedHashSet<>();
		for (Path db : dbs) {
			for (Symbol match : store.searchIn(db, simple, collected)) {
				names.add(match.fqn());
				if (names.size() >= collected) {
					break;
				}
			}
			if (names.size() >= collected) {
				break;
			}
		}
		return rank(want, names, limit);
	}

	/**
	 * Collects case insensitive substring candidates across indexes
	 * and ranks them.
	 *
	 * @param store index store, never null
	 * @param dbs index files, never null
	 * @param want wanted fully qualified name, never null
	 * @param collected maximum candidates gathered, positive
	 * @param limit maximum names returned, positive
	 * @return best names first, never null
	 * @throws IOException when index reads fail
	 */
	public static List<String> collectInsensitive(
			JarIndexStore store, List<Path> dbs, String want, int collected, int limit) throws IOException {
		Objects.requireNonNull(store, "store");
		Objects.requireNonNull(dbs, "dbs");
		Objects.requireNonNull(want, "want");
		if (collected <= 0 || limit <= 0) {
			throw new IllegalArgumentException("collected and limit must be positive");
		}
		String simple = Packages.simpleName(want);
		Set<String> names = new LinkedHashSet<>();
		for (Path db : dbs) {
			for (Symbol match : store.searchInInsensitive(db, simple, collected)) {
				names.add(match.fqn());
				if (names.size() >= collected) {
					break;
				}
			}
			if (names.size() >= collected) {
				break;
			}
		}
		return rank(want, names, limit);
	}

	/**
	 * Orders candidates best-first, capped to {@code limit}.
	 *
	 * @param want       wanted fully qualified name, never null
	 * @param candidates fully qualified candidates, never null
	 * @param limit      maximum names, positive
	 * @return best names first, never null
	 * @throws NullPointerException if any argument is {@code null}
	 * @throws IllegalArgumentException if {@code limit} is not positive
	 */
	public static List<String> rank(String want, Iterable<String> candidates, int limit) {
		Objects.requireNonNull(want, "want");
		Objects.requireNonNull(candidates, "candidates");
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		String wantSimple = Packages.simpleName(want);
		String wantPackage = Packages.packageName(want);
		Set<String> seen = new LinkedHashSet<>();
		List<String> exact = new ArrayList<>();
		List<String> packaged = new ArrayList<>();
		List<String> tail = new ArrayList<>();
		List<Scored> fuzzy = new ArrayList<>();
		for (String candidate : candidates) {
			if (candidate == null || !seen.add(candidate)) {
				continue;
			}
			String candidateSimple = Packages.simpleName(candidate);
			if (candidateSimple.equals(wantSimple)) {
				exact.add(candidate);
			} else if (!wantPackage.isEmpty() && candidate.startsWith(wantPackage + ".")) {
				packaged.add(candidate);
			} else {
				int scored = distance(wantSimple, candidateSimple, 2);
				if (scored <= 2) {
					fuzzy.add(new Scored(candidate, scored));
				} else {
					tail.add(candidate);
				}
			}
		}
		fuzzy.sort(Comparator.comparingInt(Scored::distance).thenComparing(Scored::name));
		List<String> ranked = new ArrayList<>(exact);
		ranked.addAll(packaged);
		for (Scored scored : fuzzy) {
			ranked.add(scored.name());
		}
		ranked.addAll(tail);
		return ranked.size() <= limit ? List.copyOf(ranked) : List.copyOf(ranked.subList(0, limit));
	}

	/**
	 * Optimal-string-alignment distance with early exit past
	 * {@code threshold}. Transpositions count as one edit.
	 * Rolling rows keep memory linear in the shorter name.
	 */
	static int distance(String first, String second, int threshold) {
		int left = first.length();
		int right = second.length();
		if (Math.abs(left - right) > threshold) {
			return threshold + 1;
		}
		if (right > left) {
			String swapped = first;
			first = second;
			second = swapped;
			int swappedLength = left;
			left = right;
			right = swappedLength;
		}
		int[] twoAgo = new int[right + 1];
		int[] oneAgo = new int[right + 1];
		int[] current = new int[right + 1];
		for (int j = 0; j <= right; j++) {
			oneAgo[j] = j;
		}
		for (int i = 1; i <= left; i++) {
			current[0] = i;
			int rowBest = threshold + 1;
			for (int j = 1; j <= right; j++) {
				int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
				int value = Math.min(
						Math.min(oneAgo[j] + 1, current[j - 1] + 1), oneAgo[j - 1] + cost);
				if (i > 1
						&& j > 1
						&& first.charAt(i - 1) == second.charAt(j - 2)
						&& first.charAt(i - 2) == second.charAt(j - 1)) {
					value = Math.min(value, twoAgo[j - 2] + 1);
				}
				current[j] = value;
				rowBest = Math.min(rowBest, value);
			}
			if (rowBest > threshold) {
				return threshold + 1;
			}
			int[] rotation = twoAgo;
			twoAgo = oneAgo;
			oneAgo = current;
			current = rotation;
		}
		return oneAgo[right];
	}

	private record Scored(String name, int distance) {
	}
}
