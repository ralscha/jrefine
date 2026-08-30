package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.CharLiteralExpr;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Removes String replacement calls whose constant search cannot match the literal
 * receiver.
 */
public final class RemoveNoEffectStringReplacementTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-no-effect-string-replacement";
	}

	@Override
	public String description() {
		return "Remove String replacement operations that cannot match";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> all = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		List<MethodCallExpr> candidates = all.stream()
			.filter(call -> all.stream().noneMatch(other -> other != call && other.isAncestorOf(call)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : candidates) {
			findings.add(Finding.at(call, "Remove replacement operation that has no effect"));
			if (applyFixes) {
				context.editor()
					.replace(call.getRange().orElseThrow(), context.editor().text(call.getScope().orElseThrow()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<MethodCallExpr> candidate(InspectionContext context, MethodCallExpr call) {
		if (!Set.of("replace", "replaceAll", "replaceFirst").contains(call.getNameAsString())
				|| call.getScope().isEmpty() || call.getArguments().size() != 2
				|| !(call.getScope().orElseThrow() instanceof StringLiteralExpr receiver)
				|| AstSupport.hasComment(context, call) || !literalReplacement(call)) {
			return Optional.empty();
		}
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), "String", "java.lang", Set.of("String"))) {
			return Optional.empty();
		}
		boolean absent = "replace".equals(call.getNameAsString()) ? absentLiteral(receiver.asString(), call)
				: absentRegex(receiver.asString(), call);
		return absent ? Optional.of(call) : Optional.empty();
	}

	private static boolean literalReplacement(MethodCallExpr call) {
		if ("replace".equals(call.getNameAsString()) && call.getArgument(0) instanceof CharLiteralExpr) {
			return call.getArgument(1) instanceof CharLiteralExpr;
		}
		return call.getArgument(0) instanceof StringLiteralExpr && call.getArgument(1) instanceof StringLiteralExpr;
	}

	private static boolean absentLiteral(String receiver, MethodCallExpr call) {
		if (call.getArgument(0) instanceof CharLiteralExpr character) {
			return receiver.indexOf(character.asChar()) < 0;
		}
		return !receiver.contains(call.getArgument(0).asStringLiteralExpr().asString());
	}

	private static boolean absentRegex(String receiver, MethodCallExpr call) {
		try {
			return !Pattern.compile(call.getArgument(0).asStringLiteralExpr().asString()).matcher(receiver).find();
		}
		catch (PatternSyntaxException ignored) {
			return false;
		}
	}

}
