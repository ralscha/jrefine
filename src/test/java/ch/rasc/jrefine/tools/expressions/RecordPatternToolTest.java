package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordPatternToolTest {

	@Test
	void deconstructsARecordInTheInstanceofPattern() {
		InspectionContext context = TestSources.parse("""
				record Point(int x, int y) {}
				class Sample { int sum(Object value) {
				    if (value instanceof Point point) {
				        int horizontal = point.x();
				        int vertical = point.y();
				        return horizontal + vertical;
				    }
				    return 0;
				} }
				""");
		ToolResult result = new UseRecordPatternTool().inspect(context, true);
		String output = TestSources.print(context);

		assertTrue(result.changed());
		assertTrue(output.contains("instanceof Point(var horizontal, var vertical)"), output);
		assertFalse(output.contains("point.x()"), output);
		assertFalse(output.contains("point.y()"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsBindingWhenItIsStillUsedOrAccessorsAreIncomplete() {
		InspectionContext stillUsed = TestSources.parse("""
				record Point(int x, int y) {}
				class Sample { void print(Object value) {
				    if (value instanceof Point point) {
				        int x = point.x();
				        int y = point.y();
				        System.out.println(point);
				    }
				} }
				""");
		InspectionContext incomplete = TestSources.parse("""
				record Point(int x, int y) {}
				class Sample { int read(Object value) {
				    if (value instanceof Point point) {
				        int x = point.x();
				        return x;
				    }
				    return 0;
				} }
				""");

		assertFalse(new UseRecordPatternTool().inspect(stillUsed, true).changed());
		assertFalse(new UseRecordPatternTool().inspect(incomplete, true).changed());
	}

}
