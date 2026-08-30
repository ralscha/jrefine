package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Removes value-mapping stages immediately before Stream.count(). */
public final class RemoveMappingBeforeCountTool implements InspectionTool {

	private static final Set<String> STREAM_TYPES = Set.of("Stream", "IntStream", "LongStream", "DoubleStream");

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "Queue", "Deque",
			"ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque", "Vector");

	@Override
	public String id() {
		return "remove-mapping-before-count";
	}

	@Override
	public String description() {
		return "Remove mapping calls immediately before Stream.count()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr stage : candidates) {
			findings.add(Finding.at(stage, "Remove mapping call before count()"));
			if (applyFixes) {
				context.editor()
					.replace(stage.getRange().orElseThrow(), context.editor().text(stage.getScope().orElseThrow()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<MethodCallExpr> candidate(InspectionContext context, MethodCallExpr count) {
		if (!"count".equals(count.getNameAsString()) || !count.getArguments().isEmpty()
				|| !(count.getScope().orElse(null) instanceof MethodCallExpr stage) || stage.getScope().isEmpty()
				|| AstSupport.hasComment(context, stage) || !mappingStage(stage)
				|| !knownPipeline(context, stage.getScope().orElseThrow(), count)) {
			return Optional.empty();
		}
		return Optional.of(stage);
	}

	private static boolean mappingStage(MethodCallExpr stage) {
		return switch (stage.getNameAsString()) {
			case "map", "mapToInt", "mapToLong", "mapToDouble", "mapToObj" -> stage.getArguments().size() == 1;
			case "boxed", "asLongStream", "asDoubleStream" -> stage.getArguments().isEmpty();
			default -> false;
		};
	}

	private static boolean knownPipeline(InspectionContext context, Expression expression, MethodCallExpr use) {
		String type = TypeLookup.visibleType(context.compilationUnit(), expression, use).orElse("");
		if (TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, STREAM_TYPES)) {
			return true;
		}
		if (!(expression instanceof MethodCallExpr call) || call.getScope().isEmpty()) {
			return false;
		}
		if (Set.of("stream", "parallelStream").contains(call.getNameAsString()) && call.getArguments().isEmpty()) {
			String ownerType = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), use)
				.orElse("");
			if (TypeLookup.isKnownJavaUtilType(context.compilationUnit(), ownerType, COLLECTION_TYPES)) {
				return true;
			}
		}
		return knownPipeline(context, call.getScope().orElseThrow(), use);
	}

}
