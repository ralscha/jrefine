package ch.rasc.jrefine.tools.expressions;

import java.util.regex.Matcher;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.text.MessageFormat;

/** Reports malformed and suspicious date, message, printf, and concatenated strings. */
public final class ReportFormatStringBugsTool implements InspectionTool {

	private static final Pattern FORMAT_TOKEN = Pattern
		.compile("%(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?(?:[tT])?[a-zA-Z%]");

	@Override
	public String id() {
		return "report-format-string-bugs";
	}

	@Override
	public String description() {
		return "Report malformed format patterns and suspicious string concatenations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		textBlocks(context, findings);
		datePatterns(context, findings);
		messagePatterns(context, findings);
		printfPatterns(context, findings);
		concatenatedFormats(context, findings);
		missingWhitespace(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void textBlocks(InspectionContext context, List<Finding> findings) {
		for (TextBlockLiteralExpr block : context.compilationUnit().findAll(TextBlockLiteralExpr.class)) {
			String source = LineEndingSupport.normalize(context.editor().text(block));
			boolean hasSpaceIndent = false;
			boolean hasTabIndent = false;
			for (String line : source.split(LineEndingSupport.LINE_FEED)) {
				if (line.startsWith(" ")) {
					hasSpaceIndent = true;
				}
				if (line.startsWith("\t")) {
					hasTabIndent = true;
				}
			}
			if (hasSpaceIndent && hasTabIndent) {
				findings.add(Finding.at(block, "Inconsistent whitespace indentation in text block"));
			}
		}
	}

	private static void datePatterns(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			if (!"SimpleDateFormat".equals(creation.getType().getNameAsString()) || creation.getArguments().isEmpty()) {
				continue;
			}
			datePattern(creation.getArgument(0))
				.ifPresent(problem -> findings.add(Finding.at(creation.getArgument(0), problem)));
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"ofPattern".equals(call.getNameAsString()) || call.getArguments().isEmpty()
					|| call.getScope().filter(scope -> scope.toString().endsWith("DateTimeFormatter")).isEmpty()) {
				continue;
			}
			datePattern(call.getArgument(0))
				.ifPresent(problem -> findings.add(Finding.at(call.getArgument(0), problem)));
		}
	}

	private static Optional<String> datePattern(Expression expression) {
		if (!(expression instanceof StringLiteralExpr literal)) {
			return Optional.empty();
		}
		String pattern = literal.asString();
		try {
			java.time.format.DateTimeFormatter.ofPattern(pattern);
		}
		catch (IllegalArgumentException exception) {
			return Optional.of("Incorrect DateTimeFormat pattern: " + exception.getMessage());
		}
		if (pattern.contains("YYYY") || pattern.matches(".*[dD]+[-/.]mm[-/.].*")) {
			return Optional.of("Suspicious date format pattern uses week-year or minutes where date fields are likely");
		}
		return Optional.empty();
	}

	private static void messagePatterns(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			if ("MessageFormat".equals(creation.getType().getNameAsString()) && !creation.getArguments().isEmpty()) {
				messagePattern(creation.getArgument(0))
					.ifPresent(problem -> findings.add(Finding.at(creation.getArgument(0), problem)));
			}
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if ("format".equals(call.getNameAsString()) && !call.getArguments().isEmpty()
					&& call.getScope().filter(scope -> scope.toString().endsWith("MessageFormat")).isPresent()) {
				messagePattern(call.getArgument(0))
					.ifPresent(problem -> findings.add(Finding.at(call.getArgument(0), problem)));
			}
		}
	}

	private static Optional<String> messagePattern(Expression expression) {
		if (!(expression instanceof StringLiteralExpr literal)) {
			return Optional.empty();
		}
		try {
			new MessageFormat(literal.asString());
			return Optional.empty();
		}
		catch (IllegalArgumentException exception) {
			return Optional.of("Incorrect MessageFormat pattern: " + exception.getMessage());
		}
	}

	private static void printfPatterns(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			Expression format = null;
			if (Set.of("format", "printf").contains(call.getNameAsString()) && !call.getArguments().isEmpty()) {
				format = call.getArgument(0);
			}
			if ("formatted".equals(call.getNameAsString()) && call.getScope().isPresent()) {
				format = call.getScope().orElseThrow();
			}
			if (format instanceof StringLiteralExpr literal && malformed(literal.asString())) {
				findings.add(Finding.at(format, "Malformed printf-style format string"));
			}
		}
	}

	private static boolean malformed(String pattern) {
		for (int index = 0; index < pattern.length(); index++) {
			if (pattern.charAt(index) != '%') {
				continue;
			}
			Matcher matcher = FORMAT_TOKEN.matcher(pattern.substring(index));
			if (!matcher.lookingAt()) {
				return true;
			}
			index += matcher.end() - 1;
		}
		return false;
	}

	private static void concatenatedFormats(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"format".equals(call.getNameAsString()) || call.getArguments().isEmpty()
					|| !(call.getArgument(0) instanceof BinaryExpr concatenation)
					|| concatenation.getOperator() != BinaryExpr.Operator.PLUS) {
				continue;
			}
			String owner = call.getScope().map(Object::toString).orElse("");
			findings.add(Finding.at(call.getArgument(0),
					owner.endsWith("MessageFormat")
							? "String concatenation is used as an argument to MessageFormat.format()"
							: "String concatenation is used as an argument to format()"));
		}
	}

	private static void missingWhitespace(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			if (binary.getOperator() != BinaryExpr.Operator.PLUS
					|| !(binary.getLeft() instanceof StringLiteralExpr left)
					|| !(binary.getRight() instanceof StringLiteralExpr right) || left.asString().isEmpty()
					|| right.asString().isEmpty()) {
				continue;
			}
			int end = left.asString().codePointBefore(left.asString().length());
			int start = right.asString().codePointAt(0);
			if (Character.isLetterOrDigit(end) && Character.isLetterOrDigit(start)) {
				findings.add(Finding.at(binary, "Whitespace may be missing in string concatenation"));
			}
		}
	}

}
