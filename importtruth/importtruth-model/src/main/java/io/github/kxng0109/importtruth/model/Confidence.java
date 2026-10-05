package io.github.kxng0109.importtruth.model;

/**
 * How much a lookup result can be trusted.
 */
public enum Confidence {

	/** The full name resolved to exactly one symbol. */
	DEFINITE,
	/** The name is ambiguous or inferred, verify before acting. */
	CANDIDATE
}
