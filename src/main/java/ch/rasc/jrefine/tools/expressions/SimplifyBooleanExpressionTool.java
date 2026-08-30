package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Applies small, type-safe boolean algebra simplifications. */
public final class SimplifyBooleanExpressionTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-boolean-expression";
	}

	@Override
	public String description() {
		return "Simplify boolean constants, conditionals, double negations, and negated comparisons";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<Expression, String> replacements = new IdentityHashMap<>();
		context.compilationUnit()
			.findAll(Expression.class)
			.forEach(expression -> replacement(context, expression)
				.ifPresent(value -> replacements.put(expression, value)));
		List<Expression> candidates = replacements.keySet()
			.stream()
			.filter(expression -> ancestors(expression).filter(Expression.class::isInstance)
				.map(Expression.class::cast)
				.noneMatch(replacements::containsKey))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Expression expression : candidates) {
			findings.add(Finding.at(expression, "Simplify boolean expression"));
			if (applyFixes) {
				context.editor().replace(expression.getRange().orElseThrow(), replacements.get(expression));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Stream<Node> ancestors(Node node) {
		return java.util.stream.Stream
			.iterate(node.getParentNode(), Optional::isPresent, parent -> parent.orElseThrow().getParentNode())
			.map(Optional::orElseThrow);
	}

	private static Optional<String> replacement(InspectionContext context, Expression expression) {
		if (hasComment(context.editor().text(expression))) {
			return java.util.Optional.empty();
		}
		if (expression instanceof UnaryExpr outer && outer.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
			Expression operand = outer.getExpression();
			if (operand instanceof UnaryExpr inner && inner.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
				return java.util.Optional.of(context.editor().text(inner.getExpression()));
			}
			if (operand.isEnclosedExpr()) {
				operand = operand.asEnclosedExpr().getInner();
			}
			if (operand instanceof BinaryExpr binary) {
				Operator inverse = inverse(binary.getOperator());
				if (inverse != null) {
					return java.util.Optional.of(context.editor().text(binary.getLeft()) + " " + inverse.asString()
							+ " " + context.editor().text(binary.getRight()));
				}
			}
		}
		if (expression instanceof BinaryExpr binary) {
			return simplifyConstant(context, binary);
		}
		if (expression instanceof ConditionalExpr conditional) {
			Boolean thenValue = booleanValue(conditional.getThenExpr());
			Boolean elseValue = booleanValue(conditional.getElseExpr());
			if (Boolean.TRUE.equals(thenValue) && Boolean.FALSE.equals(elseValue)) {
				return java.util.Optional.of(context.editor().text(conditional.getCondition()));
			}
			if (Boolean.FALSE.equals(thenValue) && Boolean.TRUE.equals(elseValue)) {
				return java.util.Optional.of(negate(context, conditional.getCondition()));
			}
		}
		return java.util.Optional.empty();
	}

	private static Optional<String> simplifyConstant(InspectionContext context, BinaryExpr binary) {
		Boolean leftBoolean = booleanValue(binary.getLeft());
		Boolean rightBoolean = booleanValue(binary.getRight());
		Operator operator = binary.getOperator();
		if (operator == BinaryExpr.Operator.AND || operator == BinaryExpr.Operator.OR) {
			if (leftBoolean != null) {
				return logicalConstant(context, binary.getRight(), leftBoolean, operator, true);
			}
			if (rightBoolean != null) {
				return logicalConstant(context, binary.getLeft(), rightBoolean, operator, false);
			}
		}
		if (operator == BinaryExpr.Operator.EQUALS || operator == BinaryExpr.Operator.NOT_EQUALS) {
			if (leftBoolean != null) {
				return java.util.Optional.of(equalityConstant(context, binary.getRight(), leftBoolean, operator));
			}
			if (rightBoolean != null) {
				return java.util.Optional.of(equalityConstant(context, binary.getLeft(), rightBoolean, operator));
			}
		}
		return java.util.Optional.empty();
	}

	private static Optional<String> logicalConstant(InspectionContext context, Expression other, boolean constant,
			BinaryExpr.Operator operator, boolean constantOnLeft) {
		if (!constantOnLeft && (operator == BinaryExpr.Operator.AND && !constant
				|| operator == BinaryExpr.Operator.OR && constant)) {
			return java.util.Optional.empty();
		}
		if (operator == BinaryExpr.Operator.AND) {
			return java.util.Optional.of(constant ? context.editor().text(other) : "false");
		}
		return java.util.Optional.of(constant ? "true" : context.editor().text(other));
	}

	private static String equalityConstant(InspectionContext context, Expression other, boolean constant,
			BinaryExpr.Operator operator) {
		boolean keep = constant == (operator == BinaryExpr.Operator.EQUALS);
		return keep ? context.editor().text(other) : negate(context, other);
	}

	private static Boolean booleanValue(Expression expression) {
		return expression instanceof BooleanLiteralExpr literal ? literal.getValue() : null;
	}

	private static String negate(InspectionContext context, Expression expression) {
		if (expression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
			return context.editor().text(unary.getExpression());
		}
		String text = context.editor().text(expression);
		return isPrimary(expression) ? "!" + text : "!(" + text + ")";
	}

	private static boolean isPrimary(Expression expression) {
		return expression.isNameExpr() || expression.isLiteralExpr() || expression.isFieldAccessExpr()
				|| expression.isMethodCallExpr() || expression.isThisExpr() || expression.isSuperExpr()
				|| expression.isArrayAccessExpr();
	}

	private static BinaryExpr.Operator inverse(BinaryExpr.Operator operator) {
		return switch (operator) {
			case EQUALS -> BinaryExpr.Operator.NOT_EQUALS;
			case NOT_EQUALS -> BinaryExpr.Operator.EQUALS;
			default -> null;
		};
	}

	private static boolean hasComment(String source) {
		return source.contains("//") || source.contains("/*");
	}

}
