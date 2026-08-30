package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFlowToolsTest {

	@Test
	void removesOnlyFinalBareReturns() {
		String output = apply(new RemoveUnnecessaryReturnTool(), """
				class Sample {
				    Sample() { return; }
				    void done() {
				        System.out.println("done");
				        return;
				    }
				    void conditional(boolean stop) {
				        if (stop) return;
				        System.out.println("continue");
				    }
				}
				""");

		assertEquals(1, occurrences(output, "return;"), output);
		assertTrue(output.contains("if (stop) return;"), output);
	}

	@Test
	void convertsEligibleArrayAndListLoopsButKeepsIndexDependentLoops() {
		InspectionContext context = TestSources.parse("""
				import java.util.List;
				import java.util.Iterator;
				class Sample {
				    void visit(String[] names, List<String> values) {
				        for (int i = 0; i < names.length; i++) {
				            String name = names[i];
				            System.out.println(name);
				        }
				        for (int i = 0; i < values.size(); i++) {
				            String value = values.get(i);
				            System.out.println(i + value);
				        }
				        for (int j = 0; j < values.size(); j++) {
				            String value = values.get(j);
				            System.out.println(value);
				        }
				        for (Iterator<String> iterator = values.iterator(); iterator.hasNext();) {
				            String entry = iterator.next();
				            System.out.println(entry);
				        }
				    }
				}
				""");
		ToolResult result = new UseEnhancedForTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(3, result.findings().size());
		assertTrue(output.contains("for (String name : names)"), output);
		assertTrue(output.contains("for (String value : values)"), output);
		assertTrue(output.contains("for (String entry : values)"), output);
		assertTrue(output.contains("for (int i = 0; i < values.size(); i++)"), output);
		TestSources.parse(output);
	}

	@Test
	void removesUnusedAndTargetNeutralLabelsButKeepsRequiredOuterBreak() {
		String output = apply(new SimplifyLabelsTool(), """
				class Sample {
				    void run(boolean active) {
				        unused: while (active) { break; }
				        same: while (active) {
				            if (active) break same;
				            continue same;
				        }
				        outer: while (active) {
				            while (active) { break outer; }
				        }
				        kept /* label meaning */ : while (active) { break; }
				    }
				}
				""");

		assertFalse(output.contains("unused:"), output);
		assertFalse(output.contains("same:"), output);
		assertTrue(output.contains("break;"), output);
		assertTrue(output.contains("continue;"), output);
		assertTrue(output.contains("outer:"), output);
		assertTrue(output.contains("break outer;"), output);
		assertTrue(output.contains("kept /* label meaning */ :"), output);
	}

	@Test
	void removesOnlyAContinueAtTheEndOfItsOwnLoopBody() {
		String output = apply(new RemoveUnnecessaryContinueTool(), """
				class Sample {
				    void run(boolean active) {
				        while (active) {
				            if (active) continue;
				            continue;
				        }
				        outer: while (active) {
				            while (active) { continue outer; }
				        }
				    }
				}
				""");

		assertEquals(1, occurrences(output, "continue;"), output);
		assertTrue(output.contains("continue outer;"), output);
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
