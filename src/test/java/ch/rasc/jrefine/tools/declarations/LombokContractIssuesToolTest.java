package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LombokContractIssuesToolTest {

	@Test
	void reportsDeprecatedLombokAnnotations() {
		ToolResult result = inspect("""
				import lombok.experimental.Builder;
				import lombok.experimental.Value;
				import lombok.experimental.Wither;
				import lombok.experimental.var;
				@Builder @Value class Legacy {
				    @Wither String name;
				    void run() { var value = "ok"; }
				}
				""");

		assertMessages(result, "lombok.Builder", "lombok.Value", "lombok.With", "lombok.var");
	}

	@Test
	void reportsInvalidAndRedundantLombokAnnotations() {
		ToolResult result = inspect("""
				import lombok.Data;
				import lombok.EqualsAndHashCode;
				import lombok.NoArgsConstructor;
				import lombok.ToString;
				import lombok.Value;
				import lombok.experimental.UtilityClass;

				@ToString @EqualsAndHashCode @NoArgsConstructor
				class Explicit {
				    Explicit() {}
				    public String toString() { return "Explicit"; }
				    public boolean equals(Object other) { return this == other; }
				    public int hashCode() { return 1; }
				}

				@Value record Immutable(String name) {}
				@Data record MutableRecord(String name) {}
				@UtilityClass interface InvalidUtility {}
				""");

		assertMessages(result, "@ToString is redundant", "@EqualsAndHashCode is redundant",
				"@NoArgsConstructor conflicts", "@Value cannot", "@Data cannot", "@UtilityClass cannot");
	}

	@Test
	void reportsStaticImportsOfSourceLocalGeneratedMethods() {
		ToolResult result = inspect("""
				package sample;
				import static sample.Model.builder;
				import static sample.Factory.create;
				import lombok.Builder;
				import lombok.RequiredArgsConstructor;

				@Builder class Model {}
				@RequiredArgsConstructor(staticName = "create") class Factory {
				    final String value;
				}
				""");

		long imports = messages(result).stream().filter(message -> message.contains("Static import")).count();
		assertTrue(imports == 2, messages(result).toString());
	}

	@Test
	void reportsGeneratedStaticImportFromNeighboringSource(@TempDir Path directory) throws IOException {
		Path packageDirectory = directory.resolve("src").resolve("sample");
		Files.createDirectories(packageDirectory);
		Files.writeString(packageDirectory.resolve("Model.java"), """
				package sample;
				import lombok.Builder;
				@Builder class Model {}
				""");
		Path use = packageDirectory.resolve("Use.java");
		String source = """
				package sample;
				import static sample.Model.builder;
				class Use { Object create() { return builder(); } }
				""";
		Files.writeString(use, source);
		InspectionContext parsed = TestSources.parse(source);
		InspectionContext context = new InspectionContext(use, parsed.compilationUnit(), source);

		ToolResult result = new ReportLombokContractIssuesTool().inspect(context, true);

		assertMessages(result, "Static import");
	}

	@Test
	void acceptsValidLombokAndUnknownExternalImports() {
		ToolResult result = inspect("""
				package sample;
				import static external.Model.builder;
				import lombok.Builder;
				import lombok.ToString;

				@Builder(builderMethodName = "") class Model {}
				@ToString class GeneratedToString { final String value = "ok"; }
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void ignoresSameNamedLocalAnnotations() {
		ToolResult result = inspect("""
				@interface Builder {}
				@interface Value {}
				@interface ToString {}
				@Builder @Value @ToString record Sample(String value) {
				    public String toString() { return value; }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void existingFinalCleanupHandlesLombokVal() {
		InspectionContext context = TestSources.parse("""
				import lombok.val;
				class Sample {
				    void run() { final val name = "jrefine"; }
				}
				""");

		ToolResult result = new RemoveUnnecessaryFinalTool().inspect(context, true);

		assertTrue(result.changed());
		assertFalse(context.editor().render().contains("final val"));
	}

	private static ToolResult inspect(String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = new ReportLombokContractIssuesTool().inspect(context, true);
		assertFalse(result.changed(), "Issue reporters must not change source");
		return result;
	}

	private static List<String> messages(ToolResult result) {
		return result.findings().stream().map(finding -> finding.message()).toList();
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = messages(result);
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

}
