package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Removes Collections.unmodifiableX wrappers around known immutable JDK collections. */
public final class RemoveRedundantUnmodifiableWrapperTool implements InspectionTool {

	private static final Map<String, String> WRAPPERS = Map.of("unmodifiableList", "List", "unmodifiableSet", "Set",
			"unmodifiableMap", "Map");

	@Override
	public String id() {
		return "remove-redundant-unmodifiable-wrapper";
	}

	@Override
	public String description() {
		return "Remove unmodifiable wrappers around immutable collections";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && other.outer().isAncestorOf(candidate.outer())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.outer(), "Remove redundant unmodifiable collection wrapper"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.outer().getRange().orElseThrow(), context.editor().text(candidate.inner()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr outer) {
		if (outer.getArguments().size() != 1 || outer.getScope().isEmpty() || AstSupport.hasComment(context, outer)
				|| !ExpressionToolSupport.knownType(context.compilationUnit(),
						outer.getScope().orElseThrow().toString(), "java.util", Set.of("Collections"))) {
			return Optional.empty();
		}
		String kind = WRAPPERS.get(outer.getNameAsString());
		if (kind == null || !(outer.getArgument(0) instanceof MethodCallExpr inner) || inner.getScope().isEmpty()) {
			return Optional.empty();
		}
		if (ExpressionToolSupport.knownType(context.compilationUnit(), inner.getScope().orElseThrow().toString(),
				"java.util", Set.of("Collections"))) {
			Set<String> allowed = switch (kind) {
				case "List" -> Set.of("emptyList", "singletonList", "unmodifiableList");
				case "Set" -> Set.of("emptySet", "singleton", "unmodifiableSet");
				case "Map" -> Set.of("emptyMap", "singletonMap", "unmodifiableMap");
				default -> Set.<String>of();
			};
			if (allowed.contains(inner.getNameAsString())) {
				return Optional.of(new Candidate(outer, inner));
			}
		}
		if (ExpressionToolSupport.knownType(context.compilationUnit(), inner.getScope().orElseThrow().toString(),
				"java.util", Set.of(kind)) && Set.of("of", "copyOf").contains(inner.getNameAsString())) {
			return Optional.of(new Candidate(outer, inner));
		}
		return Optional.empty();
	}

	private record Candidate(MethodCallExpr outer, MethodCallExpr inner) {
	}

}
