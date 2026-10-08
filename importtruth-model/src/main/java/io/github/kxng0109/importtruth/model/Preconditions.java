package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * Shared record guards with uniform messages.
 */
public final class Preconditions {

	private Preconditions() {
	}

	/**
	 * Rejects null or blank text.
	 *
	 * @param value value under test, possibly null
	 * @param name  field name for messages, never null
	 * @return the value, never null or blank
	 * @throws NullPointerException if {@code value} is {@code null}
	 * @throws IllegalArgumentException if {@code value} is blank
	 */
	public static String requireNonBlank(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	/**
	 * Rejects non-positive line numbers.
	 *
	 * @param line line number under test
	 * @param name field name for messages, never null
	 * @return the line number, positive
	 * @throws IllegalArgumentException if {@code line} is not positive
	 */
	public static long requirePositive(long line, String name) {
		Objects.requireNonNull(name, "name");
		if (line <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return line;
	}
}
