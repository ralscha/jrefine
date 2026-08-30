package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.FunctionalAnonymousSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Replaces single-method functional-interface anonymous classes with lambdas. */
public final class UseLambdaForAnonymousTool implements InspectionTool {

	@Override
	public String id() {
		return "use-lambda-for-anonymous";
	}

	@Override
	public String description() {
		return "Replace functional-interface anonymous classes with lambdas";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.map(creation -> candidate(context, creation))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.function().creation(), "Replace anonymous class with lambda"));
			if (applyFixes) {
				context.editor().replace(candidate.function().creation().getRange().orElseThrow(), candidate.lambda());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ObjectCreationExpr creation) {
		return FunctionalAnonymousSupport.function(context, creation).map(function -> {
			MethodDeclaration method = function.method();
			BlockStmt body = method.getBody().orElseThrow();
			String bodyText = context.editor().text(body);
			if (body.getStatements().size() == 1) {
				Expression expression = null;
				Statement statement = body.getStatement(0);
				if (statement instanceof ReturnStmt returned && returned.getExpression().isPresent()) {
					expression = returned.getExpression().orElseThrow();
				}
				else if (statement instanceof ExpressionStmt expressionStatement) {
					expression = expressionStatement.getExpression();
				}
				if (expression != null) {
					bodyText = context.editor().text(expression);
				}
			}
			return new Candidate(function, FunctionalAnonymousSupport.lambdaParameters(method) + " -> " + bodyText);
		});
	}

	private record Candidate(FunctionalAnonymousSupport.AnonymousFunction function, String lambda) {
	}

}
