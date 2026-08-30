package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Converts canonical comparison lambdas to Comparator.comparing(). */
public final class UseComparatorCombinatorsTool implements InspectionTool {

	@Override
	public String id() {
		return "use-comparator-combinators";
	}

	@Override
	public String description() {
		return "Replace manual comparison lambdas with Comparator combinators";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(LambdaExpr.class)
			.stream()
			.map(lambda -> candidate(context, lambda))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.lambda(), "Replace comparison lambda with Comparator.comparing()"));
			if (applyFixes) {
				String typeName = comparatorName(candidate.lambda());
				String parameter = candidate.lambda().getParameter(0).getNameAsString();
				context.editor()
					.replace(candidate.lambda().getRange().orElseThrow(), typeName + ".comparing(" + parameter + " -> "
							+ context.editor().text(candidate.leftProjection()) + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, LambdaExpr lambda) {
		if (lambda.getParameters().size() != 2 || lambda.getExpressionBody().isEmpty()
				|| AstSupport.hasComment(context, lambda) || !comparatorTarget(context, lambda)) {
			return Optional.empty();
		}
		if (!(lambda.getExpressionBody().orElseThrow() instanceof MethodCallExpr compare)
				|| !"compareTo".equals(compare.getNameAsString()) || compare.getScope().isEmpty()
				|| compare.getArguments().size() != 1) {
			return Optional.empty();
		}
		String leftName = lambda.getParameter(0).getNameAsString();
		String rightName = lambda.getParameter(1).getNameAsString();
		Expression left = compare.getScope().orElseThrow();
		Expression right = compare.getArgument(0);
		if (!usesOnly(left, leftName) || !usesOnly(right, rightName)
				|| !normalized(left, leftName).equals(normalized(right, rightName))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(lambda, left));
	}

	private static boolean comparatorTarget(InspectionContext context, LambdaExpr lambda) {
		return AstSupport.ancestor(lambda, VariableDeclarator.class)
			.filter(variable -> variable.getInitializer().orElse(null) == lambda)
			.filter(variable -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), variable.getType().asString(),
					Set.of("Comparator")))
			.isPresent();
	}

	private static String comparatorName(LambdaExpr lambda) {
		String type = AstSupport.ancestor(lambda, VariableDeclarator.class).orElseThrow().getType().asString();
		return type.startsWith("java.util.Comparator") ? "java.util.Comparator" : "Comparator";
	}

	private static boolean usesOnly(Expression expression, String parameter) {
		List<NameExpr> names = expression.findAll(NameExpr.class);
		return !names.isEmpty() && names.stream().allMatch(name -> name.getNameAsString().equals(parameter));
	}

	private static Expression normalized(Expression expression, String parameter) {
		Expression clone = expression.clone();
		clone.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(parameter))
			.forEach(name -> name.setName("value"));
		return clone;
	}

	private record Candidate(LambdaExpr lambda, Expression leftProjection) {
	}

}
