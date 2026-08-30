package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.EnclosedExpr;

/** Rewrites canonical Stream terminal chains to their direct equivalents. */
public final class SimplifyStreamCallChainTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "Queue", "Deque",
			"ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque", "Vector");

	@Override
	public String id() {
		return "simplify-stream-call-chain";
	}

	@Override
	public String description() {
		return "Simplify Stream API call chains";
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
				.noneMatch(other -> other != candidate && other.call().isAncestorOf(candidate.call())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.call(), "Simplify Stream API call chain"));
			if (applyFixes) {
				context.editor().replace(candidate.call().getRange().orElseThrow(), candidate.replacement(context));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Optional<Candidate> collect = collectedTerminal(context, call);
		if (collect.isPresent()) {
			return collect;
		}
		Optional<Candidate> anyMatch = filterFindFirst(call);
		if (anyMatch.isPresent() && knownStream(context, anyMatch.orElseThrow().base(), call)) {
			return anyMatch;
		}
		return directCollectionArray(context, call);
	}

	private static Optional<Candidate> collectedTerminal(InspectionContext context, MethodCallExpr call) {
		if (!"collect".equals(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().size() != 1
				|| !knownStream(context, call.getScope().orElseThrow(), call)
				|| !(call.getArgument(0) instanceof MethodCallExpr collector) || collector.getScope().isEmpty()
				|| !ExpressionToolSupport.knownType(context.compilationUnit(),
						collector.getScope().orElseThrow().toString(), "java.util.stream", Set.of("Collectors"))) {
			return Optional.empty();
		}
		if ("counting".equals(collector.getNameAsString()) && collector.getArguments().isEmpty()) {
			return expectsPrimitive(context, call, "long")
					? Optional.of(new Candidate(call, call.getScope().orElseThrow(), null, ".count()"))
					: Optional.empty();
		}
		String terminal = switch (collector.getNameAsString()) {
			case "summingInt" -> ".mapToInt(";
			case "summingLong" -> ".mapToLong(";
			case "summingDouble" -> ".mapToDouble(";
			default -> null;
		};
		String primitive = switch (collector.getNameAsString()) {
			case "summingInt" -> "int";
			case "summingLong" -> "long";
			case "summingDouble" -> "double";
			default -> null;
		};
		return terminal != null && collector.getArguments().size() == 1 && expectsPrimitive(context, call, primitive)
				? Optional.of(new Candidate(call, call.getScope().orElseThrow(), collector.getArgument(0), terminal))
				: Optional.empty();
	}

	private static Optional<Candidate> filterFindFirst(MethodCallExpr call) {
		if (!"isPresent".equals(call.getNameAsString()) || !call.getArguments().isEmpty()
				|| !(call.getScope().orElse(null) instanceof MethodCallExpr find)
				|| !"findFirst".equals(find.getNameAsString()) || !find.getArguments().isEmpty()
				|| !(find.getScope().orElse(null) instanceof MethodCallExpr filter)
				|| !"filter".equals(filter.getNameAsString()) || filter.getArguments().size() != 1
				|| filter.getScope().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, filter.getScope().orElseThrow(), filter.getArgument(0), ".anyMatch("));
	}

	private static Optional<Candidate> directCollectionArray(InspectionContext context, MethodCallExpr call) {
		if (!"toArray".equals(call.getNameAsString()) || !call.getArguments().isEmpty()
				|| !(call.getScope().orElse(null) instanceof MethodCallExpr stream)
				|| !Set.of("stream", "parallelStream").contains(stream.getNameAsString())
				|| !stream.getArguments().isEmpty() || stream.getScope().isEmpty()) {
			return Optional.empty();
		}
		String type = ExpressionToolSupport.visibleSimpleType(context, stream.getScope().orElseThrow(), call)
			.orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.util", COLLECTION_TYPES)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, stream.getScope().orElseThrow(), null, ".toArray()"));
	}

	private static boolean knownStream(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof MethodCallExpr call && call.getScope().isPresent()) {
			if (Set.of("stream", "parallelStream").contains(call.getNameAsString())) {
				String type = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), use)
					.orElse("");
				if (ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.util", COLLECTION_TYPES)) {
					return true;
				}
			}
			if (knownStream(context, call.getScope().orElseThrow(), use)) {
				return true;
			}
		}
		String type = ExpressionToolSupport.visibleSimpleType(context, expression, use).orElse("");
		return ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.util.stream", Set.of("Stream"));
	}

	private static boolean expectsPrimitive(InspectionContext context, MethodCallExpr call, String primitive) {
		Node current = call;
		while (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed
				&& enclosed.getInner() == current) {
			current = enclosed;
		}
		Node parent = current.getParentNode().orElse(null);
		if (parent instanceof ReturnStmt returned && returned.getExpression().orElse(null) == current) {
			return AstSupport.ancestor(returned, MethodDeclaration.class)
				.filter(method -> method.getType().isPrimitiveType())
				.map(method -> method.getType().asString().equals(primitive))
				.orElse(false);
		}
		if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == current) {
			return variable.getType().isPrimitiveType() && variable.getType().asString().equals(primitive);
		}
		if (parent instanceof AssignExpr assignment && assignment.getValue() == current) {
			return TypeLookup.visibleType(context.compilationUnit(), assignment.getTarget(), assignment)
				.filter(primitive::equals)
				.isPresent();
		}
		return false;
	}

	private record Candidate(MethodCallExpr call, Expression base, Expression argument, String operation) {
		private String replacement(InspectionContext context) {
			String source = context.editor().text(base);
			if (argument == null) {
				return source + operation;
			}
			if (operation.startsWith(".mapTo")) {
				return source + operation + context.editor().text(argument) + ").sum()";
			}
			return source + operation + context.editor().text(argument) + ")";
		}
	}

}
