package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reports assignments and increment/decrement uses that obscure or violate variable
 * roles.
 */
public final class ReportAssignmentIssuesTool implements PolicyInspectionTool {

	private static final Set<UnaryExpr.Operator> MUTATING_UNARY = Set.of(UnaryExpr.Operator.PREFIX_INCREMENT,
			UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
			UnaryExpr.Operator.POSTFIX_DECREMENT);

	@Override
	public String id() {
		return "report-assignment-issues";
	}

	@Override
	public String description() {
		return "Report suspicious parameter, field, nested, conditional, null, and increment assignments";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		catchParameters(context, findings);
		forParameters(context, findings);
		lambdaParameters(context, findings);
		callableParameters(context, findings);
		staticFields(context, findings);
		conditionalAssignments(context, findings);
		inheritedFields(context, findings);
		nestedAssignments(context, findings);
		usedIncrementResults(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void catchParameters(InspectionContext context, List<Finding> findings) {
		for (CatchClause clause : context.compilationUnit().findAll(CatchClause.class)) {
			reportMutations(clause.getBody(), clause.getParameter().getNameAsString(), clause,
					"Assignment to catch block parameter", findings);
		}
	}

	private static void forParameters(InspectionContext context, List<Finding> findings) {
		for (ForEachStmt loop : context.compilationUnit().findAll(ForEachStmt.class)) {
			for (VariableDeclarator variable : loop.getVariable().getVariables()) {
				reportMutations(loop.getBody(), variable.getNameAsString(), loop, "Assignment to for-loop parameter",
						findings);
			}
		}
	}

	private static void lambdaParameters(InspectionContext context, List<Finding> findings) {
		for (LambdaExpr lambda : context.compilationUnit().findAll(LambdaExpr.class)) {
			for (Parameter parameter : lambda.getParameters()) {
				reportMutations(lambda.getBody(), parameter.getNameAsString(), lambda, "Assignment to lambda parameter",
						findings);
			}
		}
	}

	private static void callableParameters(InspectionContext context, List<Finding> findings) {
		List<CallableDeclaration<?>> callables = new ArrayList<>(
				context.compilationUnit().findAll(MethodDeclaration.class));
		callables.addAll(context.compilationUnit().findAll(ConstructorDeclaration.class));
		for (CallableDeclaration<?> callable : callables) {
			for (Parameter parameter : callable.getParameters()) {
				reportMutations(callable, parameter.getNameAsString(), callable,
						"Assignment to method or constructor parameter", findings);
			}
		}
	}

	private static void reportMutations(Node body, String name, Node boundary, String message, List<Finding> findings) {
		body.findAll(AssignExpr.class)
			.stream()
			.filter(assignment -> simpleTarget(assignment.getTarget(), name))
			.filter(assignment -> nearestBoundary(assignment).orElse(null) == boundary
					|| boundary instanceof CatchClause || boundary instanceof ForEachStmt)
			.forEach(assignment -> findings.add(Finding.at(assignment, message)));
		body.findAll(UnaryExpr.class)
			.stream()
			.filter(unary -> MUTATING_UNARY.contains(unary.getOperator()) && simpleTarget(unary.getExpression(), name))
			.filter(unary -> nearestBoundary(unary).orElse(null) == boundary || boundary instanceof CatchClause
					|| boundary instanceof ForEachStmt)
			.forEach(unary -> findings.add(Finding.at(unary, message)));
	}

	private static Optional<Node> nearestBoundary(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof LambdaExpr || value instanceof CallableDeclaration<?>) {
				return Optional.of(value);
			}
			parent = value.getParentNode();
		}
		return Optional.empty();
	}

	private static void staticFields(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			Set<String> names = type.getFields()
				.stream()
				.filter(field -> field.isStatic())
				.flatMap(field -> field.getVariables().stream())
				.map(variable -> variable.getNameAsString())
				.collect(java.util.stream.Collectors.toSet());
			if (names.isEmpty()) {
				continue;
			}
			type.getMethods()
				.stream()
				.filter(method -> !method.isStatic() && method.getBody().isPresent())
				.forEach(method -> reportStaticMutations(context, method.getBody().orElseThrow(), names, findings));
			type.getConstructors()
				.forEach(constructor -> reportStaticMutations(context, constructor.getBody(), names, findings));
		}
	}

	private static void reportStaticMutations(InspectionContext context, Node body, Set<String> names,
			List<Finding> findings) {
		body.findAll(AssignExpr.class)
			.stream()
			.filter(assignment -> targetName(assignment.getTarget()).filter(names::contains).isPresent()
					&& fieldTarget(context, assignment.getTarget(), assignment))
			.forEach(assignment -> findings
				.add(Finding.at(assignment, "Assignment to static field from instance context")));
		body.findAll(UnaryExpr.class)
			.stream()
			.filter(unary -> MUTATING_UNARY.contains(unary.getOperator()))
			.filter(unary -> targetName(unary.getExpression()).filter(names::contains).isPresent()
					&& fieldTarget(context, unary.getExpression(), unary))
			.forEach(unary -> findings.add(Finding.at(unary, "Modification of static field from instance context")));
	}

	private static void conditionalAssignments(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(AssignExpr.class)
			.stream()
			.filter(ReportAssignmentIssuesTool::insideCondition)
			.forEach(assignment -> findings.add(Finding.at(assignment, "Assignment is used as a condition")));
	}

	private static boolean insideCondition(AssignExpr assignment) {
		Node child = assignment;
		Optional<Node> parent = assignment.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof IfStmt statement) {
				return statement.getCondition().isAncestorOf(child) || statement.getCondition() == child;
			}
			if (value instanceof WhileStmt statement) {
				return statement.getCondition().isAncestorOf(child) || statement.getCondition() == child;
			}
			if (value instanceof DoStmt statement) {
				return statement.getCondition().isAncestorOf(child) || statement.getCondition() == child;
			}
			if (value instanceof ForStmt statement) {
				Expression condition = statement.getCompare().orElse(null);
				return condition != null && (condition == child || condition.isAncestorOf(child));
			}
			if (value instanceof ConditionalExpr expression) {
				return expression.getCondition() == child || expression.getCondition().isAncestorOf(child);
			}
			if (value instanceof ExpressionStmt || value instanceof CallableDeclaration<?>) {
				return false;
			}
			child = value;
			parent = value.getParentNode();
		}
		return false;
	}

	private static void inheritedFields(InspectionContext context, List<Finding> findings) {
		List<ClassOrInterfaceDeclaration> types = context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class);
		for (ClassOrInterfaceDeclaration subtype : types) {
			for (ClassOrInterfaceType parentReference : subtype.getExtendedTypes()) {
				ClassOrInterfaceDeclaration parent = types.stream()
					.filter(candidate -> candidate.getNameAsString().equals(parentReference.getNameAsString()))
					.findFirst()
					.orElse(null);
				if (parent == null) {
					continue;
				}
				Set<String> fields = parent.getFields()
					.stream()
					.flatMap(field -> field.getVariables().stream())
					.map(variable -> variable.getNameAsString())
					.collect(java.util.stream.Collectors.toSet());
				for (ConstructorDeclaration constructor : subtype.getConstructors()) {
					constructor.findAll(AssignExpr.class)
						.stream()
						.filter(assignment -> targetName(assignment.getTarget()).filter(fields::contains).isPresent()
								&& fieldTarget(context, assignment.getTarget(), assignment))
						.forEach(assignment -> findings
							.add(Finding.at(assignment, "Constructor assigns a field declared in its superclass")));
				}
			}
		}
	}

	private static void nestedAssignments(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(AssignExpr.class)
			.stream()
			.filter(assignment -> !(assignment.getParentNode().orElse(null) instanceof ExpressionStmt)
					&& !directForExpression(assignment))
			.forEach(assignment -> findings
				.add(Finding.at(assignment, "Assignment expression is nested inside another expression")));
	}

	private static boolean directForExpression(AssignExpr assignment) {
		Node parent = assignment.getParentNode().orElse(null);
		return parent instanceof ForStmt statement
				&& (statement.getInitialization().contains(assignment) || statement.getUpdate().contains(assignment));
	}

	private static void usedIncrementResults(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(UnaryExpr.class)
			.stream()
			.filter(unary -> MUTATING_UNARY.contains(unary.getOperator()))
			.filter(unary -> !(unary.getParentNode().orElse(null) instanceof ExpressionStmt) && !directForUpdate(unary))
			.forEach(unary -> findings.add(Finding.at(unary, "Result of increment or decrement expression is used")));
	}

	private static boolean directForUpdate(UnaryExpr unary) {
		return unary.getParentNode()
			.filter(parent -> parent instanceof ForStmt statement && statement.getUpdate().contains(unary))
			.isPresent();
	}

	private static boolean simpleTarget(Expression expression, String name) {
		return expression instanceof NameExpr target && target.getNameAsString().equals(name);
	}

	private static Optional<String> targetName(Expression expression) {
		if (expression instanceof NameExpr name) {
			return Optional.of(name.getNameAsString());
		}
		if (expression instanceof FieldAccessExpr access
				&& (access.getScope() instanceof ThisExpr || access.getScope().isNameExpr())) {
			return Optional.of(access.getNameAsString());
		}
		return Optional.empty();
	}

	private static boolean fieldTarget(InspectionContext context, Expression expression, Node use) {
		if (!(expression instanceof NameExpr name)) {
			return true;
		}
		return !ch.rasc.jrefine.analysis.TypeLookup
			.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name.getNameAsString(), use);
	}

}
