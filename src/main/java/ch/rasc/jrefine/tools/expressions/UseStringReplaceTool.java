package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Replaces replaceAll() with replace() when both literals have no regex semantics. */
public final class UseStringReplaceTool implements InspectionTool {

	private static final String REGEX_META_CHARACTERS = "\\.^$|?*+()[]{}";

	@Override
	public String id() {
		return "use-string-replace";
	}

	@Override
	public String description() {
		return "Replace non-regex String.replaceAll() calls with replace()";
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
		for (MethodCallExpr call : candidates) {
			findings.add(Finding.at(call, "Replace non-regex replaceAll() with replace()"));
			if (applyFixes) {
				context.editor().replace(call.getName().getRange().orElseThrow(), "replace");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<MethodCallExpr> candidate(InspectionContext context, MethodCallExpr call) {
		if (!"replaceAll".equals(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().size() != 2
				|| !(call.getArgument(0) instanceof StringLiteralExpr pattern)
				|| !(call.getArgument(1) instanceof StringLiteralExpr replacement)
				|| AstSupport.hasComment(context, call) || containsAny(pattern.asString(), REGEX_META_CHARACTERS)
				|| containsAny(replacement.asString(), "\\$")) {
			return Optional.empty();
		}
		String receiverType = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call)
			.orElse("");
		return ExpressionToolSupport.knownType(context.compilationUnit(), receiverType, "java.lang", Set.of("String"))
				? Optional.of(call) : Optional.empty();
	}

	private static boolean containsAny(String value, String characters) {
		return value.chars().anyMatch(character -> characters.indexOf(character) >= 0);
	}

}
