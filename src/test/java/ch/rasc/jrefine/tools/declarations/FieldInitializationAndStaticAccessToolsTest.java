package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.expressions.QualifyStaticMemberAccessTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldInitializationAndStaticAccessToolsTest {

	@Test
	void reportsMissingNonNullInitializationAndMutableStaticReads() {
		ToolResult result = inspect(new ReportFieldInitializationContractIssuesTool(), """
				import org.jetbrains.annotations.NotNull;
				class Sample {
				    @NotNull private String value;
				    @NotNull private static String shared;
				    private static int seed = 1;
				    private static int copy = seed;

				    Sample(String value) { this.value = value; }
				    Sample() {}
				}
				""");

		assertMessages(result, "Instance non-null field", "Static non-null field", "Non-final static field is read");
	}

	@Test
	void acceptsVisibleInitializationContractsAndFinalStaticReads() {
		ToolResult result = inspect(new ReportFieldInitializationContractIssuesTool(), """
				import org.jspecify.annotations.NonNull;
				class Sample {
				    @NonNull private String value;
				    @NonNull private static String shared;
				    private static final int seed = 1;
				    private static int copy = seed;

				    static { shared = "ready"; }
				    { value = "ready"; }
				    Sample() {}
				    Sample(int ignored) { this(); }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void ignoresLookalikeNullnessAnnotationsAndInitializationHelpers() {
		ToolResult lookalike = inspect(new ReportFieldInitializationContractIssuesTool(), """
				@interface NotNull {}
				class Sample { @NotNull String value; }
				""");
		assertTrue(lookalike.findings().isEmpty(), lookalike.findings().toString());

		ToolResult helper = inspect(new ReportFieldInitializationContractIssuesTool(), """
				import javax.annotation.Nonnull;
				class Sample {
				    @Nonnull String value;
				    Sample() { initialize(); }
				    void initialize() { value = "ready"; }
				}
				""");
		assertTrue(helper.findings().isEmpty(), helper.findings().toString());
	}

	@Test
	void qualifiesSafeSourceLocalStaticMemberAccess() {
		String output = apply(new QualifyStaticMemberAccessTool(), """
				class Utility {
				    static int VALUE = 1;
				    static void reset() {}
				    void instanceMethod() {}
				}
				class Sample {
				    void use(Utility utility) {
				        int value = utility.VALUE;
				        utility.reset();
				        utility.instanceMethod();
				    }
				}
				""");

		assertTrue(output.contains("Utility.VALUE"), output);
		assertTrue(output.contains("Utility.reset()"), output);
		assertTrue(output.contains("utility.instanceMethod()"), output);
	}

	@Test
	void skipsExternalSideEffectingAmbiguousAndShadowedReceivers() {
		ToolResult result = inspect(new QualifyStaticMemberAccessTool(), """
				class Utility {
				    static void action(String value) {}
				    void action(int value) {}
				    static int VALUE = 1;
				}
				class Sample {
				    Utility create() { return new Utility(); }
				    void use(Utility Utility, External external) {
				        Utility.action("value");
				        create().action("value");
				        external.action();
				    }
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static String apply(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertTrue(result.changed(), "Expected " + tool.id() + " to change source");
		String output = context.editor().render();
		TestSources.parse(output);
		return output;
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, false);
		assertFalse(result.changed(), "Check-only reporter must not change source");
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
