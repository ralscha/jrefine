package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Replaces stable integral min/max conditionals with Math calls. */
public final class UseMathMinMaxTool implements InspectionTool {

	@Override
	public String id() {
		return "use-math-min-max";
	}

	@Override
	public String description() {
		return "Replace manual integral min/max conditionals with Math calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(ConditionalExpr.class)
			.stream()
			.map(expression -> candidate(context, expression))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Replace manual " + candidate.method() + " calculation"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(),
							"Math." + candidate.method() + "(" + context.editor().text(candidate.left()) + ", "
									+ context.editor().text(candidate.right()) + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ConditionalExpr expression) {
		if (!(expression.getCondition() instanceof BinaryExpr condition)
				|| !(condition.getLeft() instanceof NameExpr left) || !(condition.getRight() instanceof NameExpr right)
				|| AstSupport.hasComment(context, expression)
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), "Math", "java.lang", Set.of("Math"))
				|| TypeLookup.visibleType(context.compilationUnit(), new NameExpr("Math"), expression).isPresent()) {
			return Optional.empty();
		}
		String leftType = TypeLookup.visibleType(context.compilationUnit(), left, expression)
			.map(NumericSupport::simpleName)
			.orElse("");
		String rightType = TypeLookup.visibleType(context.compilationUnit(), right, expression)
			.map(NumericSupport::simpleName)
			.orElse("");
		if (!leftType.equals(rightType) || !Set.of("int", "long").contains(leftType)
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), left.getNameAsString(), expression)
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), right.getNameAsString(),
						expression)) {
			return Optional.empty();
		}
		boolean thenLeft = expression.getThenExpr().equals(left);
		boolean thenRight = expression.getThenExpr().equals(right);
		boolean elseLeft = expression.getElseExpr().equals(left);
		boolean elseRight = expression.getElseExpr().equals(right);
		if (!thenLeft || !elseRight) {
			if (!thenRight || !elseLeft) {
				return Optional.empty();
			}
		}
		String method = switch (condition.getOperator()) {
			case LESS, LESS_EQUALS -> thenLeft ? "min" : "max";
			case GREATER, GREATER_EQUALS -> thenLeft ? "max" : "min";
			default -> null;
		};
		return method == null ? Optional.empty() : Optional.of(new Candidate(expression, left, right, method));
	}

	private record Candidate(ConditionalExpr expression, Expression left, Expression right, String method) {
	}

}
