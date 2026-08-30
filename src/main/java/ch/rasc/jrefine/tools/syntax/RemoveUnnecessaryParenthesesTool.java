package ch.rasc.jrefine.tools.syntax;

import com.github.javaparser.ast.Node;
import java.util.List;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Removes parentheses only when Java's precedence and evaluation order remain unchanged.
 */
public final class RemoveUnnecessaryParenthesesTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-parentheses";
	}

	@Override
	public String description() {
		return "Remove parentheses that do not affect parsing or evaluation order";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(EnclosedExpr.class)
			.stream()
			.filter(expression -> !(expression.getParentNode().orElse(null) instanceof EnclosedExpr))
			.map(expression -> candidate(context, expression))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.enclosed(), "Remove unnecessary parentheses"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.enclosed().getRange().orElseThrow(), context.editor().text(candidate.inner()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, EnclosedExpr enclosed) {
		if (context.editor().text(enclosed).contains("/*") || context.editor().text(enclosed).contains("//")) {
			return java.util.Optional.empty();
		}
		Expression inner = enclosed.getInner();
		while (inner instanceof EnclosedExpr nested) {
			inner = nested.getInner();
		}
		return safeWithoutParentheses(enclosed, inner) ? java.util.Optional.of(new Candidate(enclosed, inner))
				: java.util.Optional.empty();
	}

	private static boolean safeWithoutParentheses(EnclosedExpr enclosed, Expression inner) {
		Node parent = enclosed.getParentNode().orElse(null);
		if (parent == null || parent instanceof EnclosedExpr) {
			return true;
		}
		if (!(parent instanceof Expression parentExpression)) {
			return true;
		}
		if (isPrimary(inner)) {
			return true;
		}

		if (parentExpression instanceof BinaryExpr binary) {
			int innerPrecedence = precedence(inner);
			int parentPrecedence = precedence(binary);
			if (innerPrecedence > parentPrecedence) {
				return true;
			}
			return innerPrecedence == parentPrecedence && binary.getLeft() == enclosed;
		}
		if (parentExpression instanceof MethodCallExpr call) {
			return call.getArguments().stream().anyMatch(argument -> argument == enclosed);
		}
		if (parentExpression instanceof ObjectCreationExpr creation) {
			return creation.getArguments().stream().anyMatch(argument -> argument == enclosed);
		}
		if (parentExpression instanceof AssignExpr assignment) {
			return assignment.getValue() == enclosed;
		}
		if (parentExpression instanceof ConditionalExpr conditional) {
			return conditional.getCondition() == enclosed && precedence(inner) > precedence(conditional);
		}
		if (parentExpression instanceof UnaryExpr || parentExpression instanceof CastExpr) {
			return false;
		}
		if (parentExpression instanceof FieldAccessExpr access) {
			return access.getScope() != enclosed;
		}
		if (parentExpression instanceof ArrayAccessExpr access) {
			return access.getIndex() == enclosed;
		}
		return parent instanceof VariableDeclarator;
	}

	private static boolean isPrimary(Expression expression) {
		return expression.isNameExpr() || expression.isLiteralExpr() || expression.isThisExpr()
				|| expression.isSuperExpr() || expression.isFieldAccessExpr() || expression.isMethodCallExpr()
				|| expression.isObjectCreationExpr() || expression.isArrayAccessExpr() || expression.isClassExpr()
				|| expression.isArrayCreationExpr();
	}

	private static int precedence(Expression expression) {
		if (isPrimary(expression)) {
			return 100;
		}
		if (expression instanceof UnaryExpr) {
			return 90;
		}
		if (expression instanceof CastExpr) {
			return 85;
		}
		if (expression instanceof BinaryExpr binary) {
			return switch (binary.getOperator()) {
				case MULTIPLY, DIVIDE, REMAINDER -> 80;
				case PLUS, MINUS -> 70;
				case LEFT_SHIFT, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> 60;
				case LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> 50;
				case EQUALS, NOT_EQUALS -> 45;
				case BINARY_AND -> 40;
				case XOR -> 39;
				case BINARY_OR -> 38;
				case AND -> 30;
				case OR -> 20;
			};
		}
		if (expression instanceof ConditionalExpr) {
			return 10;
		}
		if (expression instanceof AssignExpr) {
			return 5;
		}
		return 0;
	}

	private record Candidate(EnclosedExpr enclosed, Expression inner) {
	}

}
