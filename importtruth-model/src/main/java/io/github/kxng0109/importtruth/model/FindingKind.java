package io.github.kxng0109.importtruth.model;

/**
 * Severity of a file-check finding.
 */
public enum FindingKind {

	/** The import resolves to nothing. Blocks the build. */
	MISSING,
	/** The import might be project-owned or generated. Warning only. */
	CANDIDATE
}
