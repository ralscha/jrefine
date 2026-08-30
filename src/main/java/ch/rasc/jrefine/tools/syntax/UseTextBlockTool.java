package ch.rasc.jrefine.tools.syntax;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Replaces multiline all-literal String concatenations with value-equivalent text blocks.
 */
public final class UseTextBlockTool implements InspectionTool {

	@Override
	public String id() {
		return "use-text-block";
	}

	@Override
	public int minimumJavaVersion() {
		return 15;
	}

	@Override
	public String description() {
		return "Replace multiline String literal concatenations with text blocks";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(binary -> candidate(context, binary))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Multiline String concatenation can use a text block"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(),
							textBlock(candidate.value(), LineEndingSupport.detect(context.editor().source()),
									" ".repeat(candidate.expression().getBegin().orElseThrow().column - 1)));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr expression) {
		if (expression.getOperator() != BinaryExpr.Operator.PLUS || AstSupport.hasComment(context, expression)
				|| literalValue(parentExpression(expression)).isPresent()) {
			return Optional.empty();
		}
		String value = literalValue(expression).orElse(null);
		if (value == null || value.chars().filter(character -> character == '\n').count() < 2
				|| hasUnsupportedTrailingWhitespace(value)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(expression, value));
	}

	private static Expression parentExpression(BinaryExpr expression) {
		Node current = expression;
		while (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed) {
			current = enclosed;
		}
		return current.getParentNode().filter(Expression.class::isInstance).map(Expression.class::cast).orElse(null);
	}

	private static Optional<String> literalValue(Expression expression) {
		if (expression == null) {
			return Optional.empty();
		}
		if (expression instanceof StringLiteralExpr literal) {
			return Optional.of(literal.asString());
		}
		if (expression instanceof EnclosedExpr enclosed) {
			return literalValue(enclosed.getInner());
		}
		if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
			Optional<String> left = literalValue(binary.getLeft());
			Optional<String> right = literalValue(binary.getRight());
			if (left.isPresent() && right.isPresent()) {
				return Optional.of(left.orElseThrow() + right.orElseThrow());
			}
		}
		return Optional.empty();
	}

	private static String textBlock(String value, String lineEnding, String indentation) {
		String[] lines = value.split("\n", -1);
		StringBuilder result = new StringBuilder("\"\"\"").append(lineEnding);
		int contentLines = value.endsWith("\n") ? lines.length - 1 : lines.length;
		for (int index = 0; index < contentLines; index++) {
			result.append(indentation).append(encodeLine(lines[index]));
			if (index < lines.length - 1) {
				result.append(lineEnding);
			}
		}
		if (!value.endsWith("\n")) {
			result.append('\\').append(lineEnding);
		}
		result.append(indentation).append("\"\"\"");
		return result.toString();
	}

	private static String encodeLine(String line) {
		int trailingSpaces = line.length();
		while (trailingSpaces > 0 && line.charAt(trailingSpaces - 1) == ' ') {
			trailingSpaces--;
		}
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < line.length(); index++) {
			char character = line.charAt(index);
			if (character == ' ' && index >= trailingSpaces) {
				result.append("\\s");
			}
			else {
				result.append(switch (character) {
					case '\\' -> "\\\\";
					case '"' -> "\\\"";
					case '\r' -> "\\r";
					case '\t' -> "\\t";
					case '\b' -> "\\b";
					case '\f' -> "\\f";
					default ->
						character < ' ' || character == 0x7f ? octalEscape(character) : Character.toString(character);
				});
			}
		}
		return result.toString();
	}

	private static boolean hasUnsupportedTrailingWhitespace(String value) {
		for (String line : value.split("\n", -1)) {
			if (!line.isEmpty()) {
				char last = line.charAt(line.length() - 1);
				if (Character.isWhitespace(last) && last != ' ' && last != '\t' && last != '\r' && last != '\f') {
					return true;
				}
			}
		}
		return false;
	}

	private static String octalEscape(char character) {
		return "\\" + Character.forDigit(character >> 6 & 7, 8) + Character.forDigit(character >> 3 & 7, 8)
				+ Character.forDigit(character & 7, 8);
	}

	private record Candidate(BinaryExpr expression, String value) {
	}

}
