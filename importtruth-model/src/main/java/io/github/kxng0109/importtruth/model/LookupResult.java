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
 * @param jdkChecked  true when the JDK API could be consulted. False
 *                    when this build has no JDK API index, as a native
 *                    image does, and a miss is then unverified rather
 *                    than proven missing.
 */
public record LookupResult(
		boolean found,
		Confidence confidence,
		List<Symbol> matches,
		List<String> suggestions,
		boolean fromJdk,
		boolean jdkChecked) {

	/**
	 * Creates the result of a build that could consult the JDK API.
	 *
	 * @param found       true when the symbol resolves somewhere
	 * @param confidence  how much the answer can be trusted, never null
	 * @param matches     resolving symbols, empty when not found
	 * @param suggestions nearest names when not found, empty when found
	 * @param fromJdk     true when the hit came from the running JDK
	 * @throws NullPointerException if {@code confidence}, {@code matches}, or {@code suggestions} is {@code null}
	 */
	public LookupResult(
			boolean found,
			Confidence confidence,
			List<Symbol> matches,
			List<String> suggestions,
			boolean fromJdk) {
		this(found, confidence, matches, suggestions, fromJdk, true);
	}

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
