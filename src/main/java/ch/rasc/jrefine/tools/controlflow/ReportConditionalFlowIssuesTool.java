package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;

/** Reports verbose, duplicated, negated, nested, and otherwise suspicious conditions. */
public final class ReportConditionalFlowIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-conditional-flow-issues";
	}

	@Override
	public String description() {
		return "Report duplicated, negated, nested, factorable, and redundant conditions";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		ifStatements(context, findings);
		conditionals(context, findings);
		booleanExpressions(context, findings);
		nullChecks(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void ifStatements(InspectionContext context, List<Finding> findings) {
		for (IfStmt statement : context.compilationUnit().findAll(IfStmt.class)) {
			if (statement.getElseStmt().isPresent()) {
				Statement otherwise = statement.getElseStmt().orElseThrow();
				if (statement.getThenStmt().equals(otherwise)) {
					findings.add(Finding.at(statement, "'if' statement has identical branches"));
				}
				if (commonEdge(statement.getThenStmt(), otherwise)) {
					findings
						.add(Finding.at(statement, "'if' branches contain a common statement that can be extracted"));
				}
				if (terminates(statement.getThenStmt())) {
					findings.add(Finding.at(otherwise, "Redundant 'else' after an unconditionally terminating branch"));
				}
			}
			if (redundantIf(statement)) {
				findings.add(Finding.at(statement, "Redundant 'if' statement can be replaced with one expression"));
			}
			duplicateIfCondition(statement)
				.ifPresent(duplicate -> findings.add(Finding.at(duplicate, "Duplicate condition in if/else-if chain")));
		}
	}

	private static boolean commonEdge(Statement left, Statement right) {
		List<Statement> leftStatements = statements(left);
		List<Statement> rightStatements = statements(right);
		if (leftStatements.isEmpty() || rightStatements.isEmpty()) {
			return false;
		}
		return leftStatements.getFirst().equals(rightStatements.getFirst())
				|| leftStatements.getLast().equals(rightStatements.getLast());
	}

	private static List<Statement> statements(Statement statement) {
		return statement instanceof BlockStmt block ? block.getStatements() : List.of(statement);
	}

	private static boolean terminates(Statement statement) {
		List<Statement> values = statements(statement);
		if (values.isEmpty()) {
			return false;
		}
		Statement last = values.getLast();
		return last instanceof ReturnStmt || last instanceof ThrowStmt;
	}

	private static boolean redundantIf(IfStmt statement) {
		if (statement.getElseStmt().isEmpty()) {
			return false;
		}
		Statement left = only(statements(statement.getThenStmt()));
		Statement right = only(statements(statement.getElseStmt().orElseThrow()));
		if (left instanceof ReturnStmt leftReturn && right instanceof ReturnStmt rightReturn) {
			return booleanLiteral(leftReturn.getExpression().orElse(null)) != null
					&& booleanLiteral(rightReturn.getExpression().orElse(null)) != null;
		}
		if (left instanceof ExpressionStmt leftStatement && right instanceof ExpressionStmt rightStatement
				&& leftStatement.getExpression() instanceof AssignExpr leftAssign
				&& rightStatement.getExpression() instanceof AssignExpr rightAssign) {
			return leftAssign.getTarget().equals(rightAssign.getTarget())
					&& booleanLiteral(leftAssign.getValue()) != null && booleanLiteral(rightAssign.getValue()) != null;
		}
		return false;
	}

	private static Statement only(List<Statement> statements) {
		return statements.size() == 1 ? statements.getFirst() : null;
	}

	private static Boolean booleanLiteral(Expression expression) {
		return expression instanceof BooleanLiteralExpr literal ? literal.getValue() : null;
	}

	private static Optional<Expression> duplicateIfCondition(IfStmt statement) {
		Expression condition = unwrap(statement.getCondition());
		Statement next = statement.getElseStmt().orElse(null);
		while (next instanceof IfStmt other) {
			if (condition.equals(unwrap(other.getCondition()))) {
				return Optional.of(other.getCondition());
			}
			next = other.getElseStmt().orElse(null);
		}
		return Optional.empty();
	}

	private static void conditionals(InspectionContext context, List<Finding> findings) {
		for (ConditionalExpr expression : context.compilationUnit().findAll(ConditionalExpr.class)) {
			if (expression.getThenExpr().equals(expression.getElseExpr())) {
				findings.add(Finding.at(expression, "Conditional expression has identical branches"));
			}
			if (expression.getCondition() instanceof UnaryExpr unary
					&& unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
				findings.add(Finding.at(expression, "Conditional expression has a negated condition"));
			}
			if (unwrap(expression.getCondition()) instanceof BooleanLiteralExpr) {
				findings.add(Finding.at(expression, "Constant conditional expression"));
			}
			if (negatedParent(expression)) {
				findings.add(Finding.at(expression, "Negated conditional expression is confusing"));
			}
		}
	}

	private static boolean negatedParent(Expression expression) {
		Node current = expression;
		while (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed) {
			current = enclosed;
		}
		return current.getParentNode()
			.filter(parent -> parent instanceof UnaryExpr unary
					&& unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT)
			.isPresent();
	}

	private static void booleanExpressions(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			if ((binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR)
					&& unwrap(binary.getLeft()).equals(unwrap(binary.getRight()))) {
				findings.add(Finding.at(binary, "Duplicate condition in boolean expression"));
			}
			if ((binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR)
					&& (binary.getLeft() instanceof BooleanLiteralExpr
							|| binary.getRight() instanceof BooleanLiteralExpr)) {
				findings.add(Finding.at(binary, "Pointless boolean expression contains a constant term"));
			}
			if (pointlessIndexOf(binary)) {
				findings.add(Finding.at(binary, "Pointless indexOf() comparison has a constant result"));
			}
		}
		for (UnaryExpr unary : context.compilationUnit().findAll(UnaryExpr.class)) {
			if (unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
				continue;
			}
			Expression operand = unwrap(unary.getExpression());
			if (operand instanceof UnaryExpr inner && inner.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
				findings.add(Finding.at(unary, "Double negation"));
			}
			if (operand instanceof BinaryExpr binary && (binary.getOperator() == BinaryExpr.Operator.EQUALS
					|| binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS)) {
				findings.add(Finding.at(unary, "Negated equality expression"));
			}
		}
		context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.filter(ReportConditionalFlowIssuesTool::conditionalForm)
			.forEach(expression -> findings
				.add(Finding.at(expression, "Boolean expression can be replaced with a conditional expression")));
	}

	private static boolean pointlessIndexOf(BinaryExpr binary) {
		MethodCallExpr call = binary.getLeft() instanceof MethodCallExpr left ? left
				: binary.getRight() instanceof MethodCallExpr right ? right : null;
		Expression other = call == binary.getLeft() ? binary.getRight() : binary.getLeft();
		if (call == null || !"indexOf".equals(call.getNameAsString()) || call != binary.getLeft()) {
			return false;
		}
		Integer value = signedInteger(other).orElse(null);
		if (value == null) {
			return false;
		}
		return value < -1 || value == -1
				&& Set.of(BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.LESS).contains(binary.getOperator());
	}

	private static Optional<Integer> signedInteger(Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (currentExpression instanceof IntegerLiteralExpr literal) {
			return Optional.of(literal.asNumber().intValue());
		}
		if (currentExpression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS
				&& unwrap(unary.getExpression()) instanceof IntegerLiteralExpr literal) {
			return Optional.of(-literal.asNumber().intValue());
		}
		return Optional.empty();
	}

	private static boolean conditionalForm(BinaryExpr expression) {
		if (expression.getOperator() != BinaryExpr.Operator.OR) {
			return false;
		}
		if (!(unwrap(expression.getLeft()) instanceof BinaryExpr left)
				|| !(unwrap(expression.getRight()) instanceof BinaryExpr right)
				|| left.getOperator() != BinaryExpr.Operator.AND || right.getOperator() != BinaryExpr.Operator.AND) {
			return false;
		}
		Expression leftCondition = unwrap(left.getLeft());
		Expression rightCondition = unwrap(right.getLeft());
		return negationOf(leftCondition, rightCondition) || negationOf(rightCondition, leftCondition);
	}

	private static boolean negationOf(Expression left, Expression right) {
		return left instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
				&& unwrap(unary.getExpression()).equals(unwrap(right));
	}

	private static void nullChecks(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			if (binary.getOperator() != BinaryExpr.Operator.AND) {
				continue;
			}
			String checked = nonNullName(binary.getLeft()).orElse(null);
			if (checked == null) {
				continue;
			}
			if (binary.getRight()
				.findAll(MethodCallExpr.class)
				.stream()
				.anyMatch(
						call -> "equals".equals(call.getNameAsString()) && call.getArguments().size() == 1
								&& call.getArgument(0).toString().equals(checked)
								&& call.getScope().filter(scope -> scope instanceof StringLiteralExpr).isPresent()
								|| "equals".equals(call.getNameAsString())
										&& call.getScope()
											.filter(scope -> Set.of("Objects", "java.util.Objects")
												.contains(scope.toString()))
											.isPresent()
										&& call.getArguments()
											.stream()
											.anyMatch(argument -> argument.toString().equals(checked)))) {
				findings
					.add(Finding.at(binary, "Unnecessary null check before a method call that already handles null"));
			}
		}
	}

	private static Optional<String> nonNullName(Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (!(currentExpression instanceof BinaryExpr binary)
				|| binary.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
			return Optional.empty();
		}
		if (binary.getLeft() instanceof NameExpr name && binary.getRight() instanceof NullLiteralExpr) {
			return Optional.of(name.getNameAsString());
		}
		if (binary.getRight() instanceof NameExpr name && binary.getLeft() instanceof NullLiteralExpr) {
			return Optional.of(name.getNameAsString());
		}
		return Optional.empty();
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression.isEnclosedExpr()) {
			currentExpression = currentExpression.asEnclosedExpr().getInner();
		}
		return currentExpression;
	}

}
