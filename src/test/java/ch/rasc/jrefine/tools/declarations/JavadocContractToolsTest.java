package ch.rasc.jrefine.tools.declarations;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavadocContractToolsTest {

	@Test
	void reportsDeclarationTagAndDeprecationMismatches() {
		ToolResult result = inspect(new ReportJavadocContractIssuesTool(), """
				class Sample<T> {
				    /**
				     * Runs work.
				     * @param wrong first description
				     * @param wrong duplicate description
				     * @return nothing
				     * @throws
				     * @deprecated use replacement
				     */
				    void run(String value) {}
				}
				""");

		assertMessages(result, "does not match declaration parameter", "duplicate @param", "@return tag does not match",
				"@throws tag has no value", "missing a @Deprecated annotation");
	}

	@Test
	void acceptsMatchingDeclarationTags() {
		ToolResult result = inspect(new ReportJavadocContractIssuesTool(), """
				/** @param <T> element type */
				class Sample<T> {
				    /**
				     * Runs work.
				     * @param value input
				     * @return result
				     * @throws java.io.IOException on failure
				     * @deprecated use replacement
				     */
				    @Deprecated
				    String run(String value) throws java.io.IOException { return value; }
				}

				/** @param value record value */
				record Value(String value) {}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	@Test
	void reportsDanglingJavadoc() {
		ToolResult result = inspect(new ReportJavadocContractIssuesTool(), """
				class Sample {}
				/** This comment has no declaration. */
				""");

		assertMessages(result, "Dangling Javadoc");
	}

	@Test
	void reportsPackageInfoWithoutPackageDeclaration() {
		String source = "/** Package documentation. */";
		ToolResult result = inspect(new ReportJavadocContractIssuesTool(), Path.of("package-info.java"), source);

		assertMessages(result, "does not contain a package declaration");

		ToolResult valid = inspect(new ReportJavadocContractIssuesTool(), Path.of("package-info.java"),
				"/** Package documentation. */ package sample;");
		assertTrue(valid.findings().isEmpty(), valid.findings().toString());
	}

	@Test
	void reportsInvalidSourceLocalReferencesAndMalformedMarkup() {
		ToolResult result = inspect(new ReportJavadocReferenceIssuesTool(), """
				class Sample {
				    int value;
				    void run(String input) {}

				    /**
				     * {@link #missing()}
				     * {@value Sample#unknown}
				     * {@link }
				     * <b>unclosed
				     * @see #absent
				     */
				    void documented() {}
				}
				""");

		assertMessages(result, "does not resolve", "empty or unterminated", "unbalanced <b>");
	}

	@Test
	void acceptsValidAndExternalReferencesAndBalancedMarkup() {
		ToolResult result = inspect(new ReportJavadocReferenceIssuesTool(), """
				class Parent { void inherited() {} }
				/** {@link #value()} */
				@interface Options { String value(); }
				/** {@link #name()} */
				record Entry(String name) {}
				/** {@link Mode#FAST} */
				enum Mode { FAST, SAFE }
				class Sample extends Parent {
				    static final int VALUE = 1;
				    void run(String input) {}
				    void run(String input, int count) {}

				    /**
				     * <b>Bold</b> and {@code <i>literal markup</i>}.
				     * {@link #run(java.lang.String) label}
				     * {@link #run(
				     *     java.lang.String,
				     *     int) multiline label}
				     * {@link #inherited()}
				     * {@link #toString()}
				     * {@value Sample#VALUE}
				     * {@link java.util.List#size() external}
				     * @see #VALUE
				     * @see #run(java.lang.String,
				     *     int)
				     */
				    void documented() {}
				}
				""");

		assertTrue(result.findings().isEmpty(), result.findings().toString());
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		return inspect(tool, Path.of("Sample.java"), source);
	}

	private static ToolResult inspect(InspectionTool tool, Path path, String source) {
		InspectionContext parsed = TestSources.parse(source);
		InspectionContext context = new InspectionContext(path, parsed.compilationUnit(), source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Reporter must not change source");
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
