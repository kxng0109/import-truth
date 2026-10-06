package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * One import problem in a source file.
 *
 * @param file       project-relative file path, never blank
 * @param line       one-based line number, positive
 * @param kind       severity, never null
 * @param detail     what is wrong, never blank
 * @param suggestion replacement or hint, empty when unknown
 */
public record Finding(String file, int line, FindingKind kind, String detail, String suggestion) {

	/**
	 * Creates the finding.
	 *
	 * @throws NullPointerException if {@code file}, {@code kind}, {@code detail}, or {@code suggestion} is
	 *                              {@code null}
	 * @throws IllegalArgumentException if {@code file} or {@code detail} is blank, or {@code line} is not positive
	 */
	public Finding {
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(detail, "detail");
		Objects.requireNonNull(suggestion, "suggestion");
		if (file.isBlank() || detail.isBlank() || line <= 0) {
			throw new IllegalArgumentException("file, detail must not be blank and line must be positive");
		}
	}
}
