package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java8ControlFlowMigrationToolsTest {

	@Test
	void replacesEntrySetForEachWithMapForEach() {
		String output = apply(new UseMapForEachTool(), """
				import java.util.Map;
				class Sample { void print(Map<String, Integer> map) {
				    map.entrySet().forEach(entry ->
				            System.out.println(entry.getKey() + ":" + entry.getValue()));
				} }
				""");

		assertTrue(output.contains("map.forEach((key, value) ->"), output);
		assertFalse(output.contains("entry.getKey()"), output);
		assertFalse(output.contains("entry.getValue()"), output);
	}

	@Test
	void keepsEntrySetLoopWhenBodyCapturesAReassignedLocal() {
		InspectionContext context = TestSources.parse("""
				import java.util.ArrayList;
				import java.util.List;
				import java.util.Map;
				class Sample { List<String> collect(Map<String, String> map) {
				    List<String> result = new ArrayList<>();
				    for (Map.Entry<String, String> entry : map.entrySet()) {
				        result.add(entry.getKey() + entry.getValue());
				    }
				    result = List.copyOf(result);
				    return result;
				} }
				""");

		assertFalse(new UseMapForEachTool().inspect(context, true).changed());
	}

	@Test
	void keepsEntrySetLoopWhoseCallsMayRelyOnSurroundingExceptionHandling() {
		InspectionContext context = TestSources.parse("""
				import java.util.Map;
				class Sample { void write(Map<String, String> map) {
				    try {
				        for (Map.Entry<String, String> entry : map.entrySet()) {
				            writeValue(entry.getKey(), entry.getValue());
				        }
				    }
				    catch (Exception ex) {
				        throw new IllegalStateException(ex);
				    }
				}
				void writeValue(String key, String value) throws Exception {}
				}
				""");

		assertFalse(new UseMapForEachTool().inspect(context, true).changed());
	}

	@Test
	void keepsEntrySetLoopWithControlFlowThatCannotMoveIntoLambda() {
		InspectionContext context = TestSources.parse("""
				import java.util.Map;
				class Sample {
				    boolean contains(Map<String, String> map, String expected) {
				        for (Map.Entry<String, String> entry : map.entrySet()) {
				            if (entry.getValue().equals(expected)) return true;
				        }
				        return false;
				    }
				    void printNonEmpty(Map<String, String> map) {
				        for (Map.Entry<String, String> entry : map.entrySet()) {
				            if (entry.getValue().isEmpty()) continue;
				            System.out.println(entry.getKey());
				        }
				    }
				}
				""");

		assertFalse(new UseMapForEachTool().inspect(context, true).changed());
	}

	@Test
	void collapsesMappedAllMatchLoopToStream() {
		String output = apply(new CollapseLoopToStreamTool(), """
				import java.util.List;
				class Sample { boolean check(List<String> data) {
				    for (String element : data) {
				        String trimmed = element.trim();
				        if (!trimmed.startsWith("xyz")) return false;
				    }
				    return true;
				} }
				""");

		assertTrue(output.contains("data.stream().map(element -> element.trim())"), output);
		assertTrue(output.contains("allMatch(trimmed -> trimmed.startsWith(\"xyz\"))"), output);
		assertFalse(output.contains("for (String element"), output);
	}

	@Test
	void keepsLoopWhenItsPredicateCapturesAReassignedLocal() {
		InspectionContext context = TestSources.parse("""
				import java.util.List;
				class Sample { boolean check(List<String> data, String prefix) {
				    String normalized = prefix;
				    normalized = normalized.trim();
				    for (String element : data) {
				        if (element.startsWith(normalized)) return true;
				    }
				    return false;
				} }
				""");

		assertFalse(new CollapseLoopToStreamTool().inspect(context, true).changed());
	}

	@Test
	void convertsCloseOnlyFinallyToTryWithResources() {
		String output = apply(new UseTryWithResourcesTool(), """
				import java.io.PrintStream;
				class Sample { void print(String fileName) throws Exception {
				    PrintStream stream = new PrintStream(fileName);
				    try {
				        stream.print(true);
				    } finally {
				        stream.close();
				    }
				} }
				""");

		assertTrue(output.contains("try (PrintStream stream = new PrintStream(fileName))"), output);
		assertFalse(output.contains("finally"), output);
		assertFalse(output.contains("stream.close()"), output);
	}

	@Test
	void keepsResourceDeclarationWhenItIsUsedAfterTry() {
		InspectionContext context = TestSources.parse("""
				import java.io.PrintStream;
				class Sample { void print(String fileName) throws Exception {
				    PrintStream stream = new PrintStream(fileName);
				    try { stream.print(true); } finally { stream.close(); }
				    System.out.println(stream);
				} }
				""");

		assertFalse(new UseTryWithResourcesTool().inspect(context, true).changed());
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
