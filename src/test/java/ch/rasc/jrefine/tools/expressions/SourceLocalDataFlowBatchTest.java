package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.declarations.NarrowVariableScopeTool;
import ch.rasc.jrefine.tools.declarations.ReportConstantParameterTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceLocalDataFlowBatchTest {

	@Test
	void movesAnInertDeclarationImmediatelyBeforeItsFirstUse() {
		String output = apply(new NarrowVariableScopeTool(), """
				class Sample {
				    void run() {
				        int code = 7;
				        log();
				        use(code);
				    }
				    void log() {}
				    void use(int value) {}
				}
				""");

		assertTrue(output.indexOf("log();") < output.indexOf("int code = 7;"), output);
		assertTrue(output.indexOf("int code = 7;") < output.indexOf("use(code);"), output);
	}

	@Test
	void keepsEffectfulCapturedAndAlreadyNarrowDeclarations() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    void run() {
				        Object created = new Object();
				        log();
				        use(created);
				        int captured = 1;
				        log();
				        Runnable action = () -> use(captured);
				        int adjacent = 2;
				        use(adjacent);
				    }
				    void log() {}
				    void use(Object value) {}
				    void use(int value) {}
				}
				""");

		ToolResult result = new NarrowVariableScopeTool().inspect(context, true);

		assertFalse(result.changed());
		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void substitutesTypePreservingConstantsInsideProvenBranches() {
		String output = apply(new UseKnownConstantTool(), """
				class Sample {
				    void run(int code, String text) {
				        if (code == 7) {
				            use(code);
				        }
				        if (text != null) {
				            use(text);
				        }
				        else {
				            use(text);
				        }
				        while (code == 7) {
				            use(code);
				            break;
				        }
				    }
				    void use(int value) {}
				    void use(String value) {}
				}
				""");

		assertTrue(occurrences(output, "use(7)") == 2, output);
		assertTrue(output.contains("use(((String) (null)))"), output);
		assertTrue(output.contains("if (text != null)"), output);
	}

	@Test
	void keepsFloatingMutableFieldAndDeferredConstantUses() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    int field;
				    void run(double number, int code) {
				        if (number == 0.0) { use(number); }
				        if (field == 1) { use(field); }
				        if (code == 7) {
				            Runnable action = () -> use(code);
				        }
				        code = 8;
				    }
				    void use(double value) {}
				    void use(int value) {}
				}
				""");

		ToolResult result = new UseKnownConstantTool().inspect(context, true);

		assertFalse(result.changed());
		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsFoldableAndDirectlyInvokedFunctionalExpressions() {
		ToolResult result = inspect(new ReportFunctionalExpressionRedundancyTool(), """
				import java.util.function.Predicate;
				class Sample {
				    void run(Runnable task, Predicate<String> predicate) {
				        Runnable copiedTask = task::run;
				        Predicate<String> copiedPredicate = value -> predicate.test(value);
				        boolean empty = ((Predicate<String>) value -> value.isEmpty()).test("");
				    }
				}
				""");

		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		assertTrue(messages.stream().filter(message -> message.contains("can be folded")).count() == 2,
				messages.toString());
		assertTrue(messages.stream().filter(message -> message.contains("invoke it directly")).count() == 1,
				messages.toString());
	}

	@Test
	void ignoresDifferentAndSourceLocalFunctionalTargets() {
		ToolResult result = inspect(new ReportFunctionalExpressionRedundancyTool(), """
				import java.util.function.Function;
				import java.util.function.Predicate;
				class Sample {
				    Function<String, Boolean> adapt(Predicate<String> predicate) {
				        return predicate::test;
				    }
				}
				class Runnable {
				    void run() {}
				    void copy(Runnable task) { Runnable copy = task::run; }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsOnlyParametersWithTheSameLiteralAtEveryPrivateCall() {
		ToolResult result = inspect(new ReportConstantParameterTool(), """
				class Sample {
				    void run() {
				        write("first", 1);
				        this.write("second", 1);
				    }
				    private void write(String value, int mode) {}
				}
				""");

		assertTrue(result.findings().size() == 1, result.findings().toString());
		assertTrue(result.findings().getFirst().message().contains("mode"), result.findings().toString());
	}

	@Test
	void keepsDifferingOverloadedAndReferencedParameters() {
		ToolResult result = inspect(new ReportConstantParameterTool(), """
				import java.util.function.Consumer;
				class Sample {
				    void run(boolean first) {
				        choose(first ? 1 : 2);
				        overloaded(1);
				        Consumer<Integer> consumer = this::referenced;
				    }
				    private void choose(int value) {}
				    private void overloaded(int value) {}
				    private void overloaded(String value) {}
				    private void referenced(int value) {}
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		return tool.inspect(TestSources.parse(source), false);
	}

	private static String apply(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertTrue(result.changed(), "Expected " + tool.id() + " to change the source");
		String output = TestSources.print(context);
		TestSources.parse(output);
		return output;
	}

	private static int occurrences(String value, String needle) {
		return (value.length() - value.replace(needle, "").length()) / needle.length();
	}

}
