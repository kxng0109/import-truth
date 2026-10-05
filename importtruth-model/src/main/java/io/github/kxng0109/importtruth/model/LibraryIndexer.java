package io.github.kxng0109.importtruth.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads the public API shape of one library file. Implementations must never
 * execute library code. Loaded through {@link java.util.ServiceLoader}.
 */
public interface LibraryIndexer {

	/**
	 * Extracts public symbols from the library.
	 *
	 * @param jar library file, never null
	 * @return public symbols in encounter order, never null
	 * @throws IOException when the file cannot be read
	 * @throws NullPointerException when {@code jar} is {@code null}
	 */
	List<Symbol> index(Path jar) throws IOException;
}
