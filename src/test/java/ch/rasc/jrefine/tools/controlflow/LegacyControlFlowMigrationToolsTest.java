package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyControlFlowMigrationToolsTest {

	@Test
	void replacesIntegralIfChainWithArrowSwitch() {
		String output = apply(new UseSwitchForIfTool(), """
				class Sample { String label(int value) {
				    if (value == 1) return "one";
				    else if (2 == value) return "two";
				    else return "other";
				} }
				""");

		assertTrue(output.contains("switch (value)"), output);
		assertTrue(output.contains("case 1 -> { return \"one\"; }"), output);
		assertTrue(output.contains("case 2 -> { return \"two\"; }"), output);
		assertTrue(output.contains("default -> { return \"other\"; }"), output);
	}

	@Test
	void keepsUnstableIfSelectors() {
		InspectionContext context = TestSources.parse("""
				class Sample { int next() { return 1; } void run() {
				    if (next() == 1) first(); else if (next() == 2) second();
				} void first() {} void second() {} }
				""");

		assertFalse(new UseSwitchForIfTool().inspect(context, true).changed());
	}

	@Test
	void replacesAppendLoopWithStringRepeat() {
		String output = apply(new UseStringRepeatTool(), """
				class Sample { void append(StringBuilder builder, int count, Object value) {
				    for (int index = 0; index < count; index++) {
				        builder.append(value);
				    }
				} }
				""");

		assertTrue(output.contains("builder.append(String.valueOf(value).repeat(Math.max(0, count)));"), output);
		assertFalse(output.contains("for (int index"), output);
	}

	@Test
	void keepsAppendLoopsThatReevaluateAnExpression() {
		InspectionContext context = TestSources.parse("""
				class Sample { String next() { return "x"; }
				    void append(StringBuilder builder, int count) {
				        for (int index = 0; index < count; index++) builder.append(next());
				    }
				}
				""");

		assertFalse(new UseStringRepeatTool().inspect(context, true).changed());
	}

	@Test
	void migratesHashtableEnumerationToIterator() {
		String output = apply(new UseIteratorForEnumerationTool(), """
				import java.util.Enumeration;
				import java.util.Hashtable;
				class Sample { void visit(Hashtable<String, Integer> values) {
				    Enumeration<String> keys = values.keys();
				    while (keys.hasMoreElements()) {
				        String key = keys.nextElement();
				        System.out.println(key);
				    }
				} }
				""");

		assertTrue(output.contains("import java.util.Iterator;"), output);
		assertTrue(output.contains("Iterator<String> keys = values.keySet().iterator();"), output);
		assertTrue(output.contains("keys.hasNext()"), output);
		assertTrue(output.contains("keys.next()"), output);
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
