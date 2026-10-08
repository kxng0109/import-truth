package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * One indexed public API symbol from a dependency.
 *
 * @param fqn              fully qualified name, never blank
 * @param kind             symbol shape, never null
 * @param signature        JVM descriptor for members, {@code null} for types
 * @param parentFqn        enclosing type for members, {@code null} for types
 * @param deprecated       true when marked deprecated
 * @param deprecatedSince  value of {@code since}, empty when unknown
 * @param forRemoval       true when marked for removal
 */
public record Symbol(
		String fqn,
		SymbolKind kind,
		String signature,
		String parentFqn,
		boolean deprecated,
		String deprecatedSince,
		boolean forRemoval) {

	/**
	 * Creates the symbol.
	 *
	 * @throws NullPointerException     if {@code fqn}, {@code kind}, or {@code deprecatedSince} is {@code null}
	 * @throws IllegalArgumentException if {@code fqn} is blank
	 */
	public Symbol {
		fqn = Preconditions.requireNonBlank(fqn, "fqn");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(deprecatedSince, "deprecatedSince");
	}
}
