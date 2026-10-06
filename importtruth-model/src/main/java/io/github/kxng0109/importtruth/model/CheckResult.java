package io.github.kxng0109.importtruth.model;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of checking one file.
 *
 * @param healthy  false when the file could not be parsed; findings are empty then
 * @param findings problems found, empty when clean or unhealthy
 */
public record CheckResult(boolean healthy, List<Finding> findings) {

	/**
	 * Creates the result.
	 *
	 * @throws NullPointerException if {@code findings} is {@code null}
	 */
	public CheckResult {
		Objects.requireNonNull(findings, "findings");
	}
}
