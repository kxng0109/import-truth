package io.github.kxng0109.importtruth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two spellings of a nested type name and the kinds that
 * name a type.
 */
@DisplayName("Type names")
final class TypeNamesTest {

	@Test
	@DisplayName("spells a nested type with dots")
	void spellsNestedTypeWithDots() {
		assertThat(TypeNames.alternate("com.example.Outer$Inner")).as("dotted spelling")
				.isEqualTo("com.example.Outer.Inner");
		assertThat(TypeNames.alternate("com.example.Outer$Inner$Deep")).as("doubly nested")
				.isEqualTo("com.example.Outer.Inner.Deep");
		assertThat(TypeNames.alternate("com.example.Widget")).as("no dollar sign")
				.isNull();
	}

	@Test
	@DisplayName("rejects a null name")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullName() {
		assertThatThrownBy(() -> TypeNames.alternate(null)).as("null name")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("knows which kinds name a type")
	void knowsTypeKinds() {
		assertThat(SymbolKind.types()).as("type kinds").containsExactlyInAnyOrder(
				SymbolKind.CLASS,
				SymbolKind.INTERFACE,
				SymbolKind.ENUM,
				SymbolKind.RECORD,
				SymbolKind.ANNOTATION);
		assertThat(SymbolKind.isType(SymbolKind.CLASS)).as("class is a type").isTrue();
		assertThat(SymbolKind.isType(SymbolKind.INTERFACE)).as("interface is a type").isTrue();
		assertThat(SymbolKind.isType(SymbolKind.ENUM)).as("enum is a type").isTrue();
		assertThat(SymbolKind.isType(SymbolKind.RECORD)).as("record is a type").isTrue();
		assertThat(SymbolKind.isType(SymbolKind.ANNOTATION)).as("annotation is a type").isTrue();
		assertThat(SymbolKind.isType(SymbolKind.METHOD)).as("method is not a type").isFalse();
		assertThat(SymbolKind.isType(SymbolKind.FIELD)).as("field is not a type").isFalse();
	}

	@Test
	@DisplayName("rejects a null kind")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullKind() {
		assertThatThrownBy(() -> SymbolKind.isType(null)).as("null kind")
				.isInstanceOf(NullPointerException.class);
	}
}
