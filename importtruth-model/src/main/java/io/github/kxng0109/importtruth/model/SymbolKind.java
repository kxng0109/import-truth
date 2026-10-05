package io.github.kxng0109.importtruth.model;

/**
 * Shape of an indexed API symbol.
 */
public enum SymbolKind {

	/** A class. */
	CLASS,
	/** An interface. */
	INTERFACE,
	/** An enum. */
	ENUM,
	/** A record. */
	RECORD,
	/** An annotation. */
	ANNOTATION,
	/** A method or constructor. */
	METHOD,
	/** A field. */
	FIELD
}
