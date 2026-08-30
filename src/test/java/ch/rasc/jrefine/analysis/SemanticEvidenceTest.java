package ch.rasc.jrefine.analysis;

import ch.rasc.jrefine.TestSources;
import ch.rasc.jrefine.api.InspectionContext;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticEvidenceTest {

	@Test
	void distinguishesEffectivelyFinalAndReassignedCapturedValues() {
		InspectionContext context = TestSources.parse("""
				import java.util.function.Supplier;
				class Sample {
				    void run() {
				        String stable = "stable";
				        String changed = "before";
				        changed = "after";
				        Supplier<String> first = () -> stable;
				        Supplier<String> second = () -> changed;
				    }
				}
				""");
		LambdaExpr first = context.compilationUnit().findAll(LambdaExpr.class).get(0);
		LambdaExpr second = context.compilationUnit().findAll(LambdaExpr.class).get(1);

		assertTrue(SemanticEvidence.isEffectivelyFinalLocalOrParameter(context, "stable", first));
		assertFalse(SemanticEvidence.isEffectivelyFinalLocalOrParameter(context, "changed", second));
	}

	@Test
	void rejectsMethodCallReceiversAndIndirectLambdaTargets() {
		InspectionContext context = TestSources.parse("""
				import java.util.Comparator;
				import java.util.function.Supplier;
				class Sample {
				    void run() {
				        String local = "value";
				        Supplier<Integer> direct = () -> local.length();
				        Comparator<String> indirect = Comparator.comparingInt(
				                (String value) -> value.length());
				    }
				}
				""");
		LambdaExpr direct = context.compilationUnit().findAll(LambdaExpr.class).get(0);
		LambdaExpr indirect = context.compilationUnit().findAll(LambdaExpr.class).get(1);
		NameExpr directReceiver = direct.findFirst(NameExpr.class, name -> name.getNameAsString().equals("local"))
			.orElseThrow();

		assertTrue(SemanticEvidence.hasDirectLambdaTargetType(direct));
		assertFalse(SemanticEvidence.hasDirectLambdaTargetType(indirect));
		assertTrue(SemanticEvidence.isStableMethodReferenceReceiver(context, directReceiver, direct));
	}

}
