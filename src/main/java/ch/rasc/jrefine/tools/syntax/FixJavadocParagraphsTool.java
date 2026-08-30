package ch.rasc.jrefine.tools.syntax;

import java.util.List;
import java.util.regex.Matcher;
import com.github.javaparser.ast.comments.JavadocComment;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Marks prose paragraph breaks in Javadoc with HTML paragraph tags. */
public final class FixJavadocParagraphsTool implements InspectionTool {

	private static final Pattern BLANK = Pattern.compile("^(\\s*\\*)\\s*$");

	@Override
	public String id() {
		return "fix-javadoc-paragraphs";
	}

	@Override
	public String description() {
		return "Replace Javadoc prose blank lines with <p>";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.getAllComments()
			.stream()
			.filter(JavadocComment.class::isInstance)
			.map(JavadocComment.class::cast)
			.map(comment -> candidate(context, comment))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.comment(), "Replace blank Javadoc line with <p>"));
			if (applyFixes) {
				context.editor().replace(candidate.comment().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, JavadocComment comment) {
		String source = context.editor().text(comment);
		String separator = LineEndingSupport.detect(source);
		ArrayList<String> lines = new ArrayList<>(Arrays.asList(source.split("\\r?\\n", -1)));
		boolean changed = false;
		boolean inPreformatted = false;
		for (int index = 1; index + 1 < lines.size(); index++) {
			String trimmed = lines.get(index).trim().toLowerCase(java.util.Locale.ROOT);
			if (trimmed.contains("<pre") || trimmed.contains("<code")) {
				inPreformatted = true;
			}
			if (trimmed.contains("</pre") || trimmed.contains("</code")) {
				inPreformatted = false;
			}
			Matcher matcher = BLANK.matcher(lines.get(index));
			if (inPreformatted || !matcher.matches()) {
				continue;
			}
			String previous = content(lines.get(index - 1));
			String next = content(lines.get(index + 1));
			if (previous.isEmpty() || next.isEmpty() || next.startsWith("@") || next.startsWith("</")
					|| next.startsWith("<p") || previous.startsWith("<p")) {
				continue;
			}
			lines.set(index, matcher.group(1) + " <p>");
			changed = true;
		}
		return changed ? Optional.of(new Candidate(comment, String.join(separator, lines))) : Optional.empty();
	}

	private static String content(String line) {
		String value = line.strip();
		if (value.startsWith("*")) {
			value = value.substring(1).strip();
		}
		return value;
	}

	private record Candidate(JavadocComment comment, String replacement) {
	}

}
