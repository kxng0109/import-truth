package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * Spelling rules for type names.
 * <p>
 * A nested type is written {@code Outer.Inner} in source and
 * {@code Outer$Inner} in the class file. Both spellings must find the
 * same type, so a query holding a dollar sign is also tried with dots.
 */
public final class TypeNames {

	private TypeNames() {
	}

	/**
	 * Returns the other spelling of a type name.
	 *
	 * @param name fully qualified name, never null
	 * @return the dotted spelling, or {@code null} when the name holds no dollar sign
	 * @throws NullPointerException if {@code name} is {@code null}
	 */
	public static String alternate(String name) {
		Objects.requireNonNull(name, "name");
		if (name.indexOf('$') < 0) {
			return null;
		}
		return name.replace('$', '.');
	}
}
