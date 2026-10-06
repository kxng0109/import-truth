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
		List<Symbol> symbols = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Map<String, byte[]> chosen = chooseEntries(zip);
			for (Map.Entry<String, byte[]> entry : chosen.entrySet()) {
				byte[] bytes = entry.getValue();
				if (bytes.length > MAX_CLASS_BYTES) {
					continue;
				}
				ClassReader reader = new ClassReader(bytes, 0, bytes.length);
				reader.accept(new TypeCollector(symbols), ClassReader.SKIP_CODE);
			}
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

		private final List<Symbol> symbols;
		private int access;
		private boolean visible;
		private final Deprecation deprecation = new Deprecation();
		private String parentFqn = "";

		TypeCollector(List<Symbol> symbols) {
			super(API);
			this.symbols = symbols;
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
			this.parentFqn = name.replace('/', '.');
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
			symbols.add(
					new Symbol(
							parentFqn,
							kindOf(access),
							null,
							null,
							deprecation.deprecated,
							deprecation.since,
							deprecation.forRemoval));
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
				symbols.add(
						new Symbol(
								parentFqn + "." + name,
								SymbolKind.METHOD,
								descriptor,
								parentFqn,
								deprecation.deprecated,
								deprecation.since,
								deprecation.forRemoval));
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
				symbols.add(
						new Symbol(
								parentFqn + "." + name,
								SymbolKind.FIELD,
								descriptor,
								parentFqn,
								deprecation.deprecated,
								deprecation.since,
								deprecation.forRemoval));
			}
		}
	}
}
