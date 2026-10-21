package io.github.kxng0109.importtruth.model;

import java.util.Objects;
import java.util.Set;

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
	FIELD;

	/**
	 * The kinds a type-shaped search accepts.
	 *
	 * @return the type kinds, never null
	 */
	public static Set<SymbolKind> types() {
		return Set.of(CLASS, INTERFACE, ENUM, RECORD, ANNOTATION);
	}

	/**
	 * Tests whether a kind names a type. A type can be imported on its
	 * own; a member never can.
	 *
	 * @param kind kind to test, never null
	 * @return true for classes, interfaces, enums, records, annotations
	 * @throws NullPointerException if {@code kind} is {@code null}
	 */
	public static boolean isType(SymbolKind kind) {
		Objects.requireNonNull(kind, "kind");
		return kind == CLASS || kind == INTERFACE || kind == ENUM
				|| kind == RECORD || kind == ANNOTATION;
	}
}
