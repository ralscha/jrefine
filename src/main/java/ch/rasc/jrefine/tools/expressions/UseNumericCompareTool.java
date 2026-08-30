package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Replaces three-way numeric comparison conditionals with compare methods. */
public final class UseNumericCompareTool implements InspectionTool {

	private static final Map<String, String> PRIMITIVE_OWNERS = Map.of("byte", "Byte", "short", "Short", "char",
			"Character", "int", "Integer", "long", "Long", "float", "Float", "double", "Double");

	private static final Set<String> WRAPPERS = Set.of("Byte", "Short", "Character", "Integer", "Long", "Float",
			"Double");

	@Override
	public String id() {
		return "use-numeric-compare";
	}

	@Override
	public String description() {
		return "Replace three-way numeric comparisons with compare()";
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
			findings.add(Finding.at(candidate.expression(), "Use compare() for numeric comparison"));
			if (applyFixes) {
				String left = context.editor().text(candidate.left());
				String right = context.editor().text(candidate.right());
				String replacement = candidate.boxed() ? left + ".compareTo(" + right + ")"
						: candidate.owner() + ".compare(" + left + ", " + right + ")";
				context.editor().replace(candidate.expression().getRange().orElseThrow(), replacement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ConditionalExpr expression) {
		if (!(expression.getElseExpr() instanceof ConditionalExpr nested) || AstSupport.hasComment(context, expression)
				|| sign(nested.getElseExpr()) != 0) {
			return Optional.empty();
		}
		Optional<Relation> first = relation(expression.getCondition(), sign(expression.getThenExpr()));
		Optional<Relation> second = relation(nested.getCondition(), sign(nested.getThenExpr()));
		if (first.isEmpty() || second.isEmpty()) {
			return Optional.empty();
		}
		Relation left = first.orElseThrow();
		Relation right = second.orElseThrow();
		if (left.sign() != -right.sign() || !left.left().equals(right.left()) || !left.right().equals(right.right())
				|| !(left.left() instanceof NameExpr leftName) || !(left.right() instanceof NameExpr rightName)
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), leftName.getNameAsString(),
						expression)
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), rightName.getNameAsString(),
						expression)) {
			return Optional.empty();
		}
		String leftType = TypeLookup.visibleType(context.compilationUnit(), left.left(), expression)
			.map(NumericSupport::simpleName)
			.orElse(null);
		String rightType = TypeLookup.visibleType(context.compilationUnit(), left.right(), expression)
			.map(NumericSupport::simpleName)
			.orElse(null);
		if (leftType == null || !leftType.equals(rightType)) {
			return Optional.empty();
		}
		if (PRIMITIVE_OWNERS.containsKey(leftType)) {
			return Optional
				.of(new Candidate(expression, left.left(), left.right(), PRIMITIVE_OWNERS.get(leftType), false));
		}
		if (WRAPPERS.contains(leftType)
				&& TypeLookup.isKnownJavaLangType(context.compilationUnit(), leftType, Set.of(leftType))) {
			return Optional.of(new Candidate(expression, left.left(), left.right(), leftType, true));
		}
		return Optional.empty();
	}

	private static Optional<Relation> relation(Expression condition, int resultSign) {
		if (!(condition instanceof BinaryExpr binary) || Math.abs(resultSign) != 1) {
			return Optional.empty();
		}
		int relationSign = switch (binary.getOperator()) {
			case GREATER -> 1;
			case LESS -> -1;
			default -> 0;
		};
		return relationSign == resultSign ? Optional.of(new Relation(binary.getLeft(), binary.getRight(), resultSign))
				: Optional.empty();
	}

	private static int sign(Expression expression) {
		if (expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() <= 1) {
			return literal.asNumber().intValue();
		}
		if (expression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS
				&& unary.getExpression() instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 1) {
			return -1;
		}
		return Integer.MIN_VALUE;
	}

	private record Relation(Expression left, Expression right, int sign) {
	}

	private record Candidate(ConditionalExpr expression, Expression left, Expression right, String owner,
			boolean boxed) {
	}

}
