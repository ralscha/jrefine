package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Simplifies range conditions whose truth sets subsume one another. */
public final class SimplifyCoveredConditionsTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-covered-conditions";
	}

	@Override
	public String description() {
		return "Remove covered conditions and replace single-value non-strict ranges with equality";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<BinaryExpr, Candidate> replacements = new IdentityHashMap<>();
		context.compilationUnit()
			.findAll(BinaryExpr.class)
			.forEach(expression -> candidate(context, expression)
				.ifPresent(value -> replacements.put(expression, value)));
		List<BinaryExpr> candidates = replacements.keySet()
			.stream()
			.filter(expression -> replacements.keySet()
				.stream()
				.noneMatch(parent -> parent != expression && parent.isAncestorOf(expression)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (BinaryExpr expression : candidates) {
			Candidate candidate = replacements.get(expression);
			findings.add(Finding.at(expression, candidate.message()));
			if (applyFixes) {
				context.editor().replace(expression.getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr expression) {
		if (AstSupport.hasComment(context, expression) || expression.getOperator() != BinaryExpr.Operator.AND
				&& expression.getOperator() != BinaryExpr.Operator.OR) {
			return Optional.empty();
		}
		Comparison left = comparison(expression.getLeft()).orElse(null);
		Comparison right = comparison(expression.getRight()).orElse(null);
		if (left == null || right == null || !left.subject().equals(right.subject())
				|| !ExpressionToolSupport.stable(left.subject())) {
			return Optional.empty();
		}

		Optional<BigInteger> singleton = expression.getOperator() == BinaryExpr.Operator.AND
				? singletonValue(left, right) : Optional.<BigInteger>empty();
		if (singleton.isPresent()) {
			return Optional.of(new Candidate(context.editor().text(left.subject()) + " == " + singleton.orElseThrow(),
					"Replace single-value non-strict range with equality"));
		}
		if (left.direction() != right.direction()) {
			return Optional.empty();
		}
		boolean leftImpliesRight = implies(left, right);
		boolean rightImpliesLeft = implies(right, left);
		Expression retained = null;
		if (expression.getOperator() == BinaryExpr.Operator.OR) {
			if (leftImpliesRight) {
				retained = expression.getRight();
			}
			else if (rightImpliesLeft) {
				retained = expression.getLeft();
			}
		}
		else {
			if (leftImpliesRight) {
				retained = expression.getLeft();
			}
			else if (rightImpliesLeft) {
				retained = expression.getRight();
			}
		}
		return retained == null ? Optional.empty() : Optional
			.of(new Candidate(context.editor().text(retained), "Remove condition covered by a further condition"));
	}

	private static Optional<BigInteger> singletonValue(Comparison left, Comparison right) {
		if (left.direction() == right.direction()) {
			return Optional.empty();
		}
		Comparison lower = left.direction() == Direction.LOWER ? left : right;
		Comparison upper = left.direction() == Direction.UPPER ? left : right;
		BigInteger minimum = lower.strict() ? lower.constant().add(BigInteger.ONE) : lower.constant();
		BigInteger maximum = upper.strict() ? upper.constant().subtract(BigInteger.ONE) : upper.constant();
		return minimum.equals(maximum) ? Optional.of(minimum) : Optional.empty();
	}

	private static boolean implies(Comparison narrower, Comparison broader) {
		if (narrower.direction() != broader.direction()) {
			return false;
		}
		int order = narrower.constant().compareTo(broader.constant());
		if (narrower.direction() == Direction.LOWER) {
			if (order != 0) {
				return order > 0;
			}
		}
		else if (order != 0) {
			return order < 0;
		}
		return narrower.strict() || !broader.strict();
	}

	private static Optional<Comparison> comparison(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression.isEnclosedExpr()) {
			currentExpression = currentExpression.asEnclosedExpr().getInner();
		}
		if (!(currentExpression instanceof BinaryExpr binary)) {
			return Optional.empty();
		}
		Operator operator = binary.getOperator();
		if (!isRange(operator)) {
			return Optional.empty();
		}
		Optional<BigInteger> rightConstant = integer(binary.getRight());
		if (rightConstant.isPresent()) {
			return Optional.of(new Comparison(binary.getLeft(), rightConstant.orElseThrow(), operator));
		}
		Optional<BigInteger> leftConstant = integer(binary.getLeft());
		if (leftConstant.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Comparison(binary.getRight(), leftConstant.orElseThrow(), reverse(operator)));
	}

	private static Optional<BigInteger> integer(Expression expression) {
		if (expression instanceof IntegerLiteralExpr literal) {
			return Optional.of(BigInteger.valueOf(literal.asNumber().longValue()));
		}
		if (expression instanceof LongLiteralExpr literal) {
			return Optional.of(BigInteger.valueOf(literal.asNumber().longValue()));
		}
		if (expression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS) {
			return integer(unary.getExpression()).map(BigInteger::negate);
		}
		return Optional.empty();
	}

	private static boolean isRange(BinaryExpr.Operator operator) {
		return operator == BinaryExpr.Operator.GREATER || operator == BinaryExpr.Operator.GREATER_EQUALS
				|| operator == BinaryExpr.Operator.LESS || operator == BinaryExpr.Operator.LESS_EQUALS;
	}

	private static BinaryExpr.Operator reverse(BinaryExpr.Operator operator) {
		return switch (operator) {
			case GREATER -> BinaryExpr.Operator.LESS;
			case GREATER_EQUALS -> BinaryExpr.Operator.LESS_EQUALS;
			case LESS -> BinaryExpr.Operator.GREATER;
			case LESS_EQUALS -> BinaryExpr.Operator.GREATER_EQUALS;
			default -> throw new IllegalArgumentException("Not a range operator: " + operator);
		};
	}

	private enum Direction {

		LOWER, UPPER

	}

	private record Comparison(Expression subject, BigInteger constant, BinaryExpr.Operator operator) {
		private Direction direction() {
			return operator == BinaryExpr.Operator.GREATER || operator == BinaryExpr.Operator.GREATER_EQUALS
					? Direction.LOWER : Direction.UPPER;
		}

		private boolean strict() {
			return operator == BinaryExpr.Operator.GREATER || operator == BinaryExpr.Operator.LESS;
		}
	}

	private record Candidate(String replacement, String message) {
	}

}
