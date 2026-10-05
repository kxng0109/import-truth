package io.github.kxng0109.importtruth.model;

import java.util.List;
import java.util.Objects;

/**
 * Answer to a symbol lookup.
 *
 * @param found       true when the symbol resolves somewhere
 * @param confidence  how much the answer can be trusted, never null
 * @param matches     resolving symbols, empty when not found
 * @param suggestions nearest names when not found, empty when found
 * @param fromJdk     true when the hit came from the running JDK
 */
public record LookupResult(
		boolean found,
		Confidence confidence,
		List<Symbol> matches,
		List<String> suggestions,
		boolean fromJdk) {

	/**
	 * Creates the result.
	 *
	 * @throws NullPointerException if {@code confidence}, {@code matches}, or {@code suggestions} is {@code null}
	 */
	public LookupResult {
		Objects.requireNonNull(confidence, "confidence");
		Objects.requireNonNull(matches, "matches");
		Objects.requireNonNull(suggestions, "suggestions");
	}
}
