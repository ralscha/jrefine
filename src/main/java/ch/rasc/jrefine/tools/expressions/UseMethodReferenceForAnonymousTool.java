package ch.rasc.jrefine.tools.expressions;

import ch.rasc.jrefine.analysis.FunctionalAnonymousSupport.AnonymousFunction;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
import java.util.List;

/** Replaces forwarding functional-interface anonymous classes with method references. */
public final class UseMethodReferenceForAnonymousTool implements InspectionTool {

	@Override
	public String id() {
		return "use-method-reference-for-anonymous";
	}

	@Override
	public String description() {
		return "Replace forwarding anonymous classes with method references";
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
			findings.add(Finding.at(candidate.creation(), "Replace anonymous class with method reference"));
			if (applyFixes) {
				context.editor().replace(candidate.creation().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ObjectCreationExpr creation) {
		Optional<AnonymousFunction> function = FunctionalAnonymousSupport.function(context, creation);
		if (function.isEmpty()) {
			return Optional.empty();
		}
		MethodDeclaration method = function.orElseThrow().method();
		BlockStmt body = method.getBody().orElseThrow();
		if (body.getStatements().size() != 1) {
			return Optional.empty();
		}
		Expression expression = null;
		Statement statement = body.getStatement(0);
		if (statement instanceof ReturnStmt returned && returned.getExpression().isPresent()) {
			expression = returned.getExpression().orElseThrow();
		}
		else if (statement instanceof ExpressionStmt expressionStatement) {
			expression = expressionStatement.getExpression();
		}
		if (expression == null) {
			return Optional.empty();
		}
		List<String> parameters = method.getParameters()
			.stream()
			.map(parameter -> parameter.getNameAsString())
			.toList();
		if (expression instanceof MethodCallExpr call && call.getScope().isPresent()
				&& forwarded(parameters, call.getArguments())
				&& call.getScope()
					.orElseThrow()
					.findAll(NameExpr.class)
					.stream()
					.noneMatch(name -> parameters.contains(name.getNameAsString()))) {
			return Optional.of(new Candidate(creation,
					context.editor().text(call.getScope().orElseThrow()) + "::" + call.getNameAsString()));
		}
		if (expression instanceof ObjectCreationExpr constructor && constructor.getAnonymousClassBody().isEmpty()
				&& forwarded(parameters, constructor.getArguments())) {
			return Optional.of(new Candidate(creation, context.editor().text(constructor.getType()) + "::new"));
		}
		return Optional.empty();
	}

	private static boolean forwarded(List<String> parameters, NodeList<Expression> arguments) {
		if (parameters.size() != arguments.size()) {
			return false;
		}
		for (int index = 0; index < parameters.size(); index++) {
			if (!(arguments.get(index) instanceof NameExpr name)
					|| !name.getNameAsString().equals(parameters.get(index))) {
				return false;
			}
		}
		return true;
	}

	private record Candidate(ObjectCreationExpr creation, String replacement) {
	}

}
