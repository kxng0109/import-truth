package io.github.kxng0109.importtruth.adapter.java;

import io.github.kxng0109.importtruth.model.LibraryIndexer;
import io.github.kxng0109.importtruth.model.Symbol;
import io.github.kxng0109.importtruth.model.SymbolKind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Extracts public API shape from JVM libraries with ASM. Reads declarations
 * only: bytecode is skipped, library code never runs.
 */
public final class AsmLibraryIndexer implements LibraryIndexer {

	/** ASM API level this extractor is written against. */
	static final int API = Opcodes.ASM9;

	/** Entries larger than this are skipped, never parsed. */
	static final int MAX_CLASS_BYTES = 256 * 1024;

	/** Archives with more entries than this are refused outright. */
	static final int MAX_ENTRIES = 50_000;

	/** Descriptor of the runtime-retained deprecation annotation. */
	static final String DEPRECATED_DESCRIPTOR = "Ljava/lang/Deprecated;";

	@Override
	public List<Symbol> index(Path jar) throws IOException {
		Objects.requireNonNull(jar, "jar");
		List<Record> records = new ArrayList<>();
		Names names = new Names();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Map<String, byte[]> chosen = chooseEntries(zip);
			for (Map.Entry<String, byte[]> entry : chosen.entrySet()) {
				byte[] bytes = entry.getValue();
				if (bytes.length > MAX_CLASS_BYTES) {
					continue;
				}
				ClassReader reader = new ClassReader(bytes, 0, bytes.length);
				reader.accept(new TypeCollector(records, names),
						ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
		}
		// Names resolve after the pass: a nested type's source name
		// depends on the enclosing type's entry, which another class
		// file may carry.
		List<Symbol> symbols = new ArrayList<>(records.size());
		for (Record record : records) {
			symbols.add(record.toSymbol(names));
		}
		return symbols;
	}

	private static Map<String, byte[]> chooseEntries(ZipFile zip) throws IOException {
		Map<String, byte[]> base = new HashMap<>();
		Map<String, Versioned> versioned = new HashMap<>();
		// Like a real class loader, never look above the running version:
		// an incompatible newer entry must not shadow compatible ones.
		int runtime = Runtime.version().feature();
		int count = 0;
		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			count++;
			if (count > MAX_ENTRIES) {
				throw new IOException("Too many entries in " + zip.getName());
			}
			String name = entry.getName();
			if (entry.isDirectory() || !name.endsWith(".class")) {
				continue;
			}
			if (name.equals("module-info.class") || name.endsWith("/package-info.class")) {
				continue;
			}
			// Skip oversized entries before allocating: declared sizes
			// above the cap never reach the heap. Unknown sizes fall
			// through to the parse-time gate in index().
			if (entry.getSize() > MAX_CLASS_BYTES) {
				continue;
			}
			byte[] bytes = zip.getInputStream(entry).readAllBytes();
			int versionsAt = name.indexOf("META-INF/versions/");
			if (versionsAt == 0) {
				int slash = name.indexOf('/', "META-INF/versions/".length());
				if (slash < 0) {
					continue;
				}
				int release;
				try {
					release = Integer.parseInt(name.substring("META-INF/versions/".length(), slash));
				} catch (NumberFormatException notVersioned) {
					continue;
				}
				if (release < 9 || release > runtime) {
					continue;
				}
				String key = name.substring(slash + 1);
				if (key.equals("module-info.class")) {
					continue;
				}
				Versioned current = versioned.get(key);
				if (current == null || release > current.release()) {
					versioned.put(key, new Versioned(release, bytes));
				}
			} else {
				base.put(name, bytes);
			}
		}
		for (Map.Entry<String, Versioned> entry : versioned.entrySet()) {
			base.put(entry.getKey(), entry.getValue().bytes());
		}
		return base;
	}

	private record Versioned(int release, byte[] bytes) {
	}

	private static final class Deprecation {

		boolean deprecated;
		String since = "";
		boolean forRemoval;

		AnnotationVisitor visitor() {
			return new AnnotationVisitor(API) {
				@Override
				public void visit(String name, Object value) {
					if ("since".equals(name) && value instanceof String text) {
						since = text;
					} else if ("forRemoval".equals(name) && value instanceof Boolean removal) {
						forRemoval = removal;
					}
				}
			};
		}
	}

	private static boolean isPublicApi(int access) {
		return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
	}

	private static boolean isCompilerMade(int access) {
		return (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
	}

	private static final class TypeCollector extends ClassVisitor {

		private final List<Record> records;
		private final Names names;
		private int access;
		private boolean visible;
		private final Deprecation deprecation = new Deprecation();
		private String parentInternal = "";

		TypeCollector(List<Record> records, Names names) {
			super(API);
			this.records = records;
			this.names = names;
		}

		@Override
		public void visit(
				int version,
				int access,
				String name,
				String signature,
				String superName,
				String[] interfaces) {
			this.access = access;
			this.visible = isPublicApi(access) && !isCompilerMade(access);
			this.parentInternal = name;
			if ((access & Opcodes.ACC_DEPRECATED) != 0) {
				deprecation.deprecated = true;
			}
		}

		@Override
		public void visitInnerClass(String name, String outerName, String innerName, int access) {
			names.record(name, outerName, innerName);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (DEPRECATED_DESCRIPTOR.equals(descriptor)) {
				deprecation.deprecated = true;
				return deprecation.visitor();
			}
			return null;
		}

		@Override
		public MethodVisitor visitMethod(
				int access, String name, String descriptor, String signature, String[] exceptions) {
			// ASM always calls visit() before members, so the parent name is set.
			if (!visible || !isPublicApi(access) || isCompilerMade(access)) {
				return null;
			}
			return new MemberCollector(access, name, descriptor);
		}

		@Override
		public FieldVisitor visitField(
				int access, String name, String descriptor, String signature, Object value) {
			// ASM always calls visit() before members, so the parent name is set.
			if (!visible || !isPublicApi(access) || isCompilerMade(access)) {
				return null;
			}
			return new MemberFieldCollector(access, name, descriptor);
		}

		@Override
		public void visitEnd() {
			// ASM always calls visit() first; module descriptors never reach
			// here because entry selection drops them.
			if (!isPublicApi(access) || isCompilerMade(access)) {
				return;
			}
			records.add(new TypeRecord(parentInternal, kindOf(access),
					deprecation.deprecated, deprecation.since, deprecation.forRemoval));
		}

		private SymbolKind kindOf(int access) {
			if ((access & Opcodes.ACC_ANNOTATION) != 0) {
				return SymbolKind.ANNOTATION;
			}
			if ((access & Opcodes.ACC_ENUM) != 0) {
				return SymbolKind.ENUM;
			}
			if ((access & Opcodes.ACC_RECORD) != 0) {
				return SymbolKind.RECORD;
			}
			if ((access & Opcodes.ACC_INTERFACE) != 0) {
				return SymbolKind.INTERFACE;
			}
			return SymbolKind.CLASS;
		}

		private final class MemberCollector extends MethodVisitor {

			private final String name;
			private final String descriptor;
			private final Deprecation deprecation = new Deprecation();

			MemberCollector(int access, String name, String descriptor) {
				super(API);
				this.name = name;
				this.descriptor = descriptor;
				if ((access & Opcodes.ACC_DEPRECATED) != 0) {
					deprecation.deprecated = true;
				}
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (DEPRECATED_DESCRIPTOR.equals(descriptor)) {
					deprecation.deprecated = true;
					return deprecation.visitor();
				}
				return null;
			}

			@Override
			public void visitEnd() {
				records.add(new MemberRecord(parentInternal, name, SymbolKind.METHOD, descriptor,
						deprecation.deprecated, deprecation.since, deprecation.forRemoval));
			}
		}

		private final class MemberFieldCollector extends FieldVisitor {

			private final String name;
			private final String descriptor;
			private final Deprecation deprecation = new Deprecation();

			MemberFieldCollector(int access, String name, String descriptor) {
				super(API);
				this.name = name;
				this.descriptor = descriptor;
				if ((access & Opcodes.ACC_DEPRECATED) != 0) {
					deprecation.deprecated = true;
				}
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (DEPRECATED_DESCRIPTOR.equals(descriptor)) {
					deprecation.deprecated = true;
					return deprecation.visitor();
				}
				return null;
			}

			@Override
			public void visitEnd() {
				records.add(new MemberRecord(parentInternal, name, SymbolKind.FIELD, descriptor,
						deprecation.deprecated, deprecation.since, deprecation.forRemoval));
			}
		}
	}

	/**
	 * One indexed shape, resolved to a source name after the pass.
	 */
	private interface Record {

		/**
		 * Builds the symbol.
		 *
		 * @param names source name resolver, never null
		 * @return the symbol, never null
		 */
		Symbol toSymbol(Names names);
	}

	/**
	 * A type declaration.
	 *
	 * @param internalName binary name, never null
	 * @param kind         type kind, never null
	 * @param deprecated   true when marked deprecated
	 * @param since        value of {@code since}, never null
	 * @param forRemoval   true when marked for removal
	 */
	private record TypeRecord(
			String internalName,
			SymbolKind kind,
			boolean deprecated,
			String since,
			boolean forRemoval) implements Record {

		@Override
		public Symbol toSymbol(Names names) {
			String source = names.of(internalName);
			return new Symbol(source, kind, null, null, deprecated, since, forRemoval);
		}
	}

	/**
	 * A method or field declaration.
	 *
	 * @param parentInternal binary name of the declaring type, never null
	 * @param name           member name, never null
	 * @param kind           member kind, never null
	 * @param descriptor     JVM descriptor, never null
	 * @param deprecated     true when marked deprecated
	 * @param since          value of {@code since}, never null
	 * @param forRemoval     true when marked for removal
	 */
	private record MemberRecord(
			String parentInternal,
			String name,
			SymbolKind kind,
			String descriptor,
			boolean deprecated,
			String since,
			boolean forRemoval) implements Record {

		@Override
		public Symbol toSymbol(Names names) {
			String parent = names.of(parentInternal);
			return new Symbol(parent + "." + name, kind, descriptor, parent,
					deprecated, since, forRemoval);
		}
	}

	/**
	 * Resolves binary type names to the names source code writes.
	 * <p>
	 * A nested type is {@code Outer.Inner} in source and
	 * {@code Outer$Inner} in the class file. The inner classes
	 * attribute says which is which, so a top level class whose own
	 * name holds a dollar sign keeps it.
	 */
	private static final class Names {

		private final Map<String, String> outerOf = new HashMap<>();
		private final Map<String, String> simpleOf = new HashMap<>();
		private final Map<String, String> resolved = new HashMap<>();

		/**
		 * Records one inner class entry.
		 *
		 * @param internalName binary name of the nested type, never null
		 * @param outerName    binary name of the enclosing type, null when top level
		 * @param innerName    simple name of the nested type, null when unknown
		 */
		void record(String internalName, String outerName, String innerName) {
			if (outerName != null && innerName != null) {
				outerOf.put(internalName, outerName);
				simpleOf.put(internalName, innerName);
			}
		}

		/**
		 * Resolves the source name of a binary type name.
		 *
		 * @param internalName binary name, never null
		 * @return source name, never null
		 */
		String of(String internalName) {
			String known = resolved.get(internalName);
			if (known != null) {
				return known;
			}
			String outer = outerOf.get(internalName);
			String source = outer == null
					? internalName.replace('/', '.')
					: of(outer) + "." + simpleOf.get(internalName);
			resolved.put(internalName, source);
			return source;
		}
	}
}
