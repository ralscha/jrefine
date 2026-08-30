package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import com.github.javaparser.ast.expr.LiteralExpr;

/** Replaces explicit null-safe equality idioms with Objects.equals(). */
public final class UseObjectsEqualsTool implements InspectionTool {

	@Override
	public String id() {
		return "use-objects-equals";
	}

	@Override
	public String description() {
		return "Replace null-safe equals expressions with Objects.equals()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(ConditionalExpr.class)
			.stream()
			.map(expression -> conditional(context, expression))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		String objects = candidates.isEmpty() ? "Objects"
				: ImportSupport.useType(context, "java.util.Objects", applyFixes);
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Replace equality expression with Objects.equals()"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(),
							objects + ".equals(" + context.editor().text(candidate.left()) + ", "
									+ context.editor().text(candidate.right()) + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> conditional(InspectionContext context, ConditionalExpr expression) {
		if (AstSupport.hasComment(context, expression)) {
			return Optional.empty();
		}
		NameExpr checked = nullComparedName(expression.getCondition()).orElse(null);
		if (checked == null) {
			return Optional.empty();
		}
		boolean nullWhenTrue = expression.getCondition().asBinaryExpr().getOperator() == BinaryExpr.Operator.EQUALS;
		Expression nullBranch = nullWhenTrue ? expression.getThenExpr() : expression.getElseExpr();
		Expression equalsBranch = nullWhenTrue ? expression.getElseExpr() : expression.getThenExpr();
		EqualsCall call = equalsCall(equalsBranch).orElse(null);
		if (call == null || !call.left().equals(checked) || !nullEquality(nullBranch, call.right())
				|| !stable(context, checked, expression) || !stable(context, call.right(), expression)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(expression, checked, call.right()));
	}

	private static Optional<NameExpr> nullComparedName(Expression expression) {
		if (!(expression instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.EQUALS
				&& binary.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
			return Optional.empty();
		}
		return nameBesideNull(binary);
	}

	private static Optional<NameExpr> nameBesideNull(BinaryExpr binary) {
		if (binary.getLeft() instanceof NameExpr name && binary.getRight() instanceof NullLiteralExpr) {
			return Optional.of(name);
		}
		if (binary.getRight() instanceof NameExpr name && binary.getLeft() instanceof NullLiteralExpr) {
			return Optional.of(name);
		}
		return Optional.empty();
	}

	private static Optional<EqualsCall> equalsCall(Expression expression) {
		if (!(expression instanceof MethodCallExpr call) || !"equals".equals(call.getNameAsString())
				|| call.getArguments().size() != 1 || call.getScope().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new EqualsCall(call.getScope().orElseThrow(), call.getArgument(0)));
	}

	private static boolean nullEquality(Expression expression, Expression value) {
		return expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.EQUALS
				&& (binary.getLeft().equals(value) && binary.getRight() instanceof NullLiteralExpr
						|| binary.getRight().equals(value) && binary.getLeft() instanceof NullLiteralExpr);
	}

	private static boolean stable(InspectionContext context, Expression expression, Node use) {
		return expression instanceof NullLiteralExpr || expression instanceof LiteralExpr
				|| expression instanceof NameExpr name
						&& TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), name.getNameAsString(), use);
	}

	private record EqualsCall(Expression left, Expression right) {
	}

	private record Candidate(Expression expression, Expression left, Expression right) {
	}

}
