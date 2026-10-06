package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * One fired policy rule, before file position is attached.
 *
 * @param detail     what is discouraged, never blank
 * @param suggestion replacement or hint, empty when unknown
 */
public record PolicyHit(String detail, String suggestion) {

	/**
	 * Creates the hit.
	 *
	 * @throws NullPointerException if any part is {@code null}
	 * @throws IllegalArgumentException if {@code detail} is blank
	 */
	public PolicyHit {
		Objects.requireNonNull(detail, "detail");
		Objects.requireNonNull(suggestion, "suggestion");
		if (detail.isBlank()) {
			throw new IllegalArgumentException("detail must not be blank");
		}
	}
}
