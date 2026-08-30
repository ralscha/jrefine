package ch.rasc.jrefine.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.api.InspectionContext;

import java.util.Map;
import java.util.Optional;

/** Conservative primitive type inference for numeric source rewrites. */
public final class NumericSupport {

	private static final Map<String, Integer> RANKS = Map.of("byte", 1, "short", 2, "char", 2, "int", 3, "long", 4,
			"float", 5, "double", 6);

	private NumericSupport() {
	}

	public static Optional<String> typeOf(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof IntegerLiteralExpr) {
			return Optional.of("int");
		}
		if (expression instanceof LongLiteralExpr) {
			return Optional.of("long");
		}
		if (expression instanceof CharLiteralExpr) {
			return Optional.of("char");
		}
		if (expression instanceof BooleanLiteralExpr) {
			return Optional.of("boolean");
		}
		if (expression instanceof DoubleLiteralExpr literal) {
			String spelling = literal.getValue().toLowerCase(java.util.Locale.ROOT);
			return Optional.of(spelling.endsWith("f") ? "float" : "double");
		}
		if (expression instanceof CastExpr cast && cast.getType().isPrimitiveType()) {
			return Optional.of(cast.getType().asString());
		}
		if (expression instanceof EnclosedExpr enclosed) {
			return typeOf(context, enclosed.getInner(), use);
		}
		if (expression instanceof UnaryExpr unary) {
			Optional<String> type = typeOf(context, unary.getExpression(), use);
			if (unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
				return Optional.of("boolean");
			}
			return type.map(NumericSupport::unaryPromotion);
		}
		if (expression instanceof BinaryExpr binary) {
			return binaryType(context, binary, use);
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use).map(NumericSupport::simpleName);
	}

	public static boolean isNumeric(String type) {
		return RANKS.containsKey(simpleName(type));
	}

	public static boolean isIntegral(String type) {
		return switch (simpleName(type)) {
			case "byte", "short", "char", "int", "long" -> true;
			default -> false;
		};
	}

	public static boolean isFloatingPoint(String type) {
		return switch (simpleName(type)) {
			case "float", "double" -> true;
			default -> false;
		};
	}

	/** Returns the primitive type imposed by a simple assignment or return context. */
	public static Optional<String> expectedType(InspectionContext context, Expression expression) {
		Node current = expression;
		while (current.getParentNode().orElse(null) instanceof EnclosedExpr enclosed
				&& enclosed.getInner() == current) {
			current = enclosed;
		}
		Node parent = current.getParentNode().orElse(null);
		if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == current
				&& variable.getType().isPrimitiveType()) {
			return Optional.of(variable.getType().asString());
		}
		if (parent instanceof AssignExpr assignment && assignment.getValue() == current) {
			return typeOf(context, assignment.getTarget(), assignment).filter(NumericSupport::isNumeric);
		}
		if (parent instanceof ReturnStmt statement && statement.getExpression().orElse(null) == current) {
			return ancestor(statement, MethodDeclaration.class).filter(method -> method.getType().isPrimitiveType())
				.map(method -> method.getType().asString());
		}
		return Optional.empty();
	}

	/** Returns whether Java permits an implicit primitive widening conversion. */
	public static boolean canWiden(String source, String target) {
		String currentSource = source;
		String currentTarget = target;
		currentSource = simpleName(currentSource);
		currentTarget = simpleName(currentTarget);
		if (currentSource.equals(currentTarget)) {
			return true;
		}
		return switch (currentSource) {
			case "byte" -> java.util.Set.of("short", "int", "long", "float", "double").contains(currentTarget);
			case "short", "char" -> java.util.Set.of("int", "long", "float", "double").contains(currentTarget);
			case "int" -> java.util.Set.of("long", "float", "double").contains(currentTarget);
			case "long" -> java.util.Set.of("float", "double").contains(currentTarget);
			case "float" -> "double".equals(currentTarget);
			default -> false;
		};
	}

	public static String simpleName(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot >= 0 ? currentType.substring(dot + 1) : currentType;
	}

	private static Optional<String> binaryType(InspectionContext context, BinaryExpr binary, Node use) {
		return switch (binary.getOperator()) {
			case MULTIPLY, DIVIDE, REMAINDER, PLUS, MINUS, BINARY_AND, BINARY_OR, XOR ->
				promoted(typeOf(context, binary.getLeft(), use), typeOf(context, binary.getRight(), use));
			case LEFT_SHIFT, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT ->
				typeOf(context, binary.getLeft(), use).map(NumericSupport::unaryPromotion);
			case LESS, LESS_EQUALS, GREATER, GREATER_EQUALS, EQUALS, NOT_EQUALS, AND, OR -> Optional.of("boolean");
		};
	}

	private static Optional<String> promoted(Optional<String> left, Optional<String> right) {
		if (left.isEmpty() || right.isEmpty()) {
			return Optional.empty();
		}
		String leftType = simpleName(left.orElseThrow());
		String rightType = simpleName(right.orElseThrow());
		if (!isNumeric(leftType) || !isNumeric(rightType)) {
			return Optional.empty();
		}
		leftType = unaryPromotion(leftType);
		rightType = unaryPromotion(rightType);
		return Optional.of(RANKS.get(leftType) >= RANKS.get(rightType) ? leftType : rightType);
	}

	private static String unaryPromotion(String type) {
		String currentType = type;
		currentType = simpleName(currentType);
		return switch (currentType) {
			case "byte", "short", "char" -> "int";
			default -> currentType;
		};
	}

	private static <T extends Node> Optional<T> ancestor(Node node, Class<T> type) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (type.isInstance(value)) {
				return Optional.of(type.cast(value));
			}
			parent = value.getParentNode();
		}
		return Optional.empty();
	}

}
