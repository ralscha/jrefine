package ch.rasc.jrefine.tools.controlflow;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceControlFlowToolsTest {

	@Test
	void replacesDirectCollectionCopyLoopsWithAddAll() {
		InspectionContext context = TestSources.parse("""
				import java.util.ArrayList;
				import java.util.Collection;
				import java.util.List;
				class Sample {
				    List<Integer> copy(Collection<Integer> numbers) {
				        List<Integer> result = new ArrayList<>();
				        for (Integer number : numbers) {
				            result.add(number);
				        }
				        return result;
				    }
				}
				""");
		ToolResult result = new UseBulkOperationTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(1, result.findings().size());
		assertTrue(output.contains("result.addAll(numbers);"), output);
		assertFalse(output.contains("for (Integer number : numbers)"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsTransformedCopiesAndUnknownCollectionTypes() {
		InspectionContext transformed = TestSources.parse("""
				import java.util.List;
				class Sample { void copy(List<String> source, List<String> target) {
				    for (String value : source) target.add(value.trim());
				} }
				""");
		InspectionContext custom = TestSources.parse("""
				class Sample {
				    interface Collection<T> extends Iterable<T> { void add(T value); }
				    void copy(Collection<String> source, Collection<String> target) {
				        for (String value : source) target.add(value);
				    }
				}
				""");

		assertFalse(new UseBulkOperationTool().inspect(transformed, true).changed());
		assertFalse(new UseBulkOperationTool().inspect(custom, true).changed());
	}

}
