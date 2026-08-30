package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

/** Reports expression-level style preferences and avoidable indirection. */
public final class ReportExpressionStyleIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-expression-style-issues";
	}

	@Override
	public String description() {
		return "Report verbose equality, Optional, constant, and string expression styles";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		assertions(context, findings);
		enumEquality(context, findings);
		indexOf(context, findings);
		objectsEquals(context, findings);
		calls(context, findings);
		binaryExpressions(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void assertions(InspectionContext context, List<Finding> findings) {
		for (AssertStmt statement : context.compilationUnit().findAll(AssertStmt.class)) {
			statement.getMessage()
				.filter(message -> !(message instanceof StringLiteralExpr))
				.filter(message -> TypeLookup.visibleType(context.compilationUnit(), message, statement)
					.map(ReportExpressionStyleIssuesTool::simple)
					.filter(type -> "String".equals(type))
					.isEmpty())
				.ifPresent(message -> findings.add(Finding.at(message, "'assert' message is not a String")));
		}
	}

	private static void enumEquality(InspectionContext context, List<Finding> findings) {
		Set<String> enums = context.compilationUnit()
			.findAll(EnumDeclaration.class)
			.stream()
			.map(EnumDeclaration::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"equals".equals(call.getNameAsString()) || call.getArguments().size() != 1
					|| call.getScope().isEmpty()) {
				continue;
			}
			String type = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
				.map(ReportExpressionStyleIssuesTool::simple)
				.orElse("");
			if (enums.contains(type)) {
				findings.add(Finding.at(call, "equals() called on enum value can use identity comparison"));
			}
		}
	}

	private static void indexOf(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			MethodCallExpr call = binary.getLeft() instanceof MethodCallExpr left ? left
					: binary.getRight() instanceof MethodCallExpr right ? right : null;
			Expression other = call == binary.getLeft() ? binary.getRight() : binary.getLeft();
			if (call == null || !"indexOf".equals(call.getNameAsString()) || call.getScope().isEmpty()
					|| call.getArguments().size() != 1 || !indexOfPresenceComparison(binary, call, other)) {
				continue;
			}
			String type = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
				.orElse("");
			if (TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, Set.of("List"))) {
				findings.add(Finding.at(binary, "List.indexOf() presence comparison can be replaced with contains()"));
			}
		}
	}

	private static boolean indexOfPresenceComparison(BinaryExpr binary, MethodCallExpr call,
			Expression thresholdExpression) {
		Integer threshold = signedInteger(thresholdExpression).orElse(null);
		if (threshold == null) {
			return false;
		}
		Operator operator = call == binary.getLeft() ? binary.getOperator() : reversed(binary.getOperator());
		return threshold == 0 && Set.of(BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.LESS).contains(operator)
				|| threshold == -1 && Set
					.of(BinaryExpr.Operator.GREATER, BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS,
							BinaryExpr.Operator.LESS_EQUALS)
					.contains(operator);
	}

	private static BinaryExpr.Operator reversed(BinaryExpr.Operator operator) {
		return switch (operator) {
			case LESS -> BinaryExpr.Operator.GREATER;
			case LESS_EQUALS -> BinaryExpr.Operator.GREATER_EQUALS;
			case GREATER -> BinaryExpr.Operator.LESS;
			case GREATER_EQUALS -> BinaryExpr.Operator.LESS_EQUALS;
			default -> operator;
		};
	}

	private static Optional<Integer> signedInteger(Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (currentExpression instanceof IntegerLiteralExpr literal) {
			return Optional.of(literal.asNumber().intValue());
		}
		if (currentExpression instanceof UnaryExpr unary
				&& unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS
				&& unwrap(unary.getExpression()) instanceof IntegerLiteralExpr literal) {
			return Optional.of(-literal.asNumber().intValue());
		}
		return Optional.empty();
	}

	private static void objectsEquals(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"equals".equals(call.getNameAsString()) || call.getArguments().size() != 2
					|| call.getScope()
						.filter(scope -> Set.of("Objects", "java.util.Objects").contains(scope.toString()))
						.isEmpty()) {
				continue;
			}
			if (intrinsicallyNonNull(call.getArgument(0))) {
				findings.add(Finding.at(call,
						"Objects.equals() can be replaced with equals() on the non-null first argument"));
			}
		}
	}

	private static boolean intrinsicallyNonNull(Expression expression) {
		return expression instanceof StringLiteralExpr || expression instanceof ObjectCreationExpr
				|| expression instanceof ArrayCreationExpr || expression.isThisExpr() || expression.isClassExpr()
				|| expression.isLambdaExpr();
	}

	private static void calls(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if ("concat".equals(call.getNameAsString()) && call.getArguments().size() == 1
					&& call.getScope().isPresent()) {
				findings.add(Finding.at(call, "Call to String.concat() can be replaced with '+'"));
			}
			if ("valueOf".equals(call.getNameAsString()) && call.getArguments().size() == 1
					&& call.getScope()
						.filter(scope -> Set.of("String", "java.lang.String").contains(scope.toString()))
						.isPresent()
					&& unnecessaryStringContext(call)) {
				findings.add(Finding.at(call, "Unnecessary conversion to String"));
			}
		}
	}

	private static boolean unnecessaryStringContext(MethodCallExpr call) {
		Node parent = call.getParentNode().orElse(null);
		return parent instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS
				|| parent instanceof MethodCallExpr outer
						&& Set.of("append", "print", "println").contains(outer.getNameAsString());
	}

	private static void binaryExpressions(InspectionContext context, List<Finding> findings) {
		for (BinaryExpr binary : context.compilationUnit().findAll(BinaryExpr.class)) {
			if (mixedPrecedence(binary)) {
				findings.add(Finding.at(binary,
						"Multiple operators with different precedence are used without parentheses"));
			}
		}
	}

	private static boolean mixedPrecedence(BinaryExpr binary) {
		return unclearPrecedenceMix(binary.getOperator(), binary.getLeft())
				|| unclearPrecedenceMix(binary.getOperator(), binary.getRight());
	}

	private static boolean unclearPrecedenceMix(Operator parent, Expression childExpression) {
		if (!(childExpression instanceof BinaryExpr child) || precedence(parent) == precedence(child.getOperator())) {
			return false;
		}
		return arithmeticOrBitwise(parent) && arithmeticOrBitwise(child.getOperator())
				&& (bitwiseOrShift(parent) || bitwiseOrShift(child.getOperator()));
	}

	private static int precedence(Operator operator) {
		return switch (operator) {
			case MULTIPLY, DIVIDE, REMAINDER -> 10;
			case PLUS, MINUS -> 9;
			case LEFT_SHIFT, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> 8;
			case LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> 7;
			case EQUALS, NOT_EQUALS -> 6;
			case BINARY_AND -> 5;
			case XOR -> 4;
			case BINARY_OR -> 3;
			case AND -> 2;
			case OR -> 1;
		};
	}

	private static boolean arithmeticOrBitwise(Operator operator) {
		return switch (operator) {
			case MULTIPLY, DIVIDE, REMAINDER, PLUS, MINUS, LEFT_SHIFT, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT,
					BINARY_AND, XOR, BINARY_OR ->
				true;
			default -> false;
		};
	}

	private static boolean bitwiseOrShift(Operator operator) {
		return switch (operator) {
			case LEFT_SHIFT, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT, BINARY_AND, XOR, BINARY_OR -> true;
			default -> false;
		};
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression.isEnclosedExpr()) {
			currentExpression = currentExpression.asEnclosedExpr().getInner();
		}
		return currentExpression;
	}

	private static String simple(String type) {
		int dot = type.lastIndexOf('.');
		return dot < 0 ? type : type.substring(dot + 1);
	}

}
