package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoveRedundantArrayLengthCheckToolTest {

	private final RemoveRedundantArrayLengthCheckTool tool = new RemoveRedundantArrayLengthCheckTool();

	@Test
	void unwrapsAForEachLoopGuardedByANonEmptyCheck() {
		InspectionContext context = TestSources.parse("""
				class Sample { void print(String[] values) {
				    if (values.length != 0) {
				        for (String value : values) {
				            System.out.println(value);
				        }
				    }
				} }
				""");

		ToolResult result = tool.inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertFalse(output.contains("values.length"), output);
		assertTrue(output.contains("for (String value : values)"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsChecksThatGuardAdditionalWork() {
		InspectionContext context = TestSources.parse("""
				class Sample { void print(String[] values) {
				    if (values.length > 0) {
				        System.out.println("values");
				        for (String value : values) System.out.println(value);
				    }
				} }
				""");

		assertFalse(tool.inspect(context, true).changed());
	}

}
