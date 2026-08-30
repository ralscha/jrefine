package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
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
import java.util.Optional;
import java.util.Set;

/** Replaces String index searches used as presence tests with contains(). */
public final class UseStringContainsTool implements InspectionTool {

	@Override
	public String id() {
		return "use-string-contains";
	}

	@Override
	public String description() {
		return "Replace String.indexOf() presence tests with contains()";
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
			findings.add(Finding.at(candidate.binary(), "Replace String.indexOf() presence test with contains()"));
			if (applyFixes) {
				MethodCallExpr call = candidate.call();
				String contains = context.editor().text(call.getScope().orElseThrow()) + ".contains("
						+ context.editor().text(call.getArgument(0)) + ")";
				context.editor()
					.replace(candidate.binary().getRange().orElseThrow(),
							candidate.negated() ? "!" + contains : contains);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr binary) {
		if (AstSupport.hasComment(context, binary)) {
			return Optional.empty();
		}
		Optional<MethodCallExpr> direct = indexCall(binary.getLeft());
		Optional<Integer> constant = constantValue(binary.getRight());
		boolean reversed = false;
		if (direct.isEmpty() || constant.isEmpty()) {
			direct = indexCall(binary.getRight());
			constant = constantValue(binary.getLeft());
			reversed = true;
		}
		if (direct.isEmpty() || constant.isEmpty()) {
			return Optional.empty();
		}
		MethodCallExpr call = direct.orElseThrow();
		if (!stringReceiver(context, call) || !stringArgument(context, call.getArgument(0), call)) {
			return Optional.empty();
		}
		Boolean negated = comparison(binary.getOperator(), constant.orElseThrow(), reversed);
		return negated == null ? Optional.empty() : Optional.of(new Candidate(binary, call, negated));
	}

	private static Boolean comparison(BinaryExpr.Operator operator, int constant, boolean reversed) {
		if (constant == -1) {
			return switch (operator) {
				case EQUALS -> true;
				case NOT_EQUALS -> false;
				case GREATER -> reversed ? null : false;
				case LESS -> reversed ? false : null;
				case LESS_EQUALS -> reversed ? null : true;
				case GREATER_EQUALS -> reversed ? true : null;
				default -> null;
			};
		}
		if (constant == 0) {
			return switch (operator) {
				case GREATER_EQUALS -> reversed ? null : false;
				case LESS_EQUALS -> reversed ? false : null;
				case LESS -> reversed ? null : true;
				case GREATER -> reversed ? true : null;
				default -> null;
			};
		}
		return null;
	}

	private static Optional<MethodCallExpr> indexCall(Expression expression) {
		if (!(expression instanceof MethodCallExpr call) || !"indexOf".equals(call.getNameAsString())
				|| call.getScope().isEmpty() || call.getArguments().size() != 1) {
			return Optional.empty();
		}
		return Optional.of(call);
	}

	private static boolean stringReceiver(InspectionContext context, MethodCallExpr call) {
		Expression scope = call.getScope().orElseThrow();
		if (scope instanceof StringLiteralExpr || scope instanceof TextBlockLiteralExpr) {
			return true;
		}
		return TypeLookup.visibleType(context.compilationUnit(), scope, call)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
			.isPresent();
	}

	private static boolean stringArgument(InspectionContext context, Expression expression, MethodCallExpr use) {
		if (expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr) {
			return true;
		}
		if (expression instanceof CharLiteralExpr || expression instanceof IntegerLiteralExpr) {
			return false;
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
			.isPresent();
	}

	private static Optional<Integer> constantValue(Expression expression) {
		if (expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0) {
			return Optional.of(0);
		}
		if (expression.isUnaryExpr()
				&& expression.asUnaryExpr().getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS
				&& expression.asUnaryExpr().getExpression() instanceof IntegerLiteralExpr literal
				&& literal.asNumber().intValue() == 1) {
			return Optional.of(-1);
		}
		return Optional.empty();
	}

	private record Candidate(BinaryExpr binary, MethodCallExpr call, boolean negated) {
	}

}
