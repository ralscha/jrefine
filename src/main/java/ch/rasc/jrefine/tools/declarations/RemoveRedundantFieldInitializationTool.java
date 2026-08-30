package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import java.util.Optional;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.type.Type;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

/** Removes explicit field values that are identical to JVM default initialization. */
public final class RemoveRedundantFieldInitializationTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-field-initialization";
	}

	@Override
	public String description() {
		return "Remove field initializers that repeat the Java default value";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<VariableDeclarator> candidates = context.compilationUnit()
			.findAll(FieldDeclaration.class)
			.stream()
			.filter(field -> !field.isFinal())
			.filter(field -> !insideInterface(field))
			.flatMap(field -> field.getVariables().stream())
			.filter(variable -> variable.getInitializer()
				.filter(value -> isDefaultValue(variable.getType(), value))
				.isPresent())
			.filter(variable -> !hasComment(context.editor().text(variable)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (VariableDeclarator variable : candidates) {
			Expression initializer = variable.getInitializer().orElseThrow();
			findings.add(Finding.at(initializer,
					"Remove redundant default initializer for field '" + variable.getNameAsString() + "'"));
			if (applyFixes) {
				JavaToken equals = previousSignificantToken(initializer, "=");
				context.editor()
					.replace(new Range(leadingWhitespaceBegin(equals), initializer.getRange().orElseThrow().end), "");
				variable.removeInitializer();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean insideInterface(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node ancestor = parent.orElseThrow();
			if (ancestor instanceof ClassOrInterfaceDeclaration declaration) {
				return declaration.isInterface();
			}
			if (ancestor instanceof AnnotationDeclaration) {
				return true;
			}
			parent = ancestor.getParentNode();
		}
		return false;
	}

	private static boolean isDefaultValue(Type type, Expression value) {
		if (!type.isPrimitiveType()) {
			return value instanceof NullLiteralExpr;
		}
		return switch (type.asPrimitiveType().getType()) {
			case BOOLEAN -> value instanceof BooleanLiteralExpr literal && !literal.getValue();
			case CHAR -> value instanceof CharLiteralExpr literal && literal.asChar() == '\0';
			default -> numericZero(value);
		};
	}

	private static boolean numericZero(Expression expression) {
		if (expression instanceof UnaryExpr unary && (unary.getOperator() == UnaryExpr.Operator.PLUS
				|| unary.getOperator() == UnaryExpr.Operator.MINUS)) {
			return numericZero(unary.getExpression());
		}
		if (!(expression instanceof LiteralStringValueExpr literal)) {
			return false;
		}
		String value = literal.getValue().replace("_", "").toLowerCase();
		try {
			if (value.endsWith("l")) {
				value = value.substring(0, value.length() - 1);
			}
			if (value.endsWith("f") || value.endsWith("d")) {
				value = value.substring(0, value.length() - 1);
			}
			if (value.startsWith("0x") || value.startsWith("0b")) {
				int radix = value.startsWith("0x") ? 16 : 2;
				return new BigInteger(value.substring(2), radix).signum() == 0;
			}
			return new BigDecimal(value).compareTo(BigDecimal.ZERO) == 0;
		}
		catch (NumberFormatException ignored) {
			return false;
		}
	}

	private static JavaToken previousSignificantToken(Expression expression, String expected) {
		JavaToken token = expression.getTokenRange().orElseThrow().getBegin();
		while (token.getPreviousToken().isPresent()) {
			token = token.getPreviousToken().orElseThrow();
			if (!token.getText().isBlank()) {
				if (!token.getText().equals(expected)) {
					throw new IllegalStateException("Expected '" + expected + "' before initializer");
				}
				return token;
			}
		}
		throw new IllegalStateException("Could not locate initializer assignment token");
	}

	private static Position leadingWhitespaceBegin(JavaToken token) {
		JavaToken currentToken = token;
		Position begin = currentToken.getRange().orElseThrow().begin;
		Optional<JavaToken> previous = currentToken.getPreviousToken();
		while (previous.isPresent() && previous.orElseThrow().getText().isBlank()) {
			currentToken = previous.orElseThrow();
			begin = currentToken.getRange().orElseThrow().begin;
			previous = currentToken.getPreviousToken();
		}
		return begin;
	}

	private static boolean hasComment(String source) {
		return source.contains("//") || source.contains("/*");
	}

}
