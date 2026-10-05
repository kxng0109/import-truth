package io.github.kxng0109.importtruth.model;

import java.util.Objects;

/**
 * Server identity returned by the ping tool.
 *
 * @param name    server name, never blank
 * @param version server version, never blank
 */
public record ApiInfo(String name, String version) {

	/**
	 * Creates the server identity.
	 *
	 * @throws NullPointerException     if {@code name} or {@code version} is {@code null}
	 * @throws IllegalArgumentException if {@code name} or {@code version} is blank
	 */
	public ApiInfo {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(version, "version");
		if (name.isBlank() || version.isBlank()) {
			throw new IllegalArgumentException("name and version must not be blank");
		}
	}
}
