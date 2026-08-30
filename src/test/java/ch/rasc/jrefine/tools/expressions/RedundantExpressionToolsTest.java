package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedundantExpressionToolsTest {

	@Test
	void simplifiesCollectionCompareFileAndClassCalls() {
		String collection = apply(new SimplifyRedundantCollectionOperationTool(), """
				import java.util.Collections;
				import java.util.List;
				class Sample { boolean has(List<String> values, String value) {
				    return values.containsAll(Collections.singletonList(value));
				} }
				""");
		assertTrue(collection.contains("values.contains(value)"), collection);

		String compare = apply(new RemoveRedundantCompareCallTool(), """
				class Sample { boolean same(int left, int right) {
				    return Integer.compare(left, right) == 0;
				} }
				""");
		assertTrue(compare.contains("left == right"), compare);

		String file = apply(new RemoveRedundantFileCreationTool(), """
				import java.io.File;
				import java.io.FileInputStream;
				class Sample { FileInputStream open(String path) throws Exception {
				    return new FileInputStream(new File(path));
				} }
				""");
		assertTrue(file.contains("new FileInputStream(path)"), file);

		String reflected = apply(new ReplaceRedundantClassCallTool(), """
				class Sample {
				    boolean string(Object value) { return String.class.isInstance(value); }
				    String cast(Object value) { return String.class.cast(value); }
				}
				""");
		assertTrue(reflected.contains("(value) instanceof String"), reflected);
		assertTrue(reflected.contains("(String) (value)"), reflected);
	}

	@Test
	void simplifiesStringArrayAndRegexOperations() {
		String string = apply(new SimplifyRedundantStringOperationTool(), """
				class Sample {
				    int length() { return new String("message").length(); }
				    String whole() { return "message".substring(0); }
				    String text() { return "text".toString(); }
				}
				""");
		assertTrue(string.contains("return \"message\".length();"), string);
		assertTrue(string.contains("return \"message\";"), string);
		assertTrue(string.contains("return \"text\";"), string);

		String array = apply(new RemoveRedundantArrayCreationTool(), """
				import java.util.Arrays;
				import java.util.List;
				class Sample { List<String> values() {
				    return Arrays.asList(new String[]{"a", "b"});
				} }
				""");
		assertTrue(array.contains("Arrays.asList(\"a\", \"b\")"), array);

		String regex = apply(new RemoveRedundantRegexReplacementEscapeTool(), """
				class Sample { String replace(String value) {
				    return value.replaceAll("a", "\\\\b");
				} }
				""");
		assertTrue(regex.contains("replaceAll(\"a\", \"b\")"), regex);
	}

	@Test
	void simplifiesJavaTimeAndPipelineSteps() {
		String time = apply(new SimplifyRedundantJavaTimeOperationTool(), """
				import java.time.LocalDateTime;
				import java.time.temporal.ChronoField;
				class Sample {
				    LocalDateTime same(LocalDateTime value) { return LocalDateTime.from(value); }
				    int minute(LocalDateTime value) { return value.get(ChronoField.MINUTE_OF_HOUR); }
				    boolean after(LocalDateTime left, LocalDateTime right) {
				        return left.compareTo(right) > 0;
				    }
				}
				""");
		assertTrue(time.contains("return value;"), time);
		assertTrue(time.contains("value.getMinute()"), time);
		assertTrue(time.contains("left.isAfter(right)"), time);

		String stream = apply(new RemoveRedundantStreamOptionalStepTool(), """
				import java.util.List;
				class Sample { List<String> copy(List<String> values) {
				    return values.stream().map(value -> value).toList();
				} }
				""");
		assertTrue(stream.contains("values.stream().toList()"), stream);
	}

	@Test
	void removesTypeNoiseAndImmutableWrappers() {
		String arguments = apply(new RemoveRedundantTypeArgumentsTool(), """
				import java.util.Arrays;
				import java.util.List;
				class Sample { List<String> values() {
				    return Arrays.<String>asList("a", "b");
				} }
				""");
		assertTrue(arguments.contains("Arrays.asList(\"a\", \"b\")"), arguments);

		String cast = apply(new RemoveRedundantTypeCastTool(), """
				class Sample { Object widen(String value) { return (Object) value; } }
				""");
		assertTrue(cast.contains("return value;"), cast);

		String wrapper = apply(new RemoveRedundantUnmodifiableWrapperTool(), """
				import java.util.Collections;
				import java.util.List;
				class Sample { List<String> values() {
				    return Collections.unmodifiableList(Collections.singletonList("a"));
				} }
				""");
		assertTrue(wrapper.contains("return Collections.singletonList(\"a\");"), wrapper);
	}

	@Test
	void skipsLookalikeUserApisAndUnsafeStringAndCastShapes() {
		InspectionContext custom = TestSources.parse("""
				class Sample {
				    interface Values { boolean containsAll(Object value); }
				    static class Collections { static Object singletonList(Object value) { return value; } }
				    boolean test(Values values) { return values.containsAll(Collections.singletonList("x")); }
				    String chars(char[] value) { return new String(value); }
				    String nullable(String value) { return value.substring(0); }
				    Object cast(Object value) { return (String) value; }
				}
				""");

		assertFalse(new SimplifyRedundantCollectionOperationTool().inspect(custom, true).changed());
		assertFalse(new SimplifyRedundantStringOperationTool().inspect(custom, true).changed());
		assertFalse(new RemoveRedundantTypeCastTool().inspect(custom, true).changed());

		InspectionContext targetTyping = TestSources.parse("""
				import java.util.Collections;
				class TargetTyping { void run() { var values = Collections.<String>emptyList(); } }
				""");
		assertFalse(new RemoveRedundantTypeArgumentsTool().inspect(targetTyping, true).changed());

		InspectionContext uriFile = TestSources.parse("""
				import java.io.File;
				import java.io.FileInputStream;
				import java.net.URI;
				class UriFile { FileInputStream open(URI uri) throws Exception {
				    return new FileInputStream(new File(uri));
				} }
				""");
		assertFalse(new RemoveRedundantFileCreationTool().inspect(uriFile, true).changed());
	}

	@Test
	void skipsUnrelatedGetCallsWithoutFailing() {
		InspectionContext context = TestSources.parse("""
				import java.util.Map;
				class Sample { String lookup(Map<String, String> values, String key) {
				    return values.get(key);
				} }
				""");

		assertTrue(new SimplifyRedundantJavaTimeOperationTool().inspect(context, false).findings().isEmpty());
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
