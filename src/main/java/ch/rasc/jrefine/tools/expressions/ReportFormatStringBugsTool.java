package ch.rasc.jrefine.tools.expressions;

import java.util.regex.Matcher;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
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
		return "Report malformed date, message, and printf format patterns";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		datePatterns(context, findings);
		messagePatterns(context, findings);
		printfPatterns(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	static void styleIssues(InspectionContext context, List<Finding> findings) {
		textBlocks(context, findings);
		concatenatedFormats(context, findings);
		missingWhitespace(context, findings);
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
			if (!TypeLookup.isKnownType(context.compilationUnit(), creation.getType().asString(), "java.text",
					Set.of("SimpleDateFormat")) || creation.getArguments().isEmpty()) {
				continue;
			}
			datePattern(creation.getArgument(0), true)
				.ifPresent(problem -> findings.add(Finding.at(creation.getArgument(0), problem)));
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"ofPattern".equals(call.getNameAsString()) || call.getArguments().isEmpty()
					|| !staticCall(context, call, "java.time.format", "DateTimeFormatter")) {
				continue;
			}
			datePattern(call.getArgument(0), false)
				.ifPresent(problem -> findings.add(Finding.at(call.getArgument(0), problem)));
		}
	}

	private static Optional<String> datePattern(Expression expression, boolean legacy) {
		if (!(expression instanceof StringLiteralExpr literal)) {
			return Optional.empty();
		}
		String pattern = literal.asString();
		try {
			if (legacy) {
				new java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT);
			}
			else {
				java.time.format.DateTimeFormatter.ofPattern(pattern);
			}
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
			if (TypeLookup.isKnownType(context.compilationUnit(), creation.getType().asString(), "java.text",
					Set.of("MessageFormat")) && !creation.getArguments().isEmpty()) {
				messagePattern(creation.getArgument(0))
					.ifPresent(problem -> findings.add(Finding.at(creation.getArgument(0), problem)));
			}
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if ("format".equals(call.getNameAsString()) && !call.getArguments().isEmpty()
					&& staticCall(context, call, "java.text", "MessageFormat")) {
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
			Expression format = printfFormat(context, call).orElse(null);
			if (format instanceof StringLiteralExpr literal && malformed(literal.asString())) {
				findings.add(Finding.at(format, "Malformed printf-style format string"));
			}
		}
	}

	private static Optional<Expression> printfFormat(InspectionContext context, MethodCallExpr call) {
		if (!Set.of("format", "printf", "formatted").contains(call.getNameAsString())) {
			return Optional.empty();
		}
		Expression scope = call.getScope().orElse(null);
		if (scope == null) {
			return Optional.empty();
		}
		String type = scope instanceof ObjectCreationExpr creation ? creation.getType().asString()
				: TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), scope, call).orElse("");
		boolean string = scope instanceof StringLiteralExpr || scope instanceof TextBlockLiteralExpr
				|| !type.endsWith("[]")
						&& TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String"));
		if ("formatted".equals(call.getNameAsString()) && string) {
			return Optional.of(scope);
		}
		if (!Set.of("format", "printf").contains(call.getNameAsString()) || call.getArguments().isEmpty()) {
			return Optional.empty();
		}
		boolean printStream = scope instanceof FieldAccessExpr field
				&& Set.of("out", "err").contains(field.getNameAsString())
				&& typeScope(context, field.getScope(), "java.lang", "System");
		if (!("format".equals(call.getNameAsString()) && staticCall(context, call, "java.lang", "String"))
				&& !printStream
				&& !TypeLookup.isKnownType(context.compilationUnit(), type, "java.io",
						Set.of("PrintStream", "PrintWriter"))
				&& !TypeLookup.isKnownType(context.compilationUnit(), type, "java.util", Set.of("Formatter"))) {
			return Optional.empty();
		}
		Expression first = call.getArgument(0);
		if (first instanceof StringLiteralExpr || first instanceof TextBlockLiteralExpr
				|| first instanceof BinaryExpr) {
			return Optional.of(first);
		}
		boolean locale = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), first, call)
			.filter(value -> TypeLookup.isKnownType(context.compilationUnit(), value, "java.util", Set.of("Locale")))
			.isPresent()
				|| first instanceof FieldAccessExpr field
						&& typeScope(context, field.getScope(), "java.util", "Locale");
		return locale && call.getArguments().size() > 1 ? Optional.of(call.getArgument(1)) : Optional.of(first);
	}

	private static boolean staticCall(InspectionContext context, MethodCallExpr call, String packageName, String type) {
		return call.getScope().filter(scope -> typeScope(context, scope, packageName, type)).isPresent();
	}

	private static boolean typeScope(InspectionContext context, Expression scope, String packageName, String type) {
		Expression root = scope;
		while (root instanceof FieldAccessExpr field) {
			root = field.getScope();
		}
		return (!(root instanceof NameExpr)
				|| TypeLookup.visibleDeclaredType(context.compilationUnit(), root, scope).isEmpty())
				&& TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), packageName, Set.of(type));
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
			boolean messageFormat = "format".equals(call.getNameAsString())
					&& staticCall(context, call, "java.text", "MessageFormat");
			Expression format = messageFormat && !call.getArguments().isEmpty() ? call.getArgument(0)
					: printfFormat(context, call).orElse(null);
			if (!(format instanceof BinaryExpr concatenation)
					|| concatenation.getOperator() != BinaryExpr.Operator.PLUS) {
				continue;
			}
			findings.add(Finding.at(format,
					messageFormat ? "String concatenation is used as an argument to MessageFormat.format()"
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
			boolean prose = left.asString().codePoints().anyMatch(Character::isWhitespace)
					|| right.asString().codePoints().anyMatch(Character::isWhitespace);
			if (prose && Character.isLetter(end) && Character.isLetter(start)) {
				findings.add(Finding.at(binary, "Whitespace may be missing in string concatenation"));
			}
		}
	}

}
