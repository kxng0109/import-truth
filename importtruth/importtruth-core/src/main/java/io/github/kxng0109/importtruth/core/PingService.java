package io.github.kxng0109.importtruth.core;

import io.github.kxng0109.importtruth.model.ApiInfo;

/**
 * Answers liveness checks with the server identity.
 */
public final class PingService {

	private final ApiInfo identity;

	/**
	 * Creates the service.
	 *
	 * @param name    server name, never blank
	 * @param version server version, never blank
	 * @throws NullPointerException     if {@code name} or {@code version} is {@code null}
	 * @throws IllegalArgumentException if {@code name} or {@code version} is blank
	 */
	public PingService(String name, String version) {
		this.identity = new ApiInfo(name, version);
	}

	/**
	 * Returns the server identity.
	 *
	 * @return server name and version
	 */
	public ApiInfo ping() {
		return identity;
	}
}
