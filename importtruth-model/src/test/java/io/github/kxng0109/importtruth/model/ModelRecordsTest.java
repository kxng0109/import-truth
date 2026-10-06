package io.github.kxng0109.importtruth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies every model record: happy paths and rejections.
 */
@DisplayName("Model records")
final class ModelRecordsTest {

	@Test
	@DisplayName("carries symbol data")
	void carriesSymbol() {
		Symbol symbol = new Symbol("com.example.Widget", SymbolKind.CLASS, null, null, true, "2.0", true);

		assertThat(symbol.fqn()).as("name").isEqualTo("com.example.Widget");
		assertThat(symbol.kind()).as("kind").isEqualTo(SymbolKind.CLASS);
		assertThat(symbol.deprecatedSince()).as("since").isEqualTo("2.0");
		assertThat(symbol.forRemoval()).as("removal").isTrue();
	}

	@Test
	@DisplayName("rejects blank symbol names")
	void rejectsBlankSymbol() {
		assertThatThrownBy(() -> new Symbol(" ", SymbolKind.CLASS, null, null, false, "", false))
				.as("blank rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("rejects null symbol parts")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullSymbol() {
		assertThatThrownBy(() -> new Symbol(null, SymbolKind.CLASS, null, null, false, "", false))
				.as("null name rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Symbol("a.B", null, null, null, false, "", false))
				.as("null kind rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Symbol("a.B", SymbolKind.CLASS, null, null, false, null, false))
				.as("null since rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("carries lookup answers")
	void carriesLookup() {
		LookupResult result = new LookupResult(true, Confidence.DEFINITE, List.of(), List.of("a.B"), false);

		assertThat(result.found()).as("found").isTrue();
		assertThat(result.confidence()).as("confidence").isEqualTo(Confidence.DEFINITE);
		assertThat(result.suggestions()).as("suggestions").containsExactly("a.B");
		assertThat(result.fromJdk()).as("jdk flag").isFalse();
	}

	@Test
	@DisplayName("rejects null lookup parts")
	@SuppressWarnings("DataFlowIssue")
	void rejectsNullLookup() {
		assertThatThrownBy(() -> new LookupResult(true, null, List.of(), List.of(), false))
				.as("null confidence rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LookupResult(true, Confidence.CANDIDATE, null, List.of(), false))
				.as("null matches rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LookupResult(false, Confidence.CANDIDATE, List.of(), null, false))
				.as("null suggestions rejection")
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("carries findings and verdicts")
	void carriesFindings() {
		Finding finding = new Finding("A.java", 3, FindingKind.MISSING, "import a.B resolves nowhere", "maybe: a.C");
		ImportVerdict verdict = new ImportVerdict("a.B", 3, false);
		CheckResult result = new CheckResult(true, List.of(finding), List.of(verdict));

		assertThat(result.healthy()).as("healthy").isTrue();
		assertThat(result.findings()).as("findings").hasSize(1);
		assertThat(result.verdicts()).as("verdicts").hasSize(1);
		assertThat(finding.line()).as("line").isEqualTo(3);
		assertThat(finding.suggestion()).as("suggestion").isEqualTo("maybe: a.C");
		assertThat(verdict.resolved()).as("unresolved").isFalse();
	}

	@Test
	@DisplayName("rejects bad findings")
	void rejectsBadFindings() {
		assertThatThrownBy(() -> new Finding(" ", 1, FindingKind.MISSING, "d", ""))
				.as("blank file rejection")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Finding("A.java", 0, FindingKind.MISSING, "d", ""))
				.as("bad line rejection")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Finding("A.java", 1, FindingKind.MISSING, " ", ""))
				.as("blank detail rejection")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CheckResult(true, List.of(), null))
				.as("null verdicts rejection")
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ImportVerdict(" ", 1, true))
				.as("blank verdict rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("carries policy packs")
	void carriesPacks() {
		PolicyRule rule = new PolicyRule(PolicyRuleKind.RENAME, "a.B", "c.D", "");
		PolicyPack pack = new PolicyPack("test", List.of(rule));
		PolicyHit hit = new PolicyHit("renamed", "use c.D");

		assertThat(pack.name()).as("pack name").isEqualTo("test");
		assertThat(pack.rules()).as("pack rules").hasSize(1);
		assertThat(rule.subject()).as("rule subject").isEqualTo("a.B");
		assertThat(hit.detail()).as("hit detail").isEqualTo("renamed");
	}

	@Test
	@DisplayName("rejects bad policy parts")
	void rejectsBadPolicy() {
		assertThatThrownBy(() -> new PolicyRule(PolicyRuleKind.ALLOW, " ", "", ""))
				.as("blank subject rejection")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyPack(" ", List.of()))
				.as("blank pack rejection")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PolicyHit(" ", ""))
				.as("blank hit rejection")
				.isInstanceOf(IllegalArgumentException.class);
	}
}
