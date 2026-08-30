package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import java.util.List;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.tools.controlflow.ReportEmbeddedResourcePerformanceTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import ch.rasc.jrefine.api.ToolResult;

class PerformanceInspectionBatchTest {

	@Test
	void optimizesSafePerformanceExpressions() {
		String output = apply(new OptimizePerformanceExpressionsTool(), """
				import java.io.File;
				import java.io.FileInputStream;
				import java.io.InputStream;
				import java.util.List;
				import java.util.Random;
				class Sample {
				    enum Color { RED, BLUE }
				    Object[] array(List<String> values) { return values.toArray(new String[0]); }
				    InputStream open(File file) throws Exception { return new FileInputStream(file); }
				    Boolean box(boolean value) { return new Boolean(value); }
				    Class<?> type() { return new Sample().getClass(); }
				    int find(String text) { return text.indexOf("x"); }
				    void append(StringBuilder builder, String value) { builder.append("x" + value); }
				    int parse(String text) { return new Integer(text).intValue(); }
				    String format(int value) { return new Integer(value).toString(); }
				    int random(Random random) { return (int) (random.nextDouble() * 10); }
				    boolean same(Color left, Color right) { return left.equals(right); }
				}
				""");

		assertTrue(output.contains("String[]::new"), output);
		assertTrue(output.contains("Files.newInputStream(file.toPath())"), output);
		assertTrue(output.contains("Boolean.valueOf(value)"), output);
		assertTrue(output.contains("Sample.class"), output);
		assertTrue(output.contains("indexOf('x')"), output);
		assertTrue(output.contains("builder.append(\"x\").append(value)"), output);
		assertTrue(output.contains("Integer.parseInt(text)"), output);
		assertTrue(output.contains("Integer.toString(value)"), output);
		assertTrue(output.contains("random.nextInt(10)"), output);
		assertTrue(output.contains("left == right"), output);
	}

	@Test
	void reportsCollectionPerformanceFamilies() {
		ToolResult result = inspect(new ReportCollectionPerformanceTool(), """
				import java.util.*;
				class Sample {
				    enum Color { RED }
				    Map<Color, String> map = new HashMap<>();
				    Set<Color> colors = new HashSet<>();
				    List<String> values = new ArrayList<>();
				    void use(Set<String> set, List<String> list, String[] array) {
				        Arrays.asList("x");
				        list.containsAll(values);
				        set.removeAll(list);
				        for (int i = list.size() - 1; i >= 0; i--) list.remove(i);
				        for (String value : array) values.add(value);
				        for (Color key : map.keySet()) System.out.println(map.get(key));
				    }
				}
				""");
		assertMessages(result, "EnumMap", "EnumSet", "containsAll", "removeAll", "List.remove", "array-to-collection",
				"entrySet");
		assertNoMessages(result, "Collection is created without an initial capacity");
	}

	@Test
	void reportsHighConfidenceStringPerformanceFamilies() {
		ToolResult strings = inspect(new ReportStringPerformanceTool(), """
				class Sample { void use(String text, String value, StringBuilder builder) {
				    StringBuilder local = new StringBuilder();
				    text.matches("[a-z]+");
				    text.startsWith("x");
				    builder.append(text + value);
				    String one = "x" + value;
				    for (int i = 0; i < 2; i++) text += value;
				} }
				""");
		assertMessages(strings, "intermediate String", "StringBuilder", "in a loop");
		assertNoMessages(strings, "initial capacity", "regular expression", "prefix/suffix", "Single-character");
	}

	@Test
	void reportsStreamAndEmbeddedResourceFamilies() {
		ToolResult streams = inspect(new ReportStreamLambdaPerformanceTool(), """
				import java.util.List;
				import java.util.Optional;
				class Sample {
				    String create() { return "x"; }
				    long count(List<String> values) { return values.stream().map(String::trim).count(); }
				    String value(Optional<String> value) { return value.orElse(create()); }
				}
				""");
		assertMessages(streams, "lambda-accepting");
		assertNoMessages(streams, "before count");

		ToolResult resources = inspect(new ReportEmbeddedResourcePerformanceTool(), """
				class Sample { void open() {
				    RecordStore store = RecordStore.openRecordStore("x", true);
				    Connection connection = Connector.open("socket://host");
				} }
				""");
		assertMessages(resources, "RecordStore", "Connection");
	}

	private static String apply(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertTrue(result.changed(), "Expected " + tool.id() + " to change source: " + result.findings());
		String output = TestSources.print(context);
		TestSources.parse(output);
		return output;
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed());
		return result;
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

	private static void assertNoMessages(ToolResult result, String... unwanted) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String message : unwanted) {
			assertFalse(messages.contains(message), () -> "Unexpected '" + message + "' in " + messages);
		}
	}

}
