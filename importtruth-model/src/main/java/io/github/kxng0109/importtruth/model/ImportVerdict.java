package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * Whether one import resolved, before policy applies.
 *
 * @param target   dotted name without trailing wildcard, never blank
 * @param line     one-based line number, positive
 * @param resolved true when the name exists somewhere checked
 */
public record ImportVerdict(String target, long line, boolean resolved) {

	/**
	 * Creates the verdict.
	 *
	 * @throws NullPointerException if {@code target} is {@code null}
	 * @throws IllegalArgumentException if {@code target} is blank or {@code line} is not positive
	 */
	public ImportVerdict {
		Objects.requireNonNull(target, "target");
		if (target.isBlank() || line <= 0) {
			throw new IllegalArgumentException("target must not be blank and line must be positive");
		}
	}
}
