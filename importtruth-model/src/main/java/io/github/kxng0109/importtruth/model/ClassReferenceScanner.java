package io.github.kxng0109.importtruth.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Reads the symbolic references of compiled classes.
 * <p>
 * A class file records only symbolic references, so the set of
 * classes a compilation names is a fact about the bytecode and not
 * about the source.
 * <p>
 * Implementations must never execute the classes they read, and must
 * never report a name that appears only as a string literal. A class
 * named only inside a string is not a reference the bytecode makes,
 * and reporting it would keep a dependency alive that nothing uses.
 * Loaded through {@link java.util.ServiceLoader}.
 */
public interface ClassReferenceScanner {

	/**
	 * Collects the classes the given class files reference.
	 * <p>
	 * Names are binary names: dots separate the package, a dollar
	 * sign separates a nested type, for example {@code com.foo.Bar}
	 * and {@code com.foo.Bar$Baz}. A class never names itself.
	 *
	 * @param classFileOrDirectory class file or directory of class files, never null
	 * @return binary names of the referenced classes, never null
	 * @throws IOException when the class files cannot be read
	 * @throws NullPointerException when {@code classFileOrDirectory} is {@code null}
	 */
	Set<String> referencesOf(Path classFileOrDirectory) throws IOException;
}
