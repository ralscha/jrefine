package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import java.util.Optional;

/** Converts one-statement lambda blocks to expression bodies. */
public final class UseExpressionLambdaTool implements InspectionTool {

	@Override
	public String id() {
		return "use-expression-lambda";
	}

	@Override
	public String description() {
		return "Replace one-statement lambda blocks with expression bodies";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(LambdaExpr.class)
			.stream()
			.map(lambda -> candidate(context, lambda))
			.flatMap(Optional::stream)
			.filter(candidate -> candidate.lambda()
				.findAll(LambdaExpr.class)
				.stream()
				.filter(nested -> nested != candidate.lambda())
				.noneMatch(nested -> candidate(context, nested).isPresent()))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.lambda(), "Replace statement lambda with expression lambda"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.body().getRange().orElseThrow(), context.editor().text(candidate.expression()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, LambdaExpr lambda) {
		if (!(lambda.getBody() instanceof BlockStmt body) || body.getStatements().size() != 1
				|| AstSupport.hasComment(context, body)) {
			return java.util.Optional.empty();
		}
		Statement statement = body.getStatement(0);
		Expression expression = null;
		if (statement instanceof ReturnStmt returned && returned.getExpression().isPresent()) {
			expression = returned.getExpression().orElseThrow();
		}
		else if (statement instanceof ExpressionStmt expressionStatement) {
			expression = expressionStatement.getExpression();
		}
		if (expression != null && isStatementExpression(expression) && hasOverloadSensitiveTarget(lambda)) {
			return java.util.Optional.empty();
		}
		return expression == null ? java.util.Optional.empty()
				: java.util.Optional.of(new Candidate(lambda, body, expression));
	}

	private static boolean isStatementExpression(Expression expression) {
		if (expression instanceof AssignExpr || expression instanceof MethodCallExpr
				|| expression instanceof ObjectCreationExpr) {
			return true;
		}
		return expression instanceof UnaryExpr unary && switch (unary.getOperator()) {
			case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> true;
			default -> false;
		};
	}

	private static boolean hasOverloadSensitiveTarget(LambdaExpr lambda) {
		Node current = lambda;
		Optional<Node> parent = current.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			Node child = current;
			if (value instanceof CastExpr) {
				return false;
			}
			if (value instanceof MethodCallExpr call && call.getArguments()
				.stream()
				.anyMatch(argument -> argument == child || argument.isAncestorOf(child))) {
				return true;
			}
			if (value instanceof ObjectCreationExpr creation && creation.getArguments()
				.stream()
				.anyMatch(argument -> argument == child || argument.isAncestorOf(child))) {
				return true;
			}
			if (value instanceof ExplicitConstructorInvocationStmt call && call.getArguments()
				.stream()
				.anyMatch(argument -> argument == child || argument.isAncestorOf(child))) {
				return true;
			}
			if (value instanceof VariableDeclarator || value instanceof AssignExpr || value instanceof ReturnStmt) {
				return false;
			}
			current = value;
			parent = value.getParentNode();
		}
		return false;
	}

	private record Candidate(LambdaExpr lambda, BlockStmt body, Expression expression) {
	}

}
