package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
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

/** Uses Java 21 first/last operations on known sequenced collection implementations. */
public final class UseSequencedCollectionMethodsTool implements InspectionTool {

	private static final Set<String> TYPES = Set.of("List", "ArrayList", "LinkedList", "Vector", "Stack",
			"CopyOnWriteArrayList");

	@Override
	public String id() {
		return "use-sequenced-collection-methods";
	}

	@Override
	public int minimumJavaVersion() {
		return 21;
	}

	@Override
	public String description() {
		return "Use SequencedCollection first and last methods";
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
			findings.add(Finding.at(candidate.call(), "Use SequencedCollection method '" + candidate.method() + "()'"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.call().getRange().orElseThrow(),
							context.editor().text(candidate.scope()) + "." + candidate.method() + "("
									+ candidate.argument().map(context.editor()::text).orElse("") + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty() || AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Expression scope = call.getScope().orElseThrow();
		if (TypeLookup.visibleType(context.compilationUnit(), scope, call)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, TYPES))
			.isEmpty()) {
			return Optional.empty();
		}
		if (("get".equals(call.getNameAsString()) || "remove".equals(call.getNameAsString()))
				&& call.getArguments().size() == 1) {
			if (isZero(call.getArgument(0))) {
				return Optional.of(new Candidate(call, scope,
						"get".equals(call.getNameAsString()) ? "getFirst" : "removeFirst", Optional.empty()));
			}
			if (isLastIndex(call.getArgument(0), scope)) {
				return Optional.of(new Candidate(call, scope,
						"get".equals(call.getNameAsString()) ? "getLast" : "removeLast", Optional.empty()));
			}
		}
		if ("add".equals(call.getNameAsString()) && call.getArguments().size() == 2) {
			if (isZero(call.getArgument(0))) {
				return Optional.of(new Candidate(call, scope, "addFirst", Optional.of(call.getArgument(1))));
			}
			if (isSize(call.getArgument(0), scope)) {
				return Optional.of(new Candidate(call, scope, "addLast", Optional.of(call.getArgument(1))));
			}
		}
		return Optional.empty();
	}

	private static boolean isZero(Expression expression) {
		return expression instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0;
	}

	private static boolean isLastIndex(Expression expression, Expression scope) {
		return expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.MINUS
				&& isSize(binary.getLeft(), scope) && binary.getRight() instanceof IntegerLiteralExpr literal
				&& literal.asNumber().intValue() == 1;
	}

	private static boolean isSize(Expression expression, Expression scope) {
		return expression instanceof MethodCallExpr size && "size".equals(size.getNameAsString())
				&& size.getArguments().isEmpty() && size.getScope().filter(scope::equals).isPresent();
	}

	private record Candidate(MethodCallExpr call, Expression scope, String method, Optional<Expression> argument) {
	}

}
