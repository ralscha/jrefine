package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopMigrationToolsTest {

	@Test
	void replacesIteratorWhileWithEnhancedFor() {
		String output = apply(new UseEnhancedForWhileTool(), """
				import java.util.Iterator;
				import java.util.List;
				class Sample { void visit(List<String> values) {
				    Iterator<String> iterator = values.iterator();
				    while (iterator.hasNext()) {
				        String value = iterator.next();
				        System.out.println(value);
				    }
				} }
				""");
		assertTrue(output.contains("for (String value : values)"), output);
		assertFalse(output.contains("Iterator<String> iterator"), output);
	}

	@Test
	void replacesIteratorRemovalWithRemoveIf() {
		String output = apply(new UseRemoveIfTool(), """
				import java.util.Iterator;
				import java.util.List;
				class Sample { void clean(List<String> values) {
				    for (Iterator<String> iterator = values.iterator(); iterator.hasNext();) {
				        String value = iterator.next();
				        if (value.isBlank()) iterator.remove();
				    }
				} }
				""");
		assertTrue(output.contains("values.removeIf(value -> value.isBlank());"), output);
	}

	@Test
	void replacesIndexedTransformationWithReplaceAll() {
		String output = apply(new UseListReplaceAllTool(), """
				import java.util.List;
				class Sample { void trim(List<String> values) {
				    for (int i = 0; i < values.size(); i++) {
				        values.set(i, values.get(i).trim());
				    }
				} }
				""");
		assertTrue(output.contains("values.replaceAll(value -> value.trim());"), output);
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
