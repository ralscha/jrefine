package ch.rasc.jrefine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JRefineMainTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void checkThenApplyHasPredictableExitCodes() throws IOException {
		Path source = temporaryDirectory.resolve("Sample.java");
		Files.writeString(source, """
				import java.util.ArrayList;
				import java.util.List;
				import java.util.Map;
				class Sample { List<String> names = new ArrayList<String>(); }
				""");
		StringWriter checkOutput = new StringWriter();
		StringWriter checkErrors = new StringWriter();

		int checkExit = JRefineMain.execute(
				new String[] { "--tool", "remove-unused-imports,use-diamond-operator", source.toString() },
				new PrintWriter(checkOutput, true), new PrintWriter(checkErrors, true));

		assertEquals(1, checkExit);
		assertTrue(checkOutput.toString().contains("CHECK"));
		assertTrue(checkOutput.toString().contains("Found 2 issue(s) in 1 file(s)"));
		assertTrue(checkErrors.toString().isEmpty());
		assertTrue(Files.readString(source).contains("java.util.Map"));

		StringWriter applyOutput = new StringWriter();
		int applyExit = JRefineMain.execute(
				new String[] { "--apply", "--tool", "remove-unused-imports,use-diamond-operator", source.toString() },
				new PrintWriter(applyOutput, true), new PrintWriter(new StringWriter(), true));

		assertEquals(0, applyExit);
		assertTrue(applyOutput.toString().contains("FIXED"));
		assertTrue(applyOutput.toString().contains("Applied 2 fix(es)"));
		assertFalse(Files.readString(source).contains("java.util.Map"));
		assertTrue(Files.readString(source).contains("new ArrayList<>()"));
	}

	@Test
	void listsTools() {
		StringWriter output = new StringWriter();

		int exit = JRefineMain.execute(new String[] { "--list-tools" }, new PrintWriter(output, true),
				new PrintWriter(new StringWriter(), true));

		assertEquals(0, exit);
		assertTrue(output.toString().contains("remove-unused-imports"));
		assertTrue(output.toString().contains("use-diamond-operator"));
		assertTrue(output.toString().contains("use-record-pattern"));
		assertTrue(output.toString().contains("Java 21"));
	}

	@Test
	void reportsDevelopmentVersionOutsideThePackagedJar() {
		StringWriter output = new StringWriter();

		int exit = JRefineMain.execute(new String[] { "--version" }, new PrintWriter(output, true),
				new PrintWriter(new StringWriter(), true));

		assertEquals(0, exit);
		assertEquals("jrefine development", output.toString().trim());
	}

	@Test
	void rejectsExplicitToolsThatExceedTheTargetJavaRelease() {
		StringWriter errors = new StringWriter();

		int exit = JRefineMain.execute(
				new String[] { "--target-java", "17", "--tool", "use-record-pattern", temporaryDirectory.toString() },
				new PrintWriter(new StringWriter(), true), new PrintWriter(errors, true));

		assertEquals(2, exit);
		assertTrue(errors.toString().contains("Tool 'use-record-pattern' requires Java 21 but target Java is 17"));
	}

	@Test
	void loadsTargetJavaFromConfiguration() throws IOException {
		Files.writeString(temporaryDirectory.resolve(".jrefine.properties"), "target-java=11\n");
		StringWriter errors = new StringWriter();

		int exit = JRefineMain.execute(new String[] { "--tool", "use-text-block", temporaryDirectory.toString() },
				new PrintWriter(new StringWriter(), true), new PrintWriter(errors, true));

		assertEquals(2, exit);
		assertTrue(errors.toString().contains("Tool 'use-text-block' requires Java 15 but target Java is 11"));
	}

	@Test
	void rejectsUnknownTools() {
		StringWriter errors = new StringWriter();

		int exit = JRefineMain.execute(new String[] { "--tool", "does-not-exist", temporaryDirectory.toString() },
				new PrintWriter(new StringWriter(), true), new PrintWriter(errors, true));

		assertEquals(2, exit);
		assertTrue(errors.toString().contains("Unknown tool 'does-not-exist'"));
	}

	@Test
	void keepsPolicyToolsOutOfTheDefaultProfile() throws IOException {
		Path source = temporaryDirectory.resolve("Numeric.java");
		Files.writeString(source, "class Numeric { int value = 1234567; }");
		StringWriter defaultOutput = new StringWriter();

		int defaultExit = JRefineMain.execute(new String[] { source.toString() }, new PrintWriter(defaultOutput, true),
				new PrintWriter(new StringWriter(), true));

		assertEquals(0, defaultExit);
		assertFalse(defaultOutput.toString().contains("report-numeric-literal-issues"));

		StringWriter policyOutput = new StringWriter();
		int policyExit = JRefineMain.execute(new String[] { "--profile", "policy", source.toString() },
				new PrintWriter(policyOutput, true), new PrintWriter(new StringWriter(), true));

		assertEquals(1, policyExit);
		assertTrue(policyOutput.toString().contains("report-numeric-literal-issues:info"));
	}

	@Test
	void loadsSeveritySuppressionParallelismAndTimingsFromConfiguration() throws IOException {
		Path source = temporaryDirectory.resolve("Configured.java");
		Files.writeString(source, "class Configured { int value = 1234567; }");
		Files.writeString(temporaryDirectory.resolve(".jrefine.properties"), """
				profile=policy
				minimum-severity=error
				severity.report-numeric-literal-issues=error
				threads=2
				timings=true
				""");
		StringWriter output = new StringWriter();

		int exit = JRefineMain.execute(new String[] { source.toString() }, new PrintWriter(output, true),
				new PrintWriter(new StringWriter(), true));

		assertEquals(1, exit);
		assertTrue(output.toString().contains("report-numeric-literal-issues:error"));
		assertTrue(output.toString().contains("Tool timings:"));

		StringWriter suppressedOutput = new StringWriter();
		int suppressedExit = JRefineMain.execute(
				new String[] { "--suppress", "report-numeric-literal-issues", source.toString() },
				new PrintWriter(suppressedOutput, true), new PrintWriter(new StringWriter(), true));

		assertEquals(0, suppressedExit);
		assertTrue(suppressedOutput.toString().contains("No findings"));
	}

}
