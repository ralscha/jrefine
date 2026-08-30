package ch.rasc.jrefine.tools.syntax;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import com.github.javaparser.ast.expr.UnaryExpr;

/** Reports confusing, inconsistently grouped, and octal numeric literals. */
public final class ReportNumericLiteralIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-numeric-literal-issues";
	}

	@Override
	public String description() {
		return "Report confusing floating-point, underscore, and octal literal styles";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		LinkedHashSet<Node> literals = new LinkedHashSet<>(context.compilationUnit().findAll(IntegerLiteralExpr.class));
		literals.addAll(context.compilationUnit().findAll(LongLiteralExpr.class));
		literals.addAll(context.compilationUnit().findAll(DoubleLiteralExpr.class));
		for (Node literal : literals) {
			String source = context.editor().text(literal);
			if (literal instanceof DoubleLiteralExpr && confusingFloating(source)) {
				findings.add(Finding.at(literal, "Floating-point literal has a confusing spelling"));
			}
			if (couldUseSeparators(source)) {
				findings.add(Finding.at(literal, "Long numeric literal could use underscore separators"));
			}
			if (suspiciousSeparators(source)) {
				findings.add(Finding.at(literal, "Numeric literal has suspicious underscore grouping"));
			}
			if ((literal instanceof IntegerLiteralExpr || literal instanceof LongLiteralExpr) && octal(source)) {
				findings.add(Finding.at(literal, "Octal integer literal may be mistaken for decimal"));
			}
		}
		for (ArrayInitializerExpr initializer : context.compilationUnit().findAll(ArrayInitializerExpr.class)) {
			boolean hasOctal = initializer.getValues().stream().anyMatch(value -> octalLiteral(context, value));
			boolean hasDecimal = initializer.getValues().stream().anyMatch(value -> decimalInteger(context, value));
			if (hasOctal && hasDecimal) {
				findings.add(Finding.at(initializer, "Array initializer mixes octal and decimal integer literals"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean confusingFloating(String source) {
		String value = source.replace("_", "").toLowerCase(Locale.ROOT);
		if (value.endsWith("f") || value.endsWith("d")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.startsWith("0x")) {
			return false;
		}
		int exponent = value.indexOf('e');
		String mantissa = exponent >= 0 ? value.substring(0, exponent) : value;
		return !mantissa.contains(".") || mantissa.startsWith(".") || mantissa.endsWith(".");
	}

	private static boolean couldUseSeparators(String source) {
		if (source.contains("_") || !decimal(source)) {
			return false;
		}
		String value = stripSuffix(source);
		int exponent = Math.max(value.indexOf('e'), value.indexOf('E'));
		if (exponent >= 0) {
			value = value.substring(0, exponent);
		}
		int dot = value.indexOf('.');
		if (dot >= 0) {
			value = value.substring(0, dot);
		}
		return value.length() >= 5;
	}

	private static boolean suspiciousSeparators(String source) {
		if (!source.contains("_") || !decimal(source)) {
			return false;
		}
		String value = stripSuffix(source);
		int exponentAt = Math.max(value.indexOf('e'), value.indexOf('E'));
		String exponent = exponentAt >= 0 ? value.substring(exponentAt + 1) : "";
		if (exponentAt >= 0) {
			value = value.substring(0, exponentAt);
		}
		int dot = value.indexOf('.');
		String fraction = dot >= 0 ? value.substring(dot + 1) : "";
		if (dot >= 0) {
			value = value.substring(0, dot);
		}
		if (badWholeNumberGrouping(value) || badWholeNumberGrouping(
				exponent.startsWith("+") || exponent.startsWith("-") ? exponent.substring(1) : exponent)) {
			return true;
		}
		if (!fraction.contains("_")) {
			return false;
		}
		String[] groups = fraction.split("_", -1);
		for (int index = 0; index < groups.length - 1; index++) {
			if (groups[index].length() != 3) {
				return true;
			}
		}
		return groups[groups.length - 1].isEmpty() || groups[groups.length - 1].length() > 3;
	}

	private static boolean badWholeNumberGrouping(String value) {
		if (!value.contains("_")) {
			return false;
		}
		String[] groups = value.split("_", -1);
		if (groups.length < 2 || groups[0].isEmpty() || groups[0].length() > 3) {
			return true;
		}
		for (int index = 1; index < groups.length; index++) {
			if (groups[index].length() != 3) {
				return true;
			}
		}
		return false;
	}

	private static boolean decimal(String source) {
		String value = stripSuffix(source);
		return !value.startsWith("0x") && !value.startsWith("0X") && !value.startsWith("0b") && !value.startsWith("0B")
				&& !octal(source);
	}

	private static boolean octal(String source) {
		return stripSuffix(source).matches("0[0-7_]+");
	}

	private static String stripSuffix(String source) {
		if (!source.isEmpty() && "lLfFdD".indexOf(source.charAt(source.length() - 1)) >= 0) {
			return source.substring(0, source.length() - 1);
		}
		return source;
	}

	private static boolean octalLiteral(InspectionContext context, Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression instanceof UnaryExpr unary
				&& (unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.PLUS
						|| unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS)) {
			currentExpression = unary.getExpression();
		}
		return (currentExpression instanceof IntegerLiteralExpr || currentExpression instanceof LongLiteralExpr)
				&& octal(context.editor().text(currentExpression));
	}

	private static boolean decimalInteger(InspectionContext context, Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression instanceof UnaryExpr unary
				&& (unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.PLUS
						|| unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS)) {
			currentExpression = unary.getExpression();
		}
		return (currentExpression instanceof IntegerLiteralExpr || currentExpression instanceof LongLiteralExpr)
				&& decimal(context.editor().text(currentExpression));
	}

}
