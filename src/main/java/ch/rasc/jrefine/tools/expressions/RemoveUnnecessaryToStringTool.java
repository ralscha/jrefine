package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Set;

/** Removes toString() calls already performed by string concatenation. */
public final class RemoveUnnecessaryToStringTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-to-string";
	}

	@Override
	public String description() {
		return "Remove toString() calls made redundant by string concatenation";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> "toString".equals(call.getNameAsString()) && call.getArguments().isEmpty()
					&& call.getScope().isPresent())
			.filter(call -> !AstSupport.hasComment(context, call))
			.filter(call -> stringConcatenationContext(context, call))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : candidates) {
			findings.add(Finding.at(call, "Remove unnecessary call to toString()"));
			if (applyFixes) {
				context.editor()
					.replace(call.getRange().orElseThrow(), context.editor().text(call.getScope().orElseThrow()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean stringConcatenationContext(InspectionContext context, MethodCallExpr call) {
		if (!(call.getParentNode().orElse(null) instanceof BinaryExpr binary)
				|| binary.getOperator() != BinaryExpr.Operator.PLUS) {
			return false;
		}
		Expression other = binary.getLeft() == call ? binary.getRight() : binary.getLeft();
		return stringExpression(context, other, binary);
	}

	private static boolean stringExpression(InspectionContext context, Expression expression, BinaryExpr use) {
		if (expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr) {
			return true;
		}
		if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
			return stringExpression(context, binary.getLeft(), use)
					|| stringExpression(context, binary.getRight(), use);
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
			.isPresent();
	}

}
