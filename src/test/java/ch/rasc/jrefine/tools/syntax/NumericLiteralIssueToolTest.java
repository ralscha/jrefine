package ch.rasc.jrefine.tools.syntax;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import java.util.List;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericLiteralIssueToolTest {

	@Test
	void reportsAllNumericLiteralStyleFamiliesWithoutEditing() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    double noPoint = 1e10;
				    int longNumber = 1234567;
				    int badlyGrouped = 12_34;
				    int octal = 0123;
				    int[] mixed = {012, 12};
				}
				""");
		ToolResult result = new ReportNumericLiteralIssuesTool().inspect(context, true);
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();

		assertFalse(result.changed());
		assertTrue(messages.stream().anyMatch(message -> message.contains("Floating-point")), messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("could use underscore")),
				messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("suspicious underscore")),
				messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("Octal integer")), messages.toString());
		assertTrue(messages.stream().anyMatch(message -> message.contains("mixes octal")), messages.toString());
	}

}
