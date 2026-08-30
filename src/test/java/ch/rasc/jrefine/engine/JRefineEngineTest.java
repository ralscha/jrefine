package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.Severity;
import ch.rasc.jrefine.api.JavaVersion;
import ch.rasc.jrefine.tools.declarations.RemoveUnusedImportsTool;
import ch.rasc.jrefine.tools.expressions.UseIsEmptyTool;
import ch.rasc.jrefine.tools.expressions.UseExpressionLambdaTool;
import ch.rasc.jrefine.tools.expressions.UseRecordPatternTool;
import ch.rasc.jrefine.tools.syntax.RemoveUnnecessaryParenthesesTool;
import ch.rasc.jrefine.tools.types.UseDiamondOperatorTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ch.rasc.jrefine.analysis.LineEndingSupport.LINE_FEED;

class JRefineEngineTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void appliesMultipleToolsAndWritesTheSourceOnce() throws IOException {
		Path source = temporaryDirectory.resolve("src").resolve("Sample.java");
		Files.createDirectories(source.getParent());
		Files.writeString(source, """
				import java.util.ArrayList;
				import java.util.List;
				import java.util.Map;

				class Sample {
				    List<String> names = new ArrayList<String>();
				}
				""");

		ModernizationReport report = new JRefineEngine().run(temporaryDirectory,
				List.of(new RemoveUnusedImportsTool(), new UseDiamondOperatorTool()), true);

		assertTrue(report.errors().isEmpty());
		assertEquals(1, report.scannedFiles());
		assertEquals(1, report.changedFiles());
		assertEquals(2, report.findings().size());
		String output = Files.readString(source);
		assertFalse(output.contains("java.util.Map"));
		assertTrue(output.contains("new ArrayList<>()"));
	}

	@Test
	void skipsGeneratedOutputDirectories() throws IOException {
		Path source = temporaryDirectory.resolve("src").resolve("Sample.java");
		Path generated = temporaryDirectory.resolve("target").resolve("Broken.java");
		Files.createDirectories(source.getParent());
		Files.createDirectories(generated.getParent());
		Files.writeString(source, "class Sample {}" + LINE_FEED);
		Files.writeString(generated, "this is not Java");

		ModernizationReport report = new JRefineEngine().run(temporaryDirectory, List.of(new RemoveUnusedImportsTool()),
				false);

		assertEquals(1, report.scannedFiles());
		assertTrue(report.errors().isEmpty());
	}

	@Test
	void reparsesBetweenToolsSoNestedFixesCompose() throws IOException {
		Path source = temporaryDirectory.resolve("Nested.java");
		Files.writeString(source, """
				import java.util.List;
				class Nested {
				    boolean empty(List<String> values) {
				        return (values.size() == 0);
				    }
				}
				""");

		ModernizationReport report = new JRefineEngine().run(source,
				List.of(new RemoveUnnecessaryParenthesesTool(), new UseIsEmptyTool()), true);

		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertEquals(1, report.changedFiles());
		assertTrue(Files.readString(source).contains("return values.isEmpty();"));
	}

	@Test
	void rerunsOneToolUntilNestedFixesConverge() throws IOException {
		Path source = temporaryDirectory.resolve("NestedLambda.java");
		Files.writeString(source, """
				import java.util.function.Supplier;
				class NestedLambda {
				    Supplier<Supplier<String>> value = () -> { return () -> { return "done"; }; };
				}
				""");

		ModernizationReport report = new JRefineEngine().run(source, List.of(new UseExpressionLambdaTool()), true);

		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertEquals(2, report.findings().size());
		assertTrue(Files.readString(source).contains("() -> () -> \"done\""));
	}

	@Test
	void reportsParseErrorsWithoutOverwritingTheFile() throws IOException {
		Path source = temporaryDirectory.resolve("Broken.java");
		String original = "class Broken {" + LINE_FEED;
		Files.writeString(source, original);

		ModernizationReport report = new JRefineEngine().run(source, List.of(new RemoveUnusedImportsTool()), true);

		assertEquals(1, report.errors().size());
		assertEquals(original, Files.readString(source));
	}

	@Test
	void allRegisteredToolsComposeOnMigrationCandidates() throws IOException {
		Path source = temporaryDirectory.resolve("Composite.java");
		Files.writeString(source, """
				import java.io.Serializable;
				import java.util.Collections;
				import java.util.Iterator;
				import java.util.List;

				class Composite implements Serializable {
				    private static final long serialVersionUID = 1L;
				    boolean has(String text, String part) { return text.indexOf(part) >= 0; }
				    void sort(List<String> values) { Collections.sort(values); }
				    void visit(List<String> values) {
				        Iterator<String> iterator = values.iterator();
				        while (iterator.hasNext()) {
				            String value = iterator.next();
				            System.out.println(value);
				        }
				    }
				    String label(int value) {
				        switch (value) {
				            case 1: return "one";
				            default: return "other";
				        }
				    }
				}
				""");

		ModernizationReport report = new JRefineEngine().run(source, ToolRegistry.load().all(), true);
		String output = Files.readString(source);

		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertTrue(output.contains("@Serial"), output);
		assertTrue(output.contains("text.contains(part)"), output);
		assertTrue(output.contains("values.sort(null)"), output);
		assertTrue(output.contains("for (String value : values)"), output);
		assertTrue(output.contains("return switch"), output);
	}

	@Test
	void honorsSourceSuppressionInApplyMode() throws IOException {
		Path source = temporaryDirectory.resolve("Suppressed.java");
		Files.writeString(source, """
				// jrefine-ignore-file remove-unused-imports
				import java.util.List;
				class Suppressed {}
				""");

		ModernizationReport report = new JRefineEngine().run(source, List.of(new RemoveUnusedImportsTool()), true);

		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertTrue(report.findings().isEmpty());
		assertTrue(Files.readString(source).contains("java.util.List"));
	}

	@Test
	void processesFilesDeterministicallyWithSeverityAndTimings() throws IOException {
		Path sourceDirectory = temporaryDirectory.resolve("parallel");
		Files.createDirectories(sourceDirectory);
		Files.writeString(sourceDirectory.resolve("B.java"), "import java.util.List; class B {}" + LINE_FEED);
		Files.writeString(sourceDirectory.resolve("A.java"), "import java.util.List; class A {}" + LINE_FEED);
		EngineOptions options = new EngineOptions(4, true, Map.of("remove-unused-imports", Severity.ERROR));

		ModernizationReport report = new JRefineEngine().run(sourceDirectory, List.of(new RemoveUnusedImportsTool()),
				false, options);

		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertEquals(List.of("A.java", "B.java"),
				report.findings().stream().map(finding -> finding.path().getFileName().toString()).toList());
		assertTrue(report.findings().stream().allMatch(finding -> finding.severity() == Severity.ERROR));
		assertEquals(1, report.timings().size());
		assertEquals(2, report.timings().getFirst().invocations());
		assertTrue(report.durationNanos() > 0);
	}

	@Test
	void rejectsToolsThatExceedTheEngineTargetJavaRelease() {
		EngineOptions options = new EngineOptions(1, false, Map.of(), JavaVersion.JAVA_17);

		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
				() -> new JRefineEngine().run(temporaryDirectory, List.of(new UseRecordPatternTool()), false, options));

		assertTrue(exception.getMessage().contains("Tool 'use-record-pattern' requires Java 21 but target Java is 17"));
	}

}
