package ch.rasc.jrefine.tools.types;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseDiamondOperatorToolTest {

	private final UseDiamondOperatorTool tool = new UseDiamondOperatorTool();

	@Test
	void replacesExplicitConstructorArgumentsWithDiamond() {
		InspectionContext context = TestSources.parse("""
				import java.util.ArrayList;
				import java.util.HashMap;
				import java.util.List;
				import java.util.Map;

				class Sample {
				    List<String> names = new ArrayList<String>();
				    Map<String, Integer> counts = new HashMap<String, Integer>();
				}
				""");

		ToolResult result = tool.inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertEquals(2, result.findings().size());
		assertTrue(output.contains("new ArrayList<>()"), output);
		assertTrue(output.contains("new HashMap<>()"));
		assertFalse(output.contains("new ArrayList<String>()"));
	}

	@Test
	void skipsContextsWhereDiamondCouldChangeTheInferredType() {
		InspectionContext context = TestSources.parse("""
				import java.util.ArrayList;

				class Sample {
				    void run() {
				        var inferred = new ArrayList<String>();
				        int size = new ArrayList<String>().size();
				        ArrayList<String> anonymous = new ArrayList<String>() {};
				        int conditional = (true
				                ? new ArrayList<String>()
				                : new ArrayList<String>()).size();
				    }
				}
				""");

		ToolResult result = tool.inspect(context, true);

		assertFalse(result.changed());
		assertTrue(result.findings().isEmpty());
		assertEquals(5, occurrences(TestSources.print(context), "new ArrayList<String>()"));
	}

	@Test
	void checkModeReportsWithoutChangingTheTree() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    java.util.List<String> names = new java.util.ArrayList<String>();
				}
				""");

		ToolResult result = tool.inspect(context, false);

		assertFalse(result.changed());
		assertEquals(1, result.findings().size());
		assertTrue(TestSources.print(context).contains("new java.util.ArrayList<String>()"));
	}

	private static int occurrences(String value, String needle) {
		return (value.length() - value.replace(needle, "").length()) / needle.length();
	}

}
