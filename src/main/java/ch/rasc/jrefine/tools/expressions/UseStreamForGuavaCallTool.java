package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Replaces Guava Iterables and Collections2 pseudo-functional calls with streams. */
public final class UseStreamForGuavaCallTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "SortedSet", "NavigableSet",
			"Queue", "Deque", "ArrayList", "LinkedList", "Vector", "HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque",
			"PriorityQueue", "CopyOnWriteArrayList", "CopyOnWriteArraySet");

	@Override
	public String id() {
		return "use-stream-for-guava-call";
	}

	@Override
	public String description() {
		return "Replace Guava pseudo-functional collection calls with streams";
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
		String collectors = candidates.stream().anyMatch(candidate -> candidate.terminal() == Terminal.LIST)
				? ImportSupport.useType(context, "java.util.stream.Collectors", applyFixes) : "Collectors";
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.call(), "Replace Guava pseudo-functional call with Stream API"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.call().getRange().orElseThrow(), replacement(context, candidate, collectors));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (call.getArguments().size() != 2 || call.getScope().isEmpty() || AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		String owner = call.getScope().orElseThrow().toString();
		if (!knownGuavaOwner(context, owner, "Iterables") && !knownGuavaOwner(context, owner, "Collections2")) {
			return Optional.empty();
		}
		Expression source = call.getArgument(0);
		if (TypeLookup.visibleType(context.compilationUnit(), source, call)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, COLLECTION_TYPES))
			.isEmpty()) {
			return Optional.empty();
		}
		return switch (call.getNameAsString()) {
			case "transform" -> Optional.of(new Candidate(call, source, call.getArgument(1), "map", Terminal.LIST));
			case "filter" -> Optional.of(new Candidate(call, source, call.getArgument(1), "filter", Terminal.LIST));
			case "any" -> Optional.of(new Candidate(call, source, call.getArgument(1), "anyMatch", Terminal.MATCH));
			case "all" -> Optional.of(new Candidate(call, source, call.getArgument(1), "allMatch", Terminal.MATCH));
			default -> Optional.empty();
		};
	}

	private static String replacement(InspectionContext context, Candidate candidate, String collectors) {
		String source = context.editor().text(candidate.source());
		String function = context.editor().text(candidate.function());
		if (candidate.terminal() == Terminal.MATCH) {
			return source + ".stream()." + candidate.operation() + "(" + function + ")";
		}
		return source + ".stream()." + candidate.operation() + "(" + function + ")" + ".collect(" + collectors
				+ ".toList())";
	}

	private static boolean knownGuavaOwner(InspectionContext context, String spelling, String simpleName) {
		if (context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(simpleName))) {
			return false;
		}
		if (spelling.equals("com.google.common.collect." + simpleName)) {
			return true;
		}
		if (!spelling.equals(simpleName)) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && (imported.getNameAsString()
				.equals("com.google.common.collect." + simpleName)
					|| imported.isAsterisk() && "com.google.common.collect".equals(imported.getNameAsString())));
	}

	private enum Terminal {

		LIST, MATCH

	}

	private record Candidate(MethodCallExpr call, Expression source, Expression function, String operation,
			Terminal terminal) {
	}

}
