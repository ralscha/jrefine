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

/** Removes String.format() when a literal contains no formatting directive. */
public final class SimplifyStringFormatTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-string-format";
	}

	@Override
	public String description() {
		return "Remove redundant String.format() calls";
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
			findings.add(Finding.at(candidate.call(), "Remove redundant String.format() call"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.call().getRange().orElseThrow(),
							new StringLiteralExpr(candidate.value()).toString());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (!"format".equals(call.getNameAsString()) || call.getScope().isEmpty() || call.getArguments().size() != 1
				|| !(call.getArgument(0) instanceof StringLiteralExpr format) || AstSupport.hasComment(context, call)
				|| !ExpressionToolSupport.knownType(context.compilationUnit(), call.getScope().orElseThrow().toString(),
						"java.lang", Set.of("String"))) {
			return Optional.empty();
		}
		String simplified = simplify(format.asString());
		return simplified == null ? Optional.empty() : Optional.of(new Candidate(call, simplified));
	}

	private static String simplify(String format) {
		StringBuilder result = new StringBuilder(format.length());
		for (int index = 0; index < format.length(); index++) {
			char character = format.charAt(index);
			if (character != '%') {
				result.append(character);
				continue;
			}
			if (index + 1 >= format.length() || format.charAt(index + 1) != '%') {
				return null;
			}
			result.append('%');
			index++;
		}
		return result.toString();
	}

	private record Candidate(MethodCallExpr call, String value) {
	}

}
