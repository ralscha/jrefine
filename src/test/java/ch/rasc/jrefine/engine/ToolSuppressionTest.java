package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSuppressionTest {

	@Test
	void readsMultipleDirectivesAndCommaSeparatedTools() {
		var unit = TestSources.parse("""
				/* jrefine-ignore-file first,second
				 * jrefine-ignore-file third
				 */
				class Sample {}
				""").compilationUnit();
		var suppressed = ToolSuppression.suppressedTools(unit);
		assertTrue(ToolSuppression.isSuppressed(suppressed, "first"));
		assertTrue(ToolSuppression.isSuppressed(suppressed, "second"));
		assertTrue(ToolSuppression.isSuppressed(suppressed, "third"));
		assertFalse(ToolSuppression.isSuppressed(suppressed, "fourth"));
	}

	@Test
	void ignoresDirectivePrefixesAndStringContents() {
		var unit = TestSources.parse("""
				// not-jrefine-ignore-file first
				// jrefine-ignore-file-extra second
				class Sample { String text = "jrefine-ignore-file all"; }
				""").compilationUnit();
		assertFalse(ToolSuppression.isSuppressed(unit, "first"));
		assertFalse(ToolSuppression.isSuppressed(unit, "second"));
	}

	@Test
	void onlyReadsToolNamesFromTheDirectiveLine() {
		var unit = TestSources.parse("""
				/* jrefine-ignore-file first
				 * Explanation of the first suppression.
				 */
				class Sample {}
				""").compilationUnit();
		assertTrue(ToolSuppression.isSuppressed(unit, "first"));
		assertFalse(ToolSuppression.isSuppressed(unit, "second"));
	}

	@Test
	void honorsExplicitWildcards() {
		for (String wildcard : new String[] { "all", "*" }) {
			var unit = TestSources.parse("// jrefine-ignore-file " + wildcard + "\nclass Sample {}").compilationUnit();
			assertTrue(ToolSuppression.isSuppressed(unit, "any-tool"));
		}
	}

}
