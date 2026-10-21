package io.github.kxng0109.importtruth.adapter.java;

import io.github.kxng0109.importtruth.model.ClassReferenceScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Reads the symbolic references of class files with ASM.
 * <p>
 * The reference set is a fact about the bytecode: a class file
 * records only symbolic references, so a name that appears only
 * inside a string literal is not one of them and is never reported.
 * The classes that are read are never executed, and a dependency
 * cannot run anything while it is being scanned.
 */
public final class AsmClassReferenceScanner implements ClassReferenceScanner {

	/** ASM API level this extractor is written against. */
	private static final int API = Opcodes.ASM9;

	/** Suffix every Java class file carries. */
	private static final String CLASS_SUFFIX = ".class";

	@Override
	public Set<String> referencesOf(Path classFileOrDirectory) throws IOException {
		Objects.requireNonNull(classFileOrDirectory, "classFileOrDirectory");
		Set<String> names = new LinkedHashSet<>();
		if (Files.isDirectory(classFileOrDirectory)) {
			try (Stream<Path> walk = Files.walk(classFileOrDirectory)) {
				for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
					if (file.getFileName().toString().endsWith(CLASS_SUFFIX)) {
						collect(file, names);
					}
				}
			}
		} else {
			collect(classFileOrDirectory, names);
		}
		return names;
	}

	private static void collect(Path file, Set<String> names) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		new ClassReader(bytes).accept(new ReferenceCollector(names),
				ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
	}

	/**
	 * Gathers the classes one class file names.
	 * <p>
	 * The class never names itself: a self reference says nothing
	 * about the dependencies of the code.
	 */
	private static final class ReferenceCollector extends ClassVisitor {

		private final Set<String> names;
		private String ownName = "";

		ReferenceCollector(Set<String> names) {
			super(API);
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
			this.ownName = binary(name);
			addInternal(superName);
			// ASM always hands over an array, empty when there is none.
			for (String each : interfaces) {
				addInternal(each);
			}
			addSignature(signature, false);
		}

		@Override
		public void visitNestHost(String nestHost) {
			addInternal(nestHost);
		}

		@Override
		public void visitNestMember(String nestMember) {
			addInternal(nestMember);
		}

		@Override
		public void visitPermittedSubclass(String permittedSubclass) {
			addInternal(permittedSubclass);
		}

		@Override
		public void visitInnerClass(String name, String outerName, String innerName, int access) {
			addInternal(name);
			addInternal(outerName);
		}

		@Override
		public void visitOuterClass(String owner, String name, String descriptor) {
			addInternal(owner);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			return addAnnotation(descriptor);
		}

		@Override
		public AnnotationVisitor visitTypeAnnotation(
				int typeRef,
				TypePath typePath,
				String descriptor,
				boolean visible) {
			return addAnnotation(descriptor);
		}

		@Override
		public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
			addDescriptor(descriptor);
			addSignature(signature, true);
			return new ComponentCollector();
		}

		@Override
		public FieldVisitor visitField(
				int access,
				String name,
				String descriptor,
				String signature,
				Object value) {
			addDescriptor(descriptor);
			addSignature(signature, true);
			return new FieldCollector();
		}

		@Override
		public MethodVisitor visitMethod(
				int access,
				String name,
				String descriptor,
				String signature,
				String[] exceptions) {
			addDescriptor(descriptor);
			addSignature(signature, false);
			if (exceptions != null) {
				for (String each : exceptions) {
					addInternal(each);
				}
			}
			return new BodyCollector();
		}

		/**
		 * Records a class named by an internal name.
		 *
		 * @param internalName internal name, null when the name is absent
		 */
		private void addInternal(String internalName) {
			if (internalName == null) {
				return;
			}
			String binary = binary(internalName);
			if (!binary.equals(ownName)) {
				names.add(binary);
			}
		}

		/**
		 * Records every class named by a field or method descriptor.
		 *
		 * @param descriptor field or method descriptor, never null
		 */
		private void addDescriptor(String descriptor) {
			addType(Type.getType(descriptor));
		}

		/**
		 * Records every class named by a type.
		 *
		 * @param type type to read, never null
		 */
		private void addType(Type type) {
			int sort = type.getSort();
			if (sort == Type.OBJECT) {
				addInternal(type.getInternalName());
			} else if (sort == Type.ARRAY) {
				addType(type.getElementType());
			} else if (sort == Type.METHOD) {
				for (Type argument : type.getArgumentTypes()) {
					addType(argument);
				}
				addType(type.getReturnType());
			}
		}

		/**
		 * Records a class named by an instruction operand that holds
		 * either an internal name or an array descriptor.
		 *
		 * @param type operand of the instruction, never null
		 */
		private void addTypeReference(String type) {
			if (type.startsWith("[")) {
				addDescriptor(type);
			} else {
				addInternal(type);
			}
		}

		/**
		 * Records the type of an annotation.
		 *
		 * @param descriptor descriptor of the annotation type, never null
		 * @return a visitor that collects nothing else
		 */
		private AnnotationVisitor addAnnotation(String descriptor) {
			addDescriptor(descriptor);
			return null;
		}

		/**
		 * Records the classes a generic signature names.
		 *
		 * @param signature generic signature, null when the member is not generic
		 * @param typeOnly true for a field signature, false for a class or method signature
		 */
		private void addSignature(String signature, boolean typeOnly) {
			if (signature == null) {
				return;
			}
			SignatureReader reader = new SignatureReader(signature);
			SignatureCollector collector = new SignatureCollector();
			if (typeOnly) {
				reader.acceptType(collector);
			} else {
				reader.accept(collector);
			}
		}

		/**
		 * Converts an internal name to a binary name.
		 *
		 * @param internalName internal name, never null
		 * @return binary name, never null
		 */
		private static String binary(String internalName) {
			return internalName.replace('/', '.');
		}

		/**
		 * Collects the class names of one generic signature.
		 * <p>
		 * A signature names a nested type in two ways: the name of
		 * the outer type alone, as javac writes it, or the outer
		 * name followed by the simple name of the inner type, as
		 * the class file specification writes it.
		 */
		private final class SignatureCollector extends SignatureVisitor {

			private final Deque<String> stack = new ArrayDeque<>();

			SignatureCollector() {
				super(API);
			}

			@Override
			public void visitClassType(String name) {
				stack.push(name);
				addInternal(name);
			}

			@Override
			public void visitInnerClassType(String name) {
				String nested = stack.pop() + "$" + name;
				stack.push(nested);
				addInternal(nested);
			}

			@Override
			public void visitEnd() {
				stack.poll();
			}
		}

		/** Collects the annotations of a record component. */
		private final class ComponentCollector extends RecordComponentVisitor {

			ComponentCollector() {
				super(API);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				return addAnnotation(descriptor);
			}
		}

		/** Collects the annotations of a field. */
		private final class FieldCollector extends FieldVisitor {

			FieldCollector() {
				super(API);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				return addAnnotation(descriptor);
			}

			@Override
			public AnnotationVisitor visitTypeAnnotation(
					int typeRef,
					TypePath typePath,
					String descriptor,
					boolean visible) {
				return addAnnotation(descriptor);
			}
		}

		/** Collects the classes the body of a method names. */
		private final class BodyCollector extends MethodVisitor {

			BodyCollector() {
				super(API);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				return addAnnotation(descriptor);
			}

			@Override
			public AnnotationVisitor visitTypeAnnotation(
					int typeRef,
					TypePath typePath,
					String descriptor,
					boolean visible) {
				return addAnnotation(descriptor);
			}

			@Override
			public AnnotationVisitor visitParameterAnnotation(
					int parameter,
					String descriptor,
					boolean visible) {
				return addAnnotation(descriptor);
			}

			@Override
			public void visitTypeInsn(int opcode, String type) {
				addTypeReference(type);
			}

			@Override
			public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
				addInternal(owner);
				addDescriptor(descriptor);
			}

			@Override
			public void visitMethodInsn(int opcode, String owner, String name, String descriptor) {
				addInternal(owner);
				addDescriptor(descriptor);
			}

			@Override
			public void visitMethodInsn(
					int opcode,
					String owner,
					String name,
					String descriptor,
					boolean isInterface) {
				// The interface flag does not change which classes are named.
				visitMethodInsn(opcode, owner, name, descriptor);
			}

			@Override
			public void visitInvokeDynamicInsn(
					String name,
					String descriptor,
					Handle bootstrapMethod,
					Object... bootstrapArguments) {
				addInternal(bootstrapMethod.getOwner());
				addDescriptor(descriptor);
				for (Object argument : bootstrapArguments) {
					if (argument instanceof Handle handle) {
						addInternal(handle.getOwner());
					} else if (argument instanceof Type type) {
						addType(type);
					}
				}
			}

			@Override
			public void visitLdcInsn(Object value) {
				if (value instanceof Type type) {
					addType(type);
				} else if (value instanceof Handle handle) {
					addInternal(handle.getOwner());
				} else if (value instanceof ConstantDynamic dynamic) {
					addDescriptor(dynamic.getDescriptor());
				}
			}

			@Override
			public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
				addDescriptor(descriptor);
			}

			@Override
			public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
				addInternal(type);
			}
		}
	}
}
