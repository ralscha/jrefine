package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.Node;

/**
 * Removes empty operands when the surrounding expression remains a String concatenation.
 */
public final class RemoveEmptyStringConcatenationTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-empty-string-concatenation";
	}

	@Override
	public String description() {
		return "Remove unnecessary empty strings from concatenations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(binary -> candidate(context, binary))
			.flatMap(Optional::stream)
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && candidate.expression().isAncestorOf(other.expression())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Remove empty string concatenation operand"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(),
							context.editor().text(candidate.replacement()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr binary) {
		if (binary.getOperator() != BinaryExpr.Operator.PLUS || AstSupport.hasComment(context, binary)) {
			return Optional.empty();
		}
		Expression replacement;
		if (empty(binary.getLeft())) {
			replacement = binary.getRight();
		}
		else if (empty(binary.getRight())) {
			replacement = binary.getLeft();
		}
		else {
			return Optional.empty();
		}
		if (!stringExpression(context, replacement, binary) && !parentProvidesStringContext(context, binary)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(binary, replacement));
	}

	private static boolean parentProvidesStringContext(InspectionContext context, BinaryExpr binary) {
		if (!(binary.getParentNode().orElse(null) instanceof BinaryExpr parent)
				|| parent.getOperator() != BinaryExpr.Operator.PLUS) {
			return false;
		}
		Expression other = parent.getLeft() == binary ? parent.getRight()
				: parent.getRight() == binary ? parent.getLeft() : null;
		return other != null && stringExpression(context, other, parent);
	}

	private static boolean stringExpression(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof StringLiteralExpr) {
			return true;
		}
		if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
			return stringExpression(context, binary.getLeft(), use)
					|| stringExpression(context, binary.getRight(), use);
		}
		String type = ExpressionToolSupport.visibleSimpleType(context, expression, use).orElse("");
		return ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.lang", Set.of("String"));
	}

	private static boolean empty(Expression expression) {
		return expression instanceof StringLiteralExpr literal && literal.asString().isEmpty();
	}

	private record Candidate(BinaryExpr expression, Expression replacement) {
	}

}
