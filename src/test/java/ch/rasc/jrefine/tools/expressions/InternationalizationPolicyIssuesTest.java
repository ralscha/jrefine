package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternationalizationPolicyIssuesTest {

	@Test
	void reportsLocaleInsensitiveJdkApis() {
		ToolResult result = inspect("""
				import java.sql.Time;
				import java.util.Date;
				import java.util.StringTokenizer;
				class Sample {
				    void render(Date date, Time time, Double amount, String text) {
				        date.toString();
				        time.toString();
				        amount.toString();
				        text.equals("value");
				        text.equalsIgnoreCase("value");
				        text.compareTo("value");
				        text.compareToIgnoreCase("value");
				        text.trim();
				        StringTokenizer tokens = new StringTokenizer(text, ",");
				    }
				}
				""");

		assertMessages(result, "Date.toString", "Time.toString", "Number.toString", "locale-aware Collator",
				"not Unicode-aware", "StringTokenizer");
		long comparisons = messages(result).stream().filter(message -> message.contains("Collator")).count();
		assertTrue(comparisons == 4, messages(result).toString());
	}

	@Test
	void recognizesConstructedCastAndSourceLocalNumberReceivers() {
		ToolResult result = inspect("""
				import java.util.Date;
				abstract class Score extends Number {}
				class Sample {
				    void render(Object value, Score score) {
				        new Date().toString();
				        ((Number) value).toString();
				        score.toString();
				        " value ".trim().equals("value");
				    }
				}
				""");

		assertMessages(result, "Date.toString", "Number.toString", "not Unicode-aware", "locale-aware Collator");
		long numbers = messages(result).stream().filter(message -> message.contains("Number.toString")).count();
		assertTrue(numbers == 2, messages(result).toString());
	}

	@Test
	void acceptsExplicitFormattingAndNonJdkLookalikes() {
		ToolResult explicit = inspect("""
				import java.text.Collator;
				import java.text.DateFormat;
				import java.text.NumberFormat;
				import java.util.Date;
				import java.util.Locale;
				class Sample {
				    void render(Date date, Number amount, String left, String right) {
				        DateFormat.getDateInstance(DateFormat.SHORT, Locale.US).format(date);
				        NumberFormat.getNumberInstance(Locale.US).format(amount);
				        Collator.getInstance(Locale.US).compare(left, right);
				        left.strip();
				        left.split(",");
				    }
				}
				""");
		assertTrue(explicit.findings().isEmpty(), explicit.findings().toString());

		ToolResult lookalikes = inspect("""
				class Date { public String toString() { return ""; } }
				class Number { public String toString() { return ""; } }
				class StringTokenizer {}
				class Sample {
				    void render(Date date, Number number) {
				        date.toString();
				        number.toString();
				        StringTokenizer tokens = new StringTokenizer();
				    }
				}
				""");
		assertTrue(lookalikes.findings().isEmpty(), lookalikes.findings().toString());
	}

	private static ToolResult inspect(String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = new ReportInternationalizationPolicyIssuesTool().inspect(context, true);
		assertFalse(result.changed(), "Policy reporters must not change source");
		return result;
	}

	private static List<String> messages(ToolResult result) {
		return result.findings().stream().map(finding -> finding.message()).toList();
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = messages(result);
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

}
