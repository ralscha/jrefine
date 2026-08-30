package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import java.util.Optional;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Set;

/** Replaces identity Function lambdas with Function.identity(). */
public final class UseMethodCallForLambdaTool implements InspectionTool {

	@Override
	public String id() {
		return "use-method-call-for-lambda";
	}

	@Override
	public String description() {
		return "Replace identity lambdas with Function.identity()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<LambdaExpr> candidates = context.compilationUnit()
			.findAll(LambdaExpr.class)
			.stream()
			.filter(lambda -> candidate(context, lambda))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String function = candidates.isEmpty() ? "Function"
				: ImportSupport.useType(context, "java.util.function.Function", applyFixes);
		for (LambdaExpr lambda : candidates) {
			findings.add(Finding.at(lambda, "Replace identity lambda with Function.identity()"));
			if (applyFixes) {
				context.editor().replace(lambda.getRange().orElseThrow(), function + ".identity()");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean candidate(InspectionContext context, LambdaExpr lambda) {
		if (lambda.getParameters().size() != 1 || lambda.getExpressionBody().isEmpty()
				|| !(lambda.getExpressionBody().orElseThrow() instanceof NameExpr body)
				|| !body.getNameAsString().equals(lambda.getParameter(0).getNameAsString())
				|| AstSupport.hasComment(context, lambda)) {
			return false;
		}
		Optional<VariableDeclarator> variable = lambda.getParentNode()
			.filter(VariableDeclarator.class::isInstance)
			.map(VariableDeclarator.class::cast);
		if (variable.isEmpty() || variable.orElseThrow().getInitializer().orElse(null) != lambda
				|| !(variable.orElseThrow().getType() instanceof ClassOrInterfaceType type)) {
			return false;
		}
		return TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type.asString(), Set.of("Function"));
	}

}
