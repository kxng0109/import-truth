package io.github.kxng0109.importtruth.model;

/**
 * Shape of a policy rule.
 */
public enum PolicyRuleKind {

	/** Prefer one package over another. */
	PREFER,
	/** Explicitly allow a package. Never fires. */
	ALLOW,
	/** Rename one fully qualified name to another. */
	RENAME
}
