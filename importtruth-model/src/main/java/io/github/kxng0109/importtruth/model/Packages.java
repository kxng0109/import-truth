package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * Package-name containment used by existence checks and policy rules.
 */
public final class Packages {

	private Packages() {
	}

	/**
	 * Tests whether {@code name} equals {@code container} or lies under it.
	 *
	 * @param container owning package, never null
	 * @param name      queried name, never null
	 * @return true on equality or containment
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public static boolean contains(String container, String name) {
		Objects.requireNonNull(container, "container");
		Objects.requireNonNull(name, "name");
		return name.equals(container) || name.startsWith(container + ".");
	}

	/**
	 * Tests whether {@code pkg} equals {@code name} or lies under it.
	 *
	 * @param name queried name, never null
	 * @param pkg  candidate package, never null
	 * @return true on equality or containment
	 * @throws NullPointerException if any argument is {@code null}
	 */
	public static boolean coveredBy(String name, String pkg) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(pkg, "pkg");
		return pkg.equals(name) || pkg.startsWith(name + ".");
	}

	/**
	 * Splits a fully qualified name into simple name and package.
	 *
	 * @param fqn fully qualified name, never null
	 * @return simple name, never null
	 * @throws NullPointerException if {@code fqn} is {@code null}
	 */
	public static String simpleName(String fqn) {
		Objects.requireNonNull(fqn, "fqn");
		int dot = fqn.lastIndexOf('.');
		return dot < 0 ? fqn : fqn.substring(dot + 1);
	}

	/**
	 * Splits a fully qualified name into its package, empty when bare.
	 *
	 * @param fqn fully qualified name, never null
	 * @return package or empty string, never null
	 * @throws NullPointerException if {@code fqn} is {@code null}
	 */
	public static String packageName(String fqn) {
		Objects.requireNonNull(fqn, "fqn");
		int dot = fqn.lastIndexOf('.');
		return dot < 0 ? "" : fqn.substring(0, dot);
	}
}
