package io.github.kxng0109.importtruth.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves a project's dependency jars.
 */
public interface DependencyResolver {

	/**
	 * Returns the project's resolved jar files.
	 *
	 * @param projectDir  project root, never null
	 * @param allowNetwork true to retry online when offline resolution fails
	 * @return existing jar files, never null
	 * @throws IOException when resolution fails
	 */
	List<Path> resolve(Path projectDir, boolean allowNetwork) throws IOException;
}
