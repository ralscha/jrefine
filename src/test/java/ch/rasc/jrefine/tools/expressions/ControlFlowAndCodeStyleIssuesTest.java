package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import java.util.List;
import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.tools.controlflow.ReportConditionalFlowIssuesTool;
import ch.rasc.jrefine.tools.controlflow.ReportControlFlowStructureIssuesTool;
import ch.rasc.jrefine.tools.declarations.ReportDeclarationStyleIssuesTool;
import ch.rasc.jrefine.tools.syntax.ReportBlockAndTextStyleIssuesTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFlowAndCodeStyleIssuesTest {

	@Test
	void reportsStructuralControlFlowIssues() {
		ToolResult result = inspect(new ReportControlFlowStructureIssuesTool(), """
				enum Mode { A, B, C }
				class Sample {
				    void scan(Mode mode, boolean ready) {
				        outer: for (;;) {
				            if (ready) break outer;
				            continue;
				        }
				        assert ready;
				        if (!ready) return;
				        while (ready) { break; }
				        switch (mode) {
				            default: System.out.println(0);
				            case A: int value = 1;
				            case B: System.out.println(value);
				        }
				    }
				}
				""");

		assertMessages(result, "'break' statement with label", "Labeled statement", "may be replaced by a 'while'",
				"missing components", "Assertion can be replaced", "'default' is not the last", "Enum switch misses",
				"different switch branches", "replaced with 'do while'");
		assertNoMessages(result, "'continue' statement complicates loop control flow");
	}

	@Test
	void reportsConditionalControlFlowIssues() {
		ToolResult result = inspect(new ReportConditionalFlowIssuesTool(), """
				class Sample {
				    boolean choose(boolean a, boolean b, boolean c, boolean d, boolean e, boolean f) {
				        if (a) return true; else return false;
				        if (!b) System.out.println(1); else System.out.println(2);
				        int same = a ? 1 : 1;
				        int nested = a ? (b ? 1 : 2) : 3;
				        boolean duplicate = a && a;
				        boolean factor = (a && b) || (a && c);
				        boolean pointless = b && true;
				        boolean negated = !!c;
				        boolean complex = a && b && c && d && e && f;
				        return "abc".indexOf("x") < -1;
				    }
				}
				""");

		assertMessages(result, "Redundant 'if'", "identical branches", "Duplicate condition", "Pointless boolean",
				"Double negation", "Pointless indexOf");
		assertNoMessages(result, "Conditional expression may be clearer as an if/else statement",
				"Overly complex boolean expression");
	}

	@Test
	void reportsExpressionStyleIssues() {
		ToolResult result = inspect(new ReportExpressionStyleIssuesTool(), """
				import java.util.List;
				import java.util.Objects;
				import java.util.Optional;
				enum Mode { A }
				class Sample {
				    Object inspect(List<String> values, Mode mode, String text, boolean a, boolean b) {
				        assert text != null : 42;
				        if (mode.equals(Mode.A)) return true; else return false;
				        boolean found = values.indexOf(text) >= 0;
				        boolean same = Objects.equals("known", text);
				        boolean chained = a == b == true;
				        int constant = 1 + 2;
				        String call = text.trim().toLowerCase().concat("x");
				        System.out.println(text.trim());
				        String safe = text != null ? text : "";
				        if (Optional.of(text).isPresent()) System.out.println(text);
				        Object result = String.valueOf(text);
				        System.out.println(result);
				        return result;
				    }
				}
				""");

		assertMessages(result, "message is not a String", "enum value", "contains()", "Objects.equals()",
				"String.concat");
		assertNoMessages(result, "Non-functional style Optional.isPresent() usage",
				"'return' is separated from the result computation");
	}

	@Test
	void reportsOnlyPrecedenceMixesThatBenefitFromParentheses() {
		ToolResult result = inspect(new ReportExpressionStyleIssuesTool(), """
				class Sample {
				    boolean check(String text, int a, int b, int c, boolean first,
				            boolean second, boolean third) {
				        int samePrecedence = a + b - c;
				        boolean conventionalGuard = text != null && text.length() > 0;
				        int unclearArithmetic = a & b - c;
				        boolean unclearLogical = first || second && third;
				        return conventionalGuard || unclearLogical || samePrecedence > unclearArithmetic;
				    }
				}
				""");

		long precedenceFindings = result.findings()
			.stream()
			.filter(finding -> finding.message().contains("different precedence"))
			.count();
		assertTrue(precedenceFindings == 1, () -> "Expected one unclear precedence finding, got " + result.findings());
	}

	@Test
	void reportsDeclarationStyleIssues() {
		ToolResult result = inspect(new ReportDeclarationStyleIssuesTool(), """
				import java.util.ArrayList;
				import java.util.List;
				import java.util.Optional;
				import java.util.function.Function;
				enum Mode { A, B }
				sealed class Parent {}
				final class Child extends Parent {}
				record Box(int value) { Box self() { return this; } }
				class Sample {
				    int field;
				    Optional<List<String>> values;
				    Mode[] modes = new Mode[]{Mode.A, Mode.B};
				    int[] numbers = {1, 2};
				    Sample() { field = 1; }
				    <T> void consume(List<T> input) {}
				    void run(String parameter) {
				        int first = 1, second = 2;
				        var list = new ArrayList<>();
				        Runnable lambda = () -> helper();
				        Function<String, String> typed = value -> value.trim();
				        Runnable reference = this::helper;
				        first = second;
				    }
				    void helper() {}
				}
				""");

		assertMessages(result, "enum constants", "no explicit 'new'", "bounded wildcard", "Field may be final",
				"field initializer", "Multiple variables", "Return of 'this'", "permits clause");
		assertNoMessages(result, "Implicit call to super()", "Lambda parameter type can be specified",
				"Method reference can be replaced with a lambda",
				"Diamond can be replaced with explicit type arguments", "Record can be converted to a class");
	}

	@Test
	void reportsBlockAndTextStyleIssues() {
		ToolResult result = inspect(new ReportBlockAndTextStyleIssuesTool(), """
				import java.io.IOException;
				@Deprecated
				class Sample {
				    AutoCloseable open() { return null; }
				    void work() {}
				    void run(boolean ready, int value) {
				        // end if
				        if (ready) { work(); } else work();
				        try (var first = open(); var second = open()) { work(); }
				        catch (IOException | RuntimeException error) { work(); }
				        { work(); }
				        switch (value) {
				            case 1 -> work();
				            default -> { work(); }
				        }
				        String octal = "\\1234";
				        String space = "x\\s y";
				    }
				}
				""");

		assertMessages(result, "no braces", "redundant code block", "Unnecessary code block", "Block marker",
				"octal escape");
		assertNoMessages(result, "Non-terminal use of '\\s' escape sequence",
				"Non-normalized annotation can use explicit attribute syntax");
	}

	private static ToolResult inspect(InspectionTool tool, String source) {
		InspectionContext context = TestSources.parse(source);
		ToolResult result = tool.inspect(context, true);
		assertFalse(result.changed(), "Style reporters must not change source");
		return result;
	}

	private static void assertMessages(ToolResult result, String... fragments) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String fragment : fragments) {
			assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
					() -> "Missing '" + fragment + "' in " + messages);
		}
	}

	private static void assertNoMessages(ToolResult result, String... unwanted) {
		List<String> messages = result.findings().stream().map(finding -> finding.message()).toList();
		for (String message : unwanted) {
			assertFalse(messages.contains(message), () -> "Unexpected '" + message + "' in " + messages);
		}
	}

}
