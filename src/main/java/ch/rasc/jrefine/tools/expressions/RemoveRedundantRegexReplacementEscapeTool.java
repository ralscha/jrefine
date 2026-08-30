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

/** Removes replacement-string escapes that do not protect '$' or '\\'. */
public final class RemoveRedundantRegexReplacementEscapeTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-regex-replacement-escape";
	}

	@Override
	public String description() {
		return "Remove unnecessary escapes in regex replacement strings";
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
			findings.add(Finding.at(candidate.literal(), "Remove redundant regex replacement escape"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.literal().getRange().orElseThrow(),
							new StringLiteralExpr(candidate.replacement()).toString());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (!"replaceAll".equals(call.getNameAsString()) && !"replaceFirst".equals(call.getNameAsString())
				|| call.getArguments().size() != 2 || call.getScope().isEmpty()
				|| !(call.getArgument(1) instanceof StringLiteralExpr literal)
				|| AstSupport.hasComment(context, literal)) {
			return Optional.empty();
		}
		String receiverType = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call)
			.orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), receiverType, "java.lang", Set.of("String"))) {
			return Optional.empty();
		}
		String simplified = simplify(literal.asString());
		return simplified.equals(literal.asString()) ? Optional.empty()
				: Optional.of(new Candidate(literal, simplified));
	}

	private static String simplify(String value) {
		StringBuilder result = new StringBuilder(value.length());
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '\\' && index + 1 < value.length()) {
				char next = value.charAt(index + 1);
				if (next != '$' && next != '\\') {
					result.append(next);
					index++;
					continue;
				}
			}
			result.append(character);
		}
		return result.toString();
	}

	private record Candidate(StringLiteralExpr literal, String replacement) {
	}

}
