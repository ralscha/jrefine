package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import com.github.javaparser.ast.expr.ThisExpr;

/** Uses Thread and ThreadLocal lambda-taking alternatives for anonymous subclasses. */
public final class UseShorterLambdaAlternativeTool implements InspectionTool {

	@Override
	public String id() {
		return "use-shorter-lambda-alternative";
	}

	@Override
	public String description() {
		return "Replace Thread and ThreadLocal anonymous classes with lambda alternatives";
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
			findings.add(Finding.at(candidate.creation(), "Use shorter lambda alternative"));
			if (applyFixes) {
				context.editor().replace(candidate.creation().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ObjectCreationExpr creation) {
		if (creation.getAnonymousClassBody().isEmpty() || !creation.getArguments().isEmpty()
				|| AstSupport.hasComment(context, creation)
				|| creation.getAnonymousClassBody().orElseThrow().size() != 1
				|| !(creation.getAnonymousClassBody().orElseThrow().get(0) instanceof MethodDeclaration method)
				|| method.getBody().isEmpty() || !method.getParameters().isEmpty()
				|| !method.findAll(ThisExpr.class).isEmpty()
				|| !knownJavaLang(context, creation.getType().getNameAsString())) {
			return Optional.empty();
		}
		String lambdaBody = lambdaBody(context, method);
		if (lambdaBody == null) {
			return Optional.empty();
		}
		if ("Thread".equals(creation.getType().getNameAsString()) && "run".equals(method.getNameAsString())
				&& method.getType().isVoidType()) {
			return Optional.of(new Candidate(creation, "new Thread(() -> " + lambdaBody + ")"));
		}
		if ("ThreadLocal".equals(creation.getType().getNameAsString())
				&& "initialValue".equals(method.getNameAsString()) && !method.getType().isVoidType()) {
			return Optional.of(new Candidate(creation, "ThreadLocal.withInitial(() -> " + lambdaBody + ")"));
		}
		return Optional.empty();
	}

	private static String lambdaBody(InspectionContext context, MethodDeclaration method) {
		BlockStmt body = method.getBody().orElseThrow();
		if (body.getStatements().size() != 1) {
			return context.editor().text(body);
		}
		Statement statement = body.getStatement(0);
		Expression expression = null;
		if (statement instanceof ReturnStmt returned && returned.getExpression().isPresent()) {
			expression = returned.getExpression().orElseThrow();
		}
		else if (statement instanceof ExpressionStmt expressionStatement) {
			expression = expressionStatement.getExpression();
		}
		return expression == null ? context.editor().text(body) : context.editor().text(expression);
	}

	private static boolean knownJavaLang(InspectionContext context, String name) {
		if (!java.util.Set.of("Thread", "ThreadLocal").contains(name)) {
			return false;
		}
		return context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.noneMatch(type -> type.getNameAsString().equals(name));
	}

	private record Candidate(ObjectCreationExpr creation, String replacement) {
	}

}
