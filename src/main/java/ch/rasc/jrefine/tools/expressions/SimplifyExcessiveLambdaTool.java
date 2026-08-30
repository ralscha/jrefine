package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Replaces trivial Optional supplier lambdas with concrete-value overloads. */
public final class SimplifyExcessiveLambdaTool implements InspectionTool {

	private static final Set<String> OPTIONAL_TYPES = Set.of("Optional", "OptionalDouble", "OptionalInt",
			"OptionalLong");

	@Override
	public String id() {
		return "simplify-excessive-lambda";
	}

	@Override
	public String description() {
		return "Replace trivial Optional supplier lambdas with concrete values";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.lambda(), "Replace trivial supplier lambda with a concrete value"));
			if (!applyFixes) {
				continue;
			}
			context.editor().replace(candidate.call().getName().getRange().orElseThrow(), "orElse");
			context.editor()
				.replace(candidate.lambda().getRange().orElseThrow(), context.editor().text(candidate.value()));
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (!"orElseGet".equals(call.getNameAsString()) || call.getArguments().size() != 1
				|| call.getTypeArguments().isPresent() || call.getScope().isEmpty()
				|| !(call.getArgument(0) instanceof LambdaExpr lambda)
				|| !knownOptional(context, call.getScope().orElseThrow(), call)
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Optional<Expression> value = lambdaValue(lambda);
		if (value.isEmpty() || !trivialValue(context, value.orElseThrow(), lambda)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, lambda, value.orElseThrow()));
	}

	private static Optional<Expression> lambdaValue(LambdaExpr lambda) {
		if (!lambda.getParameters().isEmpty()) {
			return Optional.empty();
		}
		if (lambda.getExpressionBody().isPresent()) {
			return Optional.of(unwrap(lambda.getExpressionBody().orElseThrow()));
		}
		if (!(lambda.getBody() instanceof BlockStmt block) || block.getStatements().size() != 1
				|| !(block.getStatement(0) instanceof ReturnStmt returned) || returned.getExpression().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(unwrap(returned.getExpression().orElseThrow()));
	}

	private static boolean trivialValue(InspectionContext context, Expression expression, LambdaExpr lambda) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		return currentExpression instanceof LiteralExpr || currentExpression instanceof NameExpr name && TypeLookup
			.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name.getNameAsString(), lambda);
	}

	private static boolean knownOptional(InspectionContext context, Expression scope, MethodCallExpr use) {
		Expression currentScope = scope;
		currentScope = unwrap(currentScope);
		if (currentScope instanceof NameExpr) {
			String type = TypeLookup.visibleType(context.compilationUnit(), currentScope, use).orElse(null);
			return type != null && TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, OPTIONAL_TYPES);
		}
		if (!(currentScope instanceof MethodCallExpr factory)
				|| !Set.of("empty", "of", "ofNullable").contains(factory.getNameAsString())
				|| factory.getScope().isEmpty()) {
			return false;
		}
		String owner = context.editor().text(unwrap(factory.getScope().orElseThrow()));
		return ExpressionToolSupport.knownType(context.compilationUnit(), owner, "java.util", OPTIONAL_TYPES);
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression instanceof EnclosedExpr enclosed) {
			currentExpression = enclosed.getInner();
		}
		return currentExpression;
	}

	private record Candidate(MethodCallExpr call, LambdaExpr lambda, Expression value) {
	}

}
