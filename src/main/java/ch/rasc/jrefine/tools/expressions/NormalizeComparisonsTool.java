package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Moves literal constants to the right side of comparisons. */
public final class NormalizeComparisonsTool implements InspectionTool {

	@Override
	public String id() {
		return "normalize-comparisons";
	}

	@Override
	public String description() {
		return "Move literal constants to the right side of comparisons";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<BinaryExpr> candidates = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.filter(binary -> binary.getLeft().isLiteralExpr() && !binary.getRight().isLiteralExpr())
			.filter(binary -> inverse(binary.getOperator()) != null)
			.filter(binary -> !AstSupport.hasComment(context, binary))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (BinaryExpr binary : candidates) {
			findings.add(Finding.at(binary, "Move comparison constant to the right side"));
			if (applyFixes) {
				String replacement = context.editor().text(binary.getRight()) + " "
						+ inverse(binary.getOperator()).asString() + " " + context.editor().text(binary.getLeft());
				context.editor().replace(binary.getRange().orElseThrow(), replacement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static BinaryExpr.Operator inverse(BinaryExpr.Operator operator) {
		return switch (operator) {
			case EQUALS, NOT_EQUALS -> operator;
			case LESS -> BinaryExpr.Operator.GREATER;
			case LESS_EQUALS -> BinaryExpr.Operator.GREATER_EQUALS;
			case GREATER -> BinaryExpr.Operator.LESS;
			case GREATER_EQUALS -> BinaryExpr.Operator.LESS_EQUALS;
			default -> null;
		};
	}

}
