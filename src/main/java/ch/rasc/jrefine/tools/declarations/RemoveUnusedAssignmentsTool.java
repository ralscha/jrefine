package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;

/** Removes a local assignment immediately overwritten by the next statement. */
public final class RemoveUnusedAssignmentsTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unused-assignments";
	}

	@Override
	public String description() {
		return "Remove local assignments overwritten before being read";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BlockStmt.class)
			.stream()
			.flatMap(block -> candidates(context, block).stream())
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.assignment(), "Remove unused assignment to '" + candidate.name() + "'"));
			if (applyFixes) {
				if (statementExpression(candidate.assignment().getValue())) {
					context.editor()
						.replace(candidate.assignment().getRange().orElseThrow(),
								context.editor().text(candidate.assignment().getValue()));
				}
				else {
					context.editor().removeLine(candidate.statement());
				}
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static List<Candidate> candidates(InspectionContext context, BlockStmt block) {
		ArrayList<Candidate> result = new ArrayList<>();
		for (int index = 0; index + 1 < block.getStatements().size(); index++) {
			candidate(context, block.getStatement(index), block.getStatement(index + 1)).ifPresent(result::add);
		}
		return result;
	}

	private static Optional<Candidate> candidate(InspectionContext context, Statement first, Statement second) {
		if (!(first instanceof ExpressionStmt statement)
				|| !(statement.getExpression() instanceof AssignExpr assignment)
				|| assignment.getOperator() != AssignExpr.Operator.ASSIGN
				|| !(assignment.getTarget() instanceof NameExpr target)
				|| !(second instanceof ExpressionStmt nextStatement)
				|| !(nextStatement.getExpression() instanceof AssignExpr next)
				|| next.getOperator() != AssignExpr.Operator.ASSIGN
				|| !(next.getTarget() instanceof NameExpr nextTarget)
				|| !target.getNameAsString().equals(nextTarget.getNameAsString())
				|| reads(next.getValue(), target.getNameAsString()) || AstSupport.hasComment(context, statement)
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), target.getNameAsString(),
						assignment)) {
			return Optional.empty();
		}
		if (!pure(assignment.getValue()) && !statementExpression(assignment.getValue())) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(statement, assignment, target.getNameAsString()));
	}

	private static boolean reads(Expression expression, String name) {
		return expression.findAll(NameExpr.class)
			.stream()
			.anyMatch(reference -> reference.getNameAsString().equals(name));
	}

	private static boolean pure(Expression expression) {
		if (expression.isLiteralExpr() || expression.isNameExpr() || expression.isThisExpr()) {
			return true;
		}
		if (expression.isFieldAccessExpr()) {
			return pure(expression.asFieldAccessExpr().getScope());
		}
		if (expression.isCastExpr()) {
			return pure(expression.asCastExpr().getExpression());
		}
		if (expression.isUnaryExpr()) {
			return switch (expression.asUnaryExpr().getOperator()) {
				case PLUS, MINUS, LOGICAL_COMPLEMENT, BITWISE_COMPLEMENT ->
					pure(expression.asUnaryExpr().getExpression());
				default -> false;
			};
		}
		return expression.isBinaryExpr() && pure(expression.asBinaryExpr().getLeft())
				&& pure(expression.asBinaryExpr().getRight());
	}

	private static boolean statementExpression(Expression expression) {
		if (expression.isMethodCallExpr() || expression instanceof ObjectCreationExpr || expression.isAssignExpr()) {
			return true;
		}
		return expression instanceof UnaryExpr unary && switch (unary.getOperator()) {
			case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
			default -> false;
		};
	}

	private record Candidate(ExpressionStmt statement, AssignExpr assignment, String name) {
	}

}
