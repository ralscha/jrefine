package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/**
 * Removes Objects.requireNonNull() around expressions that are intrinsically non-null.
 */
public final class SimplifyObviousNullCheckTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-obvious-null-check";
	}

	@Override
	public String description() {
		return "Remove null-check calls whose argument is obviously non-null";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : candidates) {
			findings.add(Finding.at(call, "Objects.requireNonNull() is called with an obviously non-null argument"));
			if (applyFixes) {
				context.editor().replace(call.getRange().orElseThrow(), context.editor().text(call.getArgument(0)));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<MethodCallExpr> candidate(InspectionContext context, MethodCallExpr call) {
		if (!"requireNonNull".equals(call.getNameAsString()) || call.getArguments().size() != 1
				|| call.getScope().isEmpty() || AstSupport.hasComment(context, call)
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), call.getScope().orElseThrow().toString(),
						"java.util", Set.of("Objects"))
				|| !obviouslyNonNull(call.getArgument(0))) {
			return Optional.empty();
		}
		return Optional.of(call);
	}

	private static boolean obviouslyNonNull(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression.isEnclosedExpr()) {
			currentExpression = currentExpression.asEnclosedExpr().getInner();
		}
		return currentExpression.isObjectCreationExpr() || currentExpression.isArrayCreationExpr()
				|| currentExpression.isStringLiteralExpr() || currentExpression.isTextBlockLiteralExpr()
				|| currentExpression.isClassExpr() || currentExpression.isThisExpr() || currentExpression.isSuperExpr()
				|| currentExpression.isLambdaExpr() || currentExpression.isMethodReferenceExpr();
	}

}
