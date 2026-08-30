package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.Expression;

/**
 * Simplifies grouping collectors that select one min/max value per key into toMap
 * collectors.
 */
public final class SimplifyCollectorTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-collector";
	}

	@Override
	public String description() {
		return "Simplify cascaded grouping collectors";
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
		String function = candidates.isEmpty() ? "Function"
				: ImportSupport.useType(context, "java.util.function.Function", applyFixes);
		String binaryOperator = candidates.isEmpty() ? "BinaryOperator"
				: ImportSupport.useType(context, "java.util.function.BinaryOperator", applyFixes);
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.outer(), "Replace grouping collector with toMap collector"));
			if (applyFixes) {
				String owner = context.editor().text(candidate.outer().getScope().orElseThrow());
				String method = candidate.concurrent() ? "toConcurrentMap" : "toMap";
				context.editor()
					.replace(candidate.outer().getRange().orElseThrow(),
							owner + "." + method + "(" + context.editor().text(candidate.keyMapper()) + ", " + function
									+ ".identity(), " + binaryOperator + "." + candidate.selector() + "("
									+ context.editor().text(candidate.comparator()) + "))");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr outer) {
		boolean concurrent = "groupingByConcurrent".equals(outer.getNameAsString());
		if (!"groupingBy".equals(outer.getNameAsString()) && !concurrent || outer.getScope().isEmpty()
				|| outer.getArguments().size() != 2 || !collectors(context, outer.getScope().orElseThrow().toString())
				|| !(outer.getArgument(1) instanceof MethodCallExpr finishing)
				|| !"collectingAndThen".equals(finishing.getNameAsString()) || finishing.getScope().isEmpty()
				|| finishing.getArguments().size() != 2
				|| !collectors(context, finishing.getScope().orElseThrow().toString())
				|| !(finishing.getArgument(0) instanceof MethodCallExpr selecting)
				|| !"maxBy".equals(selecting.getNameAsString()) && !"minBy".equals(selecting.getNameAsString())
				|| selecting.getScope().isEmpty() || selecting.getArguments().size() != 1
				|| !collectors(context, selecting.getScope().orElseThrow().toString())
				|| !(finishing.getArgument(1) instanceof MethodReferenceExpr finisher)
				|| !"get".equals(finisher.getIdentifier())
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), finisher.getScope().toString(),
						"java.util", Set.of("Optional"))
				|| AstSupport.hasComment(context, outer)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(outer, outer.getArgument(0), selecting.getArgument(0),
				selecting.getNameAsString(), concurrent));
	}

	private static boolean collectors(InspectionContext context, String spelling) {
		return ExpressionToolSupport.knownType(context.compilationUnit(), spelling, "java.util.stream",
				Set.of("Collectors"));
	}

	private record Candidate(MethodCallExpr outer, Expression keyMapper, Expression comparator, String selector,
			boolean concurrent) {
	}

}
