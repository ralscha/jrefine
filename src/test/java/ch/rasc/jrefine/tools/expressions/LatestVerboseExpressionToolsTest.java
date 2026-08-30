package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.TestSources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.NameExpr;

class LatestVerboseExpressionToolsTest {

	@Test
	void collapsesSingleValueIntegralRanges() {
		InspectionContext context = TestSources.parse("""
				class Sample {
				    boolean middle(int x) { return x > 2 && x < 4; }
				    boolean missing(int[] values) {
				        return values.length == 0 || values.length > 1;
				    }
				}
				""");
		assertEquals("int[]", context.compilationUnit().findAll(Parameter.class).get(1).getType().asString());
		NameExpr arrayUse = context.compilationUnit()
			.findAll(NameExpr.class)
			.stream()
			.filter(name -> "values".equals(name.getNameAsString()))
			.findFirst()
			.orElseThrow();
		assertEquals(java.util.Optional.of("int[]"), ch.rasc.jrefine.analysis.TypeLookup
			.visibleTypePreservingArrays(context.compilationUnit(), arrayUse, arrayUse));
		ToolResult result = new SimplifyRangeCheckTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size(), output);
		assertTrue(output.contains("return x == 3;"), output);
		assertTrue(output.contains("return values.length != 1;"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsContinuousAndUnstableRangeChecks() {
		InspectionContext continuous = TestSources.parse("""
				class Sample { boolean test(double x) { return x > 2 && x < 4; } }
				""");
		InspectionContext unstable = TestSources.parse("""
				class Sample {
				    int next() { return 3; }
				    boolean test() { return next() > 2 && next() < 4; }
				}
				""");

		assertFalse(new SimplifyRangeCheckTool().inspect(continuous, true).changed());
		assertFalse(new SimplifyRangeCheckTool().inspect(unstable, true).changed());
	}

	@Test
	void replacesTrivialOptionalSupplierLambdas() {
		InspectionContext context = TestSources.parse("""
				import java.util.Optional;
				class Sample {
				    String local(Optional<String> value, String fallback) {
				        return value.orElseGet(() -> fallback);
				    }
				    String literal() {
				        return Optional.<String>empty().orElseGet(() -> { return "none"; });
				    }
				}
				""");
		ToolResult result = new SimplifyExcessiveLambdaTool().inspect(context, true);
		String output = TestSources.print(context);

		assertEquals(2, result.findings().size(), output);
		assertTrue(output.contains("value.orElse(fallback)"), output);
		assertTrue(output.contains("Optional.<String>empty().orElse(\"none\")"), output);
		TestSources.parse(output);
	}

	@Test
	void keepsNonTrivialSupplierAndLookalikeOptional() {
		InspectionContext nonTrivial = TestSources.parse("""
				import java.util.Optional;
				class Sample {
				    String load() { return "value"; }
				    String value(Optional<String> input) { return input.orElseGet(() -> load()); }
				}
				""");
		InspectionContext lookalike = TestSources.parse("""
				class Optional<T> { T orElseGet(java.util.function.Supplier<T> value) { return null; } }
				class Sample { String value(Optional<String> input) {
				    return input.orElseGet(() -> "fallback");
				} }
				""");

		assertFalse(new SimplifyExcessiveLambdaTool().inspect(nonTrivial, true).changed());
		assertFalse(new SimplifyExcessiveLambdaTool().inspect(lookalike, true).changed());
	}

}
