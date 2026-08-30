package ch.rasc.jrefine.tools;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.tools.controlflow.MergeDuplicateSwitchBranchesTool;
import ch.rasc.jrefine.tools.controlflow.MergeIdenticalCatchBranchesTool;
import ch.rasc.jrefine.tools.controlflow.UseEnhancedSwitchTool;
import ch.rasc.jrefine.tools.controlflow.UseSwitchExpressionTool;
import ch.rasc.jrefine.tools.declarations.AddSerialAnnotationTool;
import ch.rasc.jrefine.tools.declarations.RemoveRedundantLocalVariableTool;
import ch.rasc.jrefine.tools.declarations.RemoveUnnecessaryModifiersTool;
import ch.rasc.jrefine.tools.declarations.RemoveUnusedAssignmentsTool;
import ch.rasc.jrefine.tools.expressions.UseInstanceofPatternsTool;
import ch.rasc.jrefine.tools.expressions.UsePatternVariableTool;
import ch.rasc.jrefine.tools.syntax.FixJavadocParagraphsTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ch.rasc.jrefine.analysis.LineEndingSupport.LINE_FEED;

class LanguageMigrationToolsTest {

	@Test
	void mergesIdenticalCatchBranches() {
		String output = apply(new MergeIdenticalCatchBranchesTool(), """
				import java.io.IOException;
				import java.sql.SQLException;
				class Sample { void run() {
				    try { work(); }
				    catch (IOException error) { System.out.println(error.getMessage()); }
				    catch (SQLException problem) { System.out.println(problem.getMessage()); }
				} void work() throws IOException {} }
				""");
		assertTrue(output.contains("catch (IOException | SQLException error)"), output);
		assertTrue(occurrences(output, "catch (") == 1, output);
	}

	@Test
	void marksJavadocParagraphBreaks() {
		String output = apply(new FixJavadocParagraphsTool(), """
				/**
				 * First paragraph.
				 *
				 * Second paragraph.
				 *
				 * @return a value
				 */
				class Sample {}
				""");
		assertTrue(output.contains(" * <p>" + LINE_FEED + " * Second paragraph."), output);
		assertFalse(output.contains(" * <p>" + LINE_FEED + " * @return"), output);
	}

	@Test
	void movesCastDeclarationIntoPatternVariable() {
		String output = apply(new UsePatternVariableTool(), """
				class Sample { int length(Object value) {
				    if (value instanceof String) {
				        String text = (String) value;
				        return text.length();
				    }
				    return 0;
				} }
				""");
		assertTrue(output.contains("value instanceof String text"), output);
		assertFalse(output.contains("String text ="), output);
	}

	@Test
	void replacesRepeatedCastWithInstanceofPattern() {
		String output = apply(new UseInstanceofPatternsTool(), """
				class Sample { boolean blank(Object value) {
				    return value instanceof String && ((String) value).isBlank();
				} }
				""");
		assertTrue(output.contains("value instanceof String string"), output);
		assertTrue(output.contains("string).isBlank()") || output.contains("string.isBlank()"), output);
		assertFalse(output.contains("(String) value"), output);
	}

	@Test
	void inlinesImmediatelyReturnedLocal() {
		String output = apply(new RemoveRedundantLocalVariableTool(), """
				class Sample { int size(String text) {
				    int result = text.length();
				    return result;
				} }
				""");
		assertTrue(output.contains("return text.length();"), output);
		assertFalse(output.contains("int result"), output);
	}

	@Test
	void removesInterfaceAndFinalClassModifiers() {
		String output = apply(new RemoveUnnecessaryModifiersTool(), """
				abstract interface Contract {
				    public static final int VALUE = 1;
				    public abstract void run();
				}
				final class Sample { private final void helper() {} }
				""");
		assertTrue(output.contains("interface Contract"), output);
		assertTrue(output.contains("int VALUE = 1"), output);
		assertTrue(output.contains("void run()"), output);
		assertFalse(output.contains("private final void"), output);
	}

	@Test
	void addsSerialAnnotationAndImport() {
		String output = apply(new AddSerialAnnotationTool(), """
				import java.io.Serializable;
				class Sample implements Serializable {
				    private static final long serialVersionUID = 1L;
				    private Object readResolve() { return this; }
				}
				""");
		assertTrue(output.contains("import java.io.Serial;"), output);
		assertTrue(occurrences(output, "@Serial") == 2, output);
	}

	@Test
	void removesOverwrittenAssignmentButPreservesCall() {
		String output = apply(new RemoveUnusedAssignmentsTool(), """
				class Sample { int compute() { return 1; } void run() {
				    int value;
				    value = compute();
				    value = 2;
				    System.out.println(value);
				} }
				""");
		assertTrue(output.contains("compute();"), output);
		assertFalse(output.contains("value = compute()"), output);
	}

	@Test
	void keepsChainedAssignmentsThatReadThePreviousValue() {
		InspectionContext context = TestSources.parse("""
				class Sample { String clean(String input) {
				    String value = input;
				    value = value.replace("a", "b");
				    value = value.replace("c", "d");
				    return value;
				} }
				""");

		ToolResult result = new RemoveUnusedAssignmentsTool().inspect(context, true);

		assertFalse(result.changed());
		assertTrue(result.findings().isEmpty());
	}

	@Test
	void convertsColonSwitchToArrowSwitch() {
		String output = apply(new UseEnhancedSwitchTool(), """
				class Sample { void run(int value) {
				    switch (value) {
				        case 1: System.out.println("one"); break;
				        case 2: System.out.println("two"); break;
				        default: System.out.println("other");
				    }
				} }
				""");
		assertTrue(output.contains("case 1 ->"), output);
		assertTrue(output.contains("default ->"), output);
		assertFalse(output.contains("break;"), output);
	}

	@Test
	void combinesFallThroughLabelsWhenEnhancingSwitch() {
		String output = apply(new UseEnhancedSwitchTool(), """
				class Sample { void run(int value) {
				    switch (value) {
				        case 1:
				        case 2: System.out.println("small"); break;
				        default: System.out.println("other");
				    }
				} }
				""");
		assertTrue(output.contains("case 1, 2 ->"), output);
	}

	@Test
	void keepsColonSwitchWhenLaterCaseUsesEarlierCaseLocal() {
		InspectionContext context = TestSources.parse("""
				import java.util.List;
				class Sample { Object run(int value) {
				    switch (value) {
				        case 1:
				            List<String> result = List.of("one");
				            return result;
				        default:
				            result = List.of("other");
				            return result;
				    }
				} }
				""");

		ToolResult result = new UseEnhancedSwitchTool().inspect(context, true);

		assertFalse(result.changed());
		assertTrue(TestSources.print(context).contains("case 1:"));
	}

	@Test
	void convertsReturningSwitchToSwitchExpression() {
		String output = apply(new UseSwitchExpressionTool(), """
				class Sample { String label(int value) {
				    switch (value) {
				        case 1: return "one";
				        default: return "other";
				    }
				} }
				""");
		assertTrue(output.contains("return switch"), output);
		assertTrue(output.contains("case 1 ->"), output);
		assertTrue(output.contains("\"one\";"), output);
	}

	@Test
	void convertsAnAlreadyEnhancedReturningSwitchToExpression() {
		String enhanced = apply(new UseEnhancedSwitchTool(), """
				class Sample { String label(int value) {
				    switch (value) {
				        case 1: return "one";
				        default: return "other";
				    }
				} }
				""");
		String output = apply(new UseSwitchExpressionTool(), enhanced);
		assertTrue(output.contains("return switch"), output);
	}

	@Test
	void mergesDuplicateArrowSwitchBranches() {
		String output = apply(new MergeDuplicateSwitchBranchesTool(), """
				class Sample { void run(int value) {
				    switch (value) {
				        case 1 -> System.out.println("same");
				        case 2 -> System.out.println("same");
				        default -> System.out.println("other");
				    }
				} }
				""");
		assertTrue(output.contains("case 1, 2 ->"), output);
		assertTrue(occurrences(output, "println(\"same\")") == 1, output);
	}

	@Test
	void doesNotReportAlreadyGroupedColonSwitchLabels() {
		InspectionContext context = TestSources.parse("""
				class Sample { void run(int value) {
				    switch (value) {
				        case 1:
				        case 2:
				            System.out.println("same");
				            break;
				        default:
				            break;
				    }
				} }
				""");

		ToolResult result = new MergeDuplicateSwitchBranchesTool().inspect(context, true);

		assertFalse(result.changed());
		assertTrue(result.findings().isEmpty());
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
