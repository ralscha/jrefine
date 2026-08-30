package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import java.util.stream.Stream;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.Node;

/** Removes redundant String construction and no-op String methods. */
public final class SimplifyRedundantStringOperationTool implements InspectionTool {

	private static final Set<String> IDENTITY_INSENSITIVE_METHODS = Set.of("charAt", "chars", "codePointAt",
			"codePointBefore", "codePointCount", "codePoints", "compareTo", "compareToIgnoreCase", "contains",
			"contentEquals", "endsWith", "equals", "equalsIgnoreCase", "getBytes", "hashCode", "indexOf", "isBlank",
			"isEmpty", "lastIndexOf", "length", "lines", "matches", "regionMatches", "startsWith", "toCharArray");

	@Override
	public String id() {
		return "simplify-redundant-string-operation";
	}

	@Override
	public String description() {
		return "Remove redundant String constructors and no-op String calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Stream<Candidate> calls = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> callCandidate(context, call))
			.flatMap(Optional::stream);
		Stream<Candidate> creations = context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.map(creation -> creationCandidate(context, creation))
			.flatMap(Optional::stream);
		List<Candidate> all = java.util.stream.Stream.concat(calls, creations).toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && candidate.expression().isAncestorOf(other.expression())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Remove redundant String operation"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(),
							context.editor().text(candidate.replacement()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> callCandidate(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty() || AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Expression scope = call.getScope().orElseThrow();
		if (!definitelyNonNullString(context, scope, call)) {
			return Optional.empty();
		}
		if ("toString".equals(call.getNameAsString()) && call.getArguments().isEmpty()) {
			return Optional.of(new Candidate(call, scope));
		}
		if ("substring".equals(call.getNameAsString()) && call.getArguments().size() == 1
				&& call.getArgument(0) instanceof IntegerLiteralExpr literal && literal.asNumber().intValue() == 0) {
			return Optional.of(new Candidate(call, scope));
		}
		return Optional.empty();
	}

	private static Optional<Candidate> creationCandidate(InspectionContext context, ObjectCreationExpr creation) {
		if (creation.getArguments().size() != 1 || creation.getAnonymousClassBody().isPresent()
				|| AstSupport.hasComment(context, creation)
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), creation.getType().asString(),
						"java.lang", Set.of("String"))
				|| !isString(context, creation.getArgument(0), creation) || !identityIsUnobservable(creation)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(creation, creation.getArgument(0)));
	}

	private static boolean identityIsUnobservable(ObjectCreationExpr creation) {
		return creation.getParentNode()
			.filter(parent -> parent instanceof MethodCallExpr call && call.getScope().orElse(null) == creation
					&& IDENTITY_INSENSITIVE_METHODS.contains(call.getNameAsString()))
			.isPresent();
	}

	private static boolean definitelyNonNullString(InspectionContext context, Expression expression, Node use) {
		return expression instanceof StringLiteralExpr
				|| expression instanceof ObjectCreationExpr && isString(context, expression, use);
	}

	private static boolean isString(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof StringLiteralExpr) {
			return true;
		}
		return ExpressionToolSupport.visibleSimpleType(context, expression, use)
			.filter(type -> ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.lang",
					Set.of("String")))
			.isPresent();
	}

	private record Candidate(Node expression, Expression replacement) {
	}

}
