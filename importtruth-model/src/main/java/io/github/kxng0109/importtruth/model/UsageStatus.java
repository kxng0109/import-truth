package io.github.kxng0109.importtruth.model;

/**
 * How the scanned code uses one dependency.
 */
public enum UsageStatus {

	/** The code names a class the dependency holds. */
	USED_DIRECT,
	/** The code names no class of the dependency, but a class it uses does. */
	USED_INDIRECT,
	/** The code names no class of the dependency and no class it uses does. */
	UNREFERENCED,
	/** The dependency could not be checked. */
	UNVERIFIED
}
