package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Flattens Comparator.comparing calls nested inside thenComparing. */
public final class SimplifyComparatorMethodTool implements InspectionTool {

	private static final Map<String, String> METHODS = Map.of("comparing", "thenComparing", "comparingInt",
			"thenComparingInt", "comparingLong", "thenComparingLong", "comparingDouble", "thenComparingDouble");

	@Override
	public String id() {
		return "simplify-comparator-method";
	}

	@Override
	public String description() {
		return "Simplify redundant Comparator combinator calls";
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
			findings.add(Finding.at(candidate.outer(), "Flatten redundant Comparator.comparing() call"));
			if (applyFixes) {
				String arguments = candidate.inner()
					.getArguments()
					.stream()
					.map(context.editor()::text)
					.reduce((left, right) -> left + ", " + right)
					.orElse("");
				context.editor()
					.replace(candidate.outer().getRange().orElseThrow(),
							context.editor().text(candidate.outer().getScope().orElseThrow()) + "." + candidate.method()
									+ "(" + arguments + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr outer) {
		if (!"thenComparing".equals(outer.getNameAsString()) || outer.getScope().isEmpty()
				|| outer.getArguments().size() != 1 || !(outer.getArgument(0) instanceof MethodCallExpr inner)
				|| inner.getScope().isEmpty() || AstSupport.hasComment(context, outer)) {
			return Optional.empty();
		}
		String method = METHODS.get(inner.getNameAsString());
		if (method == null || inner.getArguments().isEmpty()
				|| inner.getScope().orElseThrow() instanceof NameExpr name
						&& TypeLookup.visibleType(context.compilationUnit(), name, inner).isPresent()
				|| !ExpressionToolSupport.knownType(context.compilationUnit(),
						inner.getScope().orElseThrow().toString(), "java.util", Set.of("Comparator"))) {
			return Optional.empty();
		}
		String receiverType = ExpressionToolSupport.visibleSimpleType(context, outer.getScope().orElseThrow(), outer)
			.orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), receiverType, "java.util",
				Set.of("Comparator"))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(outer, inner, method));
	}

	private record Candidate(MethodCallExpr outer, MethodCallExpr inner, String method) {
	}

}
