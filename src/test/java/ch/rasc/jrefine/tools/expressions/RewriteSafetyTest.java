package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RewriteSafetyTest {

	@Test
	void preservesCapacityConstructorsAndNullAcceptingCollectionCopies() {
		assertUnchanged(new UseCollectionFactoryTool(), """
				import java.util.*;
				class Sample {
				    List<String> capacity = Collections.unmodifiableList(new ArrayList<>(10));
				    Set<String> setCapacity = Collections.unmodifiableSet(new HashSet<>(10));
				    Map<String, String> mapCapacity = Collections.unmodifiableMap(new HashMap<>(10));
				    List<String> list(List<String> input) {
				        return Collections.unmodifiableList(new ArrayList<>(input));
				    }
				    Set<String> set(Set<String> input) {
				        return Collections.unmodifiableSet(new HashSet<>(input));
				    }
				    Map<String, String> map(Map<String, String> input) {
				        return Collections.unmodifiableMap(new HashMap<>(input));
				    }
				}
				""");
	}

	@Test
	void preservesExplicitCollectionElementTypes() {
		assertUnchanged(new UseCollectionFactoryTool(), """
				import java.util.*;
				class Sample {
				    void sample() {
				        var outer = Collections.<Number>unmodifiableList(Arrays.asList(1));
				        var inner = Collections.unmodifiableList(Arrays.<Number>asList(1));
				    }
				}
				""");
	}

	@Test
	void preservesNullToStringConversionAndArrayConversion() {
		assertUnchanged(new RemoveEmptyStringConcatenationTool(), """
				class Sample {
				    String nullable(String value) { return value + ""; }
				    String array(String[] value) { return "" + value; }
				}
				""");
	}

	@Test
	void preservesObjectConversionBeforeEvaluationOfTheNextOperand() {
		assertUnchanged(new RemoveEmptyStringConcatenationTool(), """
				class Sample {
				    String suffix;
				    String value(Object value) { return "" + value + suffix; }
				}
				""");
	}

	private static void assertUnchanged(InspectionTool tool, String source) {
		for (boolean apply : new boolean[] { false, true }) {
			var context = TestSources.parse(source);
			var result = tool.inspect(context, apply);
			assertFalse(result.changed());
			assertEquals(0, result.findings().size());
			assertEquals(source, context.editor().render());
		}
	}

}
