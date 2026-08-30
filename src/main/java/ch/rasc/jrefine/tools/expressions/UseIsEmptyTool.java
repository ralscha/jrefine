package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.analysis.TypeLookup;

import java.util.ArrayList;
import java.util.Set;
import java.util.Optional;

/** Replaces zero size/length comparisons and empty-string equality with isEmpty(). */
public final class UseIsEmptyTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "SortedSet", "NavigableSet",
			"Queue", "Deque", "Map", "SortedMap", "NavigableMap", "ArrayList", "LinkedList", "Vector", "Stack",
			"HashSet", "LinkedHashSet", "TreeSet", "ArrayDeque", "PriorityQueue", "HashMap", "LinkedHashMap", "TreeMap",
			"WeakHashMap", "IdentityHashMap", "ConcurrentHashMap", "ConcurrentMap", "CopyOnWriteArrayList",
			"CopyOnWriteArraySet");

	@Override
	public String id() {
		return "use-is-empty";
	}

	@Override
	public String description() {
		return "Replace zero-size comparisons and String.equals(\"\") with isEmpty()";
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
			findings.add(Finding.at(candidate.binary(), "Replace zero-size comparison with isEmpty()"));
			if (applyFixes) {
				MethodCallExpr call = candidate.call();
				String replacement = (candidate.negated() ? "!" : "")
						+ context.editor().text(call.getScope().orElseThrow()) + ".isEmpty()";
				context.editor().replace(candidate.binary().getRange().orElseThrow(), replacement);
			}
		}
		List<MethodCallExpr> stringEqualsCalls = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> "equals".equals(call.getNameAsString()))
			.filter(call -> call.getScope().isPresent())
			.filter(call -> call.getArguments().size() == 1)
			.filter(call -> call.getArgument(0) instanceof StringLiteralExpr literal && literal.getValue().isEmpty())
			.filter(call -> knownJavaString(context, call.getScope().orElseThrow(), call))
			.filter(call -> !hasComment(context.editor().text(call)))
			.toList();
		for (MethodCallExpr call : stringEqualsCalls) {
			findings.add(Finding.at(call, "Replace empty-string equality with isEmpty()"));
			if (applyFixes) {
				String replacement = context.editor().text(call.getScope().orElseThrow()) + ".isEmpty()";
				context.editor().replace(call.getRange().orElseThrow(), replacement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr binary) {
		if (hasComment(context.editor().text(binary))) {
			return java.util.Optional.empty();
		}
		Operator operator = binary.getOperator();
		if (operator != BinaryExpr.Operator.EQUALS && operator != BinaryExpr.Operator.NOT_EQUALS) {
			return java.util.Optional.empty();
		}
		MethodCallExpr call;
		if (isZero(binary.getRight()) && binary.getLeft() instanceof MethodCallExpr left) {
			call = left;
		}
		else if (isZero(binary.getLeft()) && binary.getRight() instanceof MethodCallExpr right) {
			call = right;
		}
		else {
			return java.util.Optional.empty();
		}
		if (!call.getArguments().isEmpty() || call.getScope().isEmpty()) {
			return java.util.Optional.empty();
		}
		String method = call.getNameAsString();
		boolean knownType = switch (method) {
			case "size" -> knownCollection(context, call.getScope().orElseThrow(), call);
			case "length" -> knownString(context, call.getScope().orElseThrow(), call);
			default -> false;
		};
		if (!knownType) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new Candidate(binary, call, operator == BinaryExpr.Operator.NOT_EQUALS));
	}

	private static boolean isZero(Expression expression) {
		if (!(expression instanceof IntegerLiteralExpr literal)) {
			return false;
		}
		try {
			return literal.asNumber().longValue() == 0;
		}
		catch (RuntimeException ignored) {
			return false;
		}
	}

	private static boolean knownCollection(InspectionContext context, Expression scope, Node use) {
		if (scope instanceof ObjectCreationExpr creation) {
			return TypeLookup.isKnownJavaUtilType(context.compilationUnit(), creation.getType().asString(),
					COLLECTION_TYPES);
		}
		return declaredType(context, scope, use)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, COLLECTION_TYPES))
			.isPresent();
	}

	private static boolean knownString(InspectionContext context, Expression scope, Node use) {
		if (scope instanceof StringLiteralExpr) {
			return true;
		}
		return declaredType(context, scope, use)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type,
					Set.of("String", "CharSequence")))
			.isPresent();
	}

	private static boolean knownJavaString(InspectionContext context, Expression scope, Node use) {
		if (scope instanceof StringLiteralExpr) {
			return true;
		}
		return declaredType(context, scope, use)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
			.isPresent();
	}

	private static Optional<String> declaredType(InspectionContext context, Expression scope, Node use) {
		return TypeLookup.visibleType(context.compilationUnit(), scope, use);
	}

	private record Candidate(BinaryExpr binary, MethodCallExpr call, boolean negated) {
	}

	private static boolean hasComment(String source) {
		return source.contains("//") || source.contains("/*");
	}

}
