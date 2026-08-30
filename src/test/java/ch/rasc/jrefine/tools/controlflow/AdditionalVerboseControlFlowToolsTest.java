package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdditionalVerboseControlFlowToolsTest {

	@Test
	void removesBreaksAtEndOfArrowRulesAndFinalColonBranch() {
		InspectionContext arrow = TestSources.parse("""
				class Sample { void print(int value) {
				    switch (value) {
				        case 1 -> { System.out.println("one"); break; }
				        default -> { System.out.println("other"); break; }
				    }
				} }
				""");
		ToolResult arrowResult = new RemoveUnnecessaryBreakTool().inspect(arrow, true);
		String arrowOutput = TestSources.print(arrow);
		assertEquals(2, arrowResult.findings().size());
		assertFalse(arrowOutput.contains("break;"), arrowOutput);
		TestSources.parse(arrowOutput);

		InspectionContext colon = TestSources.parse("""
				class Sample { void print(int value) {
				    switch (value) {
				        case 1: System.out.println("one"); break;
				        default: System.out.println("other"); break;
				    }
				} }
				""");
		ToolResult colonResult = new RemoveUnnecessaryBreakTool().inspect(colon, true);
		String colonOutput = TestSources.print(colon);
		assertEquals(1, colonResult.findings().size());
		assertEquals(1, occurrences(colonOutput, "break;"), colonOutput);
		TestSources.parse(colonOutput);
	}

	@Test
	void keepsLoopBreakAndNonFinalColonBranchBreak() {
		InspectionContext context = TestSources.parse("""
				class Sample { void run(int value) {
				    while (true) { break; }
				    switch (value) {
				        case 1: System.out.println("one"); break;
				        default: System.out.println("other");
				    }
				} }
				""");

		assertFalse(new RemoveUnnecessaryBreakTool().inspect(context, true).changed());
	}

	@Test
	void removesDefaultFromExhaustiveLocalEnumSwitches() {
		InspectionContext context = TestSources.parse("""
				enum State { READY, DONE }
				class Sample {
				    int code(State state) {
				        return switch (state) {
				            case READY -> 1;
				            case DONE -> 2;
				            default -> 0;
				        };
				    }
				    void print(State state) {
				        switch (state) {
				            case READY -> System.out.println("ready");
				            case DONE -> System.out.println("done");
				            default -> System.out.println("unknown");
				        }
				    }
				}
				""");
		ToolResult result = new RemoveUnnecessaryEnumSwitchDefaultTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size());
		assertFalse(output.contains("default ->"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsDefaultForIncompleteOrUnknownEnumSwitch() {
		InspectionContext incomplete = TestSources.parse("""
				enum State { READY, DONE }
				class Sample { int code(State state) { return switch (state) {
				    case READY -> 1;
				    default -> 0;
				}; } }
				""");
		InspectionContext unknown = TestSources.parse("""
				import java.time.DayOfWeek;
				class Sample { int code(DayOfWeek day) { return switch (day) {
				    case MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY -> 1;
				    case SATURDAY, SUNDAY -> 2;
				    default -> 0;
				}; } }
				""");

		assertFalse(new RemoveUnnecessaryEnumSwitchDefaultTool().inspect(incomplete, true).changed());
		assertFalse(new RemoveUnnecessaryEnumSwitchDefaultTool().inspect(unknown, true).changed());
	}

	@Test
	void removesBroaderCatchAfterOnlyExceptionWasCaught() {
		String output = apply(new RemoveUnreachableCatchTool(), """
				import java.io.FileNotFoundException;
				import java.io.IOException;
				class Sample { void run() {
				    try {
				        throw new FileNotFoundException();
				    } catch (FileNotFoundException exception) {
				        System.out.println("missing");
				    } catch (IOException exception) {
				        System.out.println("unreachable");
				    }
				} }
				""");

		assertTrue(output.contains("catch (FileNotFoundException exception)"), output);
		assertFalse(output.contains("catch (IOException exception)"), output);
		assertFalse(output.contains("unreachable"), output);
	}

	@Test
	void keepsCatchWhenTryCanPerformOtherWorkOrCommentWouldBeLost() {
		InspectionContext otherWork = TestSources.parse("""
				import java.io.FileNotFoundException;
				import java.io.IOException;
				class Sample { void run() {
				    try {
				        System.out.println();
				        throw new FileNotFoundException();
				    } catch (FileNotFoundException exception) {
				    } catch (IOException exception) {
				    }
				} }
				""");
		InspectionContext comment = TestSources.parse("""
				import java.io.FileNotFoundException;
				import java.io.IOException;
				class Sample { void run() {
				    try { throw new FileNotFoundException(); }
				    catch (FileNotFoundException exception) {}
				    catch (IOException exception) { /* retained explanation */ }
				} }
				""");

		assertFalse(new RemoveUnreachableCatchTool().inspect(otherWork, true).changed());
		assertFalse(new RemoveUnreachableCatchTool().inspect(comment, true).changed());
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
