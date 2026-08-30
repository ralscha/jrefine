package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Removes wrapper valueOf() calls whose argument already has the same wrapper type. */
public final class RemoveBoxingOfBoxedValueTool implements InspectionTool {

	private static final Set<String> WRAPPERS = Set.of("Boolean", "Byte", "Character", "Short", "Integer", "Long",
			"Float", "Double");

	@Override
	public String id() {
		return "remove-boxing-of-boxed-value";
	}

	@Override
	public String description() {
		return "Remove boxing calls applied to already boxed values";
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
			findings.add(Finding.at(candidate.call(), "Remove boxing of already boxed value"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.call().getRange().orElseThrow(), context.editor().text(candidate.argument()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (!"valueOf".equals(call.getNameAsString()) || call.getArguments().size() != 1 || call.getScope().isEmpty()
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Optional<String> wrapper = wrapper(context, call.getScope().orElseThrow());
		if (wrapper.isEmpty()) {
			return Optional.empty();
		}
		Expression argument = call.getArgument(0);
		return boxedType(context, argument, call).filter(wrapper.orElseThrow()::equals).isPresent()
				? Optional.of(new Candidate(call, argument)) : Optional.empty();
	}

	private static Optional<String> wrapper(InspectionContext context, Expression scope) {
		String spelling = scope.toString();
		String simple = ExpressionToolSupport.simpleName(spelling);
		return WRAPPERS.contains(simple)
				&& TypeLookup.isKnownJavaLangType(context.compilationUnit(), spelling, Set.of(simple))
						? Optional.of(simple) : Optional.empty();
	}

	private static Optional<String> boxedType(InspectionContext context, Expression argument, MethodCallExpr use) {
		String spelling;
		if (argument instanceof NameExpr) {
			spelling = TypeLookup.visibleType(context.compilationUnit(), argument, use).orElse(null);
		}
		else if (argument instanceof CastExpr cast) {
			spelling = cast.getType().asString();
		}
		else if (argument instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isEmpty()) {
			spelling = creation.getType().asString();
		}
		else {
			spelling = null;
		}
		if (spelling == null) {
			return Optional.empty();
		}
		String simple = ExpressionToolSupport.simpleName(spelling);
		return WRAPPERS.contains(simple)
				&& TypeLookup.isKnownJavaLangType(context.compilationUnit(), spelling, Set.of(simple))
						? Optional.of(simple) : Optional.empty();
	}

	private record Candidate(MethodCallExpr call, Expression argument) {
	}

}
