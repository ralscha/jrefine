package ch.rasc.jrefine.tools.syntax;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ch.rasc.jrefine.analysis.LineEndingSupport.LINE_FEED;

class SyntaxToolsTest {

	@Test
	void removesUnnecessarySemicolonsButKeepsAnEmptyLoopBody() {
		String output = apply(new RemoveUnnecessarySemicolonsTool(), """
				class Sample {
				    ;
				    void run(boolean waiting) {
				        ;
				        while (waiting);
				    }
				};
				""");

		assertFalse(output.lines().anyMatch(line -> ";".equals(line.strip())), output);
		assertTrue(output.contains("while (waiting);"), output);
		assertFalse(output.stripTrailing().endsWith(";"), output);
	}

	@Test
	void normalizesVariableParameterAndMethodArrayDeclarations() {
		String output = apply(new NormalizeArrayDeclarationsTool(), """
				class Sample {
				    String values[];
				    String[] convert(String input[])[] { return null; }
				}
				""");

		assertTrue(output.contains("String[] values"), output);
		assertTrue(output.contains("String[][] convert(String[] input)"), output);
	}

	@Test
	void removesOnlyPrecedenceNeutralParentheses() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    int calculate(int a, int b, int c) {
				        int one = (1);
				        return (a + b) * c;
				    }
				}
				""");
		ToolResult result = new RemoveUnnecessaryParenthesesTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("int one = 1;"), output);
		assertTrue(output.contains("return (a + b) * c;"), output);
	}

	@Test
	void sortsAdjacentModifiersAndLeavesCommentedSequencesAlone() {
		String output = apply(new SortModifiersTool(), """
				class Sample {
				    static public final int VALUE = 1;
				    static /* keep placement */ public void run() {}
				}
				""");

		assertTrue(output.contains("public static final int VALUE"), output);
		assertTrue(output.contains("static /* keep placement */ public"), output);
	}

	@Test
	void simplifiesMarkerAndSingleValueAnnotations() {
		String output = apply(new SimplifyAnnotationsTool(), """
				@interface Flag {}
				@interface Name { String value(); }
				@Flag()
				@Name(value = "sample")
				class Sample {}
				""");

		assertTrue(output.contains("@Flag" + LINE_FEED), output);
		assertTrue(output.contains("@Name(\"sample\")"), output);
	}

	@Test
	void usesArrayInitializerShorthandOnlyForMatchingUnannotatedTypes() {
		String output = apply(new SimplifyArrayInitializersTool(), """
				class Sample {
				    int[] values = new int[]{1, 2};
				    Object other = new int[]{3};
				    int @Mark [] annotated = new int @Mark [] {4};
				}
				@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
				@interface Mark {}
				""");

		assertTrue(output.contains("int[] values = {1, 2}"), output);
		assertTrue(output.contains("Object other = new int[]{3}"), output);
		assertTrue(output.contains("new int @Mark [] {4}"), output);
	}

	private static String apply(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertTrue(result.changed(), "Expected " + tool.id() + " to change the source");
		String output = TestSources.print(context);
		TestSources.parse(output);
		return output;
	}

}
