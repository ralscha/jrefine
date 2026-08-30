package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Removes integral wrapper compare calls used only to compare their result with zero. */
public final class RemoveRedundantCompareCallTool implements InspectionTool {

	private static final Set<String> OWNERS = Set.of("Byte", "Short", "Character", "Integer", "Long");

	@Override
	public String id() {
		return "remove-redundant-compare-call";
	}

	@Override
	public String description() {
		return "Replace superfluous compare() calls with direct comparisons";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(binary -> candidate(context, binary))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.binary(), "Remove redundant compare() call"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.binary().getRange().orElseThrow(), context.editor().text(candidate.left()) + " "
							+ candidate.operator().asString() + " " + context.editor().text(candidate.right()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr binary) {
		if (AstSupport.hasComment(context, binary)) {
			return Optional.empty();
		}
		MethodCallExpr call;
		BinaryExpr.Operator operator = binary.getOperator();
		if (binary.getLeft() instanceof MethodCallExpr method && zero(binary.getRight())) {
			call = method;
		}
		else if (zero(binary.getLeft()) && binary.getRight() instanceof MethodCallExpr method) {
			call = method;
			operator = reverse(operator);
		}
		else {
			return Optional.empty();
		}
		if (!Set
			.of(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.LESS,
					BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS)
			.contains(operator) || !"compare".equals(call.getNameAsString()) || call.getArguments().size() != 2
				|| call.getScope().isEmpty() || !ExpressionToolSupport.knownType(context.compilationUnit(),
						call.getScope().orElseThrow().toString(), "java.lang", OWNERS)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(binary, call.getArgument(0), call.getArgument(1), operator));
	}

	private static boolean zero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private static BinaryExpr.Operator reverse(BinaryExpr.Operator operator) {
		return switch (operator) {
			case LESS -> BinaryExpr.Operator.GREATER;
			case LESS_EQUALS -> BinaryExpr.Operator.GREATER_EQUALS;
			case GREATER -> BinaryExpr.Operator.LESS;
			case GREATER_EQUALS -> BinaryExpr.Operator.LESS_EQUALS;
			default -> operator;
		};
	}

	private record Candidate(BinaryExpr binary, Expression left, Expression right, BinaryExpr.Operator operator) {
	}

}
