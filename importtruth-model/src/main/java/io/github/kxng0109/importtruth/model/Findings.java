package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * Renders findings as single agent-readable lines.
 */
public final class Findings {

	private Findings() {
	}

	/**
	 * Formats one finding as {@code file:line KIND detail (suggestion)}.
	 *
	 * @param finding finding, never null
	 * @return single line, never null
	 * @throws NullPointerException if {@code finding} is {@code null}
	 */
	public static String format(Finding finding) {
		Objects.requireNonNull(finding, "finding");
		StringBuilder line = new StringBuilder(finding.file()).append(':').append(finding.line())
				.append(' ').append(finding.kind().name())
				.append(' ').append(finding.detail());
		if (!finding.suggestion().isEmpty()) {
			line.append(" (").append(finding.suggestion()).append(')');
		}
		return line.toString();
	}
}
