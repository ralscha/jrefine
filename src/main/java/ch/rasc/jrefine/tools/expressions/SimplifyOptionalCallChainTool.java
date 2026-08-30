package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.Expression;

/** Collapses a presence-only Optional map/orElse chain to isPresent(). */
public final class SimplifyOptionalCallChainTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-optional-call-chain";
	}

	@Override
	public String description() {
		return "Simplify canonical Optional call chains";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.outer(), "Replace presence-only Optional chain with isPresent()"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.outer().getRange().orElseThrow(),
							context.editor().text(candidate.optional()) + ".isPresent()");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr outer) {
		if (!"orElse".equals(outer.getNameAsString()) || outer.getScope().isEmpty() || outer.getArguments().size() != 1
				|| !(outer.getArgument(0) instanceof BooleanLiteralExpr fallback) || fallback.getValue()
				|| !(outer.getScope().orElseThrow() instanceof MethodCallExpr map)
				|| !"map".equals(map.getNameAsString()) || map.getScope().isEmpty() || map.getArguments().size() != 1
				|| !(map.getArgument(0) instanceof LambdaExpr lambda) || lambda.getParameters().size() != 1
				|| lambda.getExpressionBody().isEmpty()
				|| !(lambda.getExpressionBody().orElseThrow() instanceof BooleanLiteralExpr mapped)
				|| !mapped.getValue() || AstSupport.hasComment(context, outer)) {
			return Optional.empty();
		}
		Expression optional = map.getScope().orElseThrow();
		String type = ExpressionToolSupport.visibleSimpleType(context, optional, outer).orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.util", Set.of("Optional"))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(outer, optional));
	}

	private record Candidate(MethodCallExpr outer, Expression optional) {
	}

}
