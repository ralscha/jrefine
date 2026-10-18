package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.Severity;
import ch.rasc.jrefine.api.JavaVersion;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.tools.expressions.UseListSortTool;
import ch.rasc.jrefine.tools.expressions.RemoveRedundantStreamOptionalStepTool;
import ch.rasc.jrefine.tools.declarations.RemoveUnnecessaryModifiersTool;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.Position;
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
	void revisitsEarlierToolsWhenLaterRewritesExposeNewFixes() throws IOException {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source, """
				import java.util.Collections;
				import java.util.List;
				class Sample {
				    void sort(List<String> values) { Collections.sort(values); }
				}
				""");
		List<InspectionTool> tools = List.of(new RemoveUnusedImportsTool(), new UseListSortTool());
		ModernizationReport report = new JRefineEngine().run(source, tools, true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertEquals(2, report.findings().size());
		assertTrue(report.findings().stream().allMatch(ReportedFinding::fixed));
		assertFalse(Files.readString(source).contains("java.util.Collections"));
		String transformed = Files.readString(source);
		ModernizationReport repeat = new JRefineEngine().run(source, tools, true);
		assertTrue(repeat.errors().isEmpty());
		assertTrue(repeat.findings().isEmpty());
		assertEquals(0, repeat.changedFiles());
		assertEquals(transformed, Files.readString(source));
	}

	@Test
	void removesNestedParenthesesWithoutOverlappingEdits() throws IOException {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source, """
				class Sample {
				    boolean allowed(boolean owner, boolean admin, boolean enabled) {
				        return (owner || (admin && enabled));
				    }
				}
				""");
		ModernizationReport report = new JRefineEngine().run(source, List.of(new RemoveUnnecessaryParenthesesTool()),
				true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertTrue(Files.readString(source).contains("return owner || admin && enabled;"));
		assertEquals(2, report.findings().size());
	}

	@Test
	void preservesAccessOfProtectedOverridesInFinalClasses() throws IOException {
		Path source = temporaryDirectory.resolve("Sample.java");
		String original = """
				class Base { protected String value() { return "base"; } }
				final class Sample extends Base {
				    @Override protected String value() { return "child"; }
				}
				final class WithoutAnnotation extends Base {
				    protected String value() { return "child"; }
				}
				final class Copies implements Cloneable {
				    @Override protected Object clone() throws CloneNotSupportedException { return super.clone(); }
				}
				""";
		Files.writeString(source, original);
		ModernizationReport report = new JRefineEngine().run(source, List.of(new RemoveUnnecessaryModifiersTool()),
				true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertEquals(original, Files.readString(source));
		assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "-d", temporaryDirectory.toString(), source.toString()));
	}

	@Test
	void preservesGenericWideningAndStreamTraversal() throws Exception {
		Path source = temporaryDirectory.resolve("Sample.java");
		String original = """
				import java.util.List;
				import java.util.Optional;
				import java.util.stream.Stream;
				public class Sample {
				    Stream<Object> widen(Stream<String> values) { return values.map(value -> value); }
				    Optional<Object> widenOptional(Optional<String> value) { return value.map(item -> item); }
				    List<Object> explicit(Stream<String> values) { return values.<Object>map(value -> value).toList(); }
				    List<Object> typed(Stream<String> values) { return values.map((Object value) -> value).toList(); }
				    static int visits;
				    public static int visitCount() {
				        Stream.of(1, 2).peek(value -> visits++).filter(value -> true).count();
				        return visits;
				    }
				}
				""";
		Files.writeString(source, original);
		ModernizationReport report = new JRefineEngine().run(source,
				List.of(new RemoveRedundantStreamOptionalStepTool()), true);
		assertTrue(report.errors().isEmpty(), report.errors().toString());
		assertEquals(original, Files.readString(source));
		assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler()
			.run(null, null, null, "-d", temporaryDirectory.toString(), source.toString()));
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] { temporaryDirectory.toUri().toURL() })) {
			assertEquals(2, loader.loadClass("Sample").getMethod("visitCount").invoke(null));
		}
	}

	@Test
	void rollsBackConflictingToolsAcrossPasses() throws IOException {
		Path source = temporaryDirectory.resolve("Sample.java");
		String original = "class Sample { int value = 0; }";
		Files.writeString(source, original);
		ModernizationReport report = new JRefineEngine().run(source,
				List.of(replaceInteger("0", "1"), replaceInteger("1", "0")), true);
		assertEquals(1, report.errors().size());
		assertTrue(report.errors().getFirst().message().contains("do not converge"));
		assertEquals(0, report.changedFiles());
		assertTrue(report.findings().stream().noneMatch(ReportedFinding::fixed));
		assertEquals(original, Files.readString(source));
	}

	private static InspectionTool replaceInteger(String from, String to) {
		return new InspectionTool() {
			@Override
			public String id() {
				return "replace-" + from;
			}

			@Override
			public String description() {
				return "Test conflicting transformations";
			}

			@Override
			public ToolResult inspect(InspectionContext context, boolean applyFixes) {
				var literal = context.compilationUnit()
					.findAll(IntegerLiteralExpr.class)
					.stream()
					.filter(value -> value.getValue().equals(from))
					.findFirst();
				if (literal.isEmpty()) {
					return new ToolResult(List.of(), false);
				}
				if (applyFixes) {
					context.editor().replace(literal.orElseThrow().getRange().orElseThrow(), to);
				}
				return ToolResult.of(List.of(Finding.at(literal.orElseThrow(), "Replace integer")), applyFixes);
			}
		};
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
	void discardsAllFixesWhenAToolQueuesUnreportedEdits() throws IOException {
		Path source = temporaryDirectory.resolve("Sample.java");
		String original = "import java.util.List; class Sample {}";
		Files.writeString(source, original);
		InspectionTool broken = new InspectionTool() {
			@Override
			public String id() {
				return "broken";
			}

			@Override
			public String description() {
				return "Queues an edit without reporting it";
			}

			@Override
			public ToolResult inspect(InspectionContext context, boolean applyFixes) {
				context.editor().insert(Position.HOME, "// unexpected edit\n");
				return new ToolResult(List.of(), false);
			}
		};
		ModernizationReport report = new JRefineEngine().run(source, List.of(new RemoveUnusedImportsTool(), broken),
				true);
		assertEquals(1, report.errors().size());
		assertEquals(0, report.changedFiles());
		assertTrue(report.findings().stream().noneMatch(ReportedFinding::fixed));
		assertEquals(original, Files.readString(source));
	}

	@Test
	void validatesSourceInApplyModeEvenWhenNoToolsAreSelected() throws IOException {
		Path source = temporaryDirectory.resolve("Broken.java");
		String original = "class Broken {";
		Files.writeString(source, original);
		ModernizationReport report = new JRefineEngine().run(source, List.of(), true);
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
