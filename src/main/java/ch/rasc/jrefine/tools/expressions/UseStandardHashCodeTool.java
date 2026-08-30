package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Replaces manual long hash folding with Long.hashCode(). */
public final class UseStandardHashCodeTool implements InspectionTool {

	@Override
	public String id() {
		return "use-standard-hash-code";
	}

	@Override
	public String description() {
		return "Replace manual long hash folding with standard hashCode methods";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(CastExpr.class)
			.stream()
			.map(cast -> candidate(context, cast))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.cast(), "Use Long.hashCode()"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.cast().getRange().orElseThrow(),
							"Long.hashCode(" + context.editor().text(candidate.value()) + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, CastExpr cast) {
		if (!cast.getType().isPrimitiveType() || !"int".equals(cast.getType().asString())
				|| AstSupport.hasComment(context, cast)) {
			return Optional.empty();
		}
		Expression expression = unwrap(cast.getExpression());
		if (!(expression instanceof BinaryExpr xor) || xor.getOperator() != BinaryExpr.Operator.XOR) {
			return Optional.empty();
		}
		Expression left = unwrap(xor.getLeft());
		Expression right = unwrap(xor.getRight());
		if (!(right instanceof BinaryExpr shift) || shift.getOperator() != BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT
				|| !(unwrap(shift.getRight()) instanceof IntegerLiteralExpr amount)
				|| amount.asNumber().intValue() != 32 || !left.equals(unwrap(shift.getLeft()))
				|| NumericSupport.typeOf(context, left, cast).filter("long"::equals).isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(cast, left));
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression instanceof EnclosedExpr enclosed) {
			currentExpression = enclosed.getInner();
		}
		return currentExpression;
	}

	private record Candidate(CastExpr cast, Expression value) {
	}

}
