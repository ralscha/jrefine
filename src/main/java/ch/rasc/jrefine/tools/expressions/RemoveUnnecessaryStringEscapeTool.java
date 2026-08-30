package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Removes apostrophe escapes that are unnecessary inside ordinary Java string literals.
 */
public final class RemoveUnnecessaryStringEscapeTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-string-escape";
	}

	@Override
	public String description() {
		return "Remove unnecessary character escapes in String literals";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(StringLiteralExpr.class)
			.stream()
			.map(literal -> candidate(context, literal))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.literal(), "Remove unnecessarily escaped character"));
			if (applyFixes) {
				context.editor().replace(candidate.literal().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, StringLiteralExpr literal) {
		String source = context.editor().text(literal);
		StringBuilder replacement = new StringBuilder(source.length());
		boolean changed = false;
		for (int index = 0; index < source.length(); index++) {
			char character = source.charAt(index);
			if (character == '\\' && index + 1 < source.length()) {
				char escaped = source.charAt(index + 1);
				if (escaped == '\'') {
					replacement.append('\'');
					index++;
					changed = true;
					continue;
				}
				replacement.append(character).append(escaped);
				index++;
				continue;
			}
			replacement.append(character);
		}
		return changed ? Optional.of(new Candidate(literal, replacement.toString())) : Optional.empty();
	}

	private record Candidate(StringLiteralExpr literal, String replacement) {
	}

}
