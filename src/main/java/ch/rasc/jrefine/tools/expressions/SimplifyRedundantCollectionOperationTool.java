package ch.rasc.jrefine.tools.expressions;

import java.util.List;
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

/** Simplifies collection operations that unnecessarily wrap a single value. */
public final class SimplifyRedundantCollectionOperationTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "Queue", "Deque",
			"ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet");

	@Override
	public String id() {
		return "simplify-redundant-collection-operation";
	}

	@Override
	public String description() {
		return "Simplify unnecessarily complex Collection operations";
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
			findings.add(Finding.at(candidate.outer(), "Replace containsAll(singletonList(...)) with contains(...)"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.outer().getRange().orElseThrow(), context.editor().text(candidate.scope())
							+ ".contains(" + context.editor().text(candidate.value()) + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (!"containsAll".equals(call.getNameAsString()) || call.getScope().isEmpty()
				|| call.getArguments().size() != 1 || AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		String receiverType = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call)
			.orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), receiverType, "java.util", COLLECTION_TYPES)) {
			return Optional.empty();
		}
		if (!(call.getArgument(0) instanceof MethodCallExpr singleton)
				|| !"singletonList".equals(singleton.getNameAsString()) || singleton.getArguments().size() != 1
				|| singleton.getScope().isEmpty() || !ExpressionToolSupport.knownType(context.compilationUnit(),
						singleton.getScope().orElseThrow().toString(), "java.util", Set.of("Collections"))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, call.getScope().orElseThrow(), singleton.getArgument(0)));
	}

	private record Candidate(MethodCallExpr outer, Expression scope, Expression value) {
	}

}
