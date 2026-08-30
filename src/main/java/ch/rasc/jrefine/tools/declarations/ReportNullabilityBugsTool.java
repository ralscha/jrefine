package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

/**
 * Reports source-local violations of common nullability annotations and proven null
 * dereferences.
 */
public final class ReportNullabilityBugsTool implements InspectionTool {

	private static final Set<String> NON_NULL = Set.of("NotNull", "NonNull", "Nonnull");

	@Override
	public String id() {
		return "report-nullability-bugs";
	}

	@Override
	public String description() {
		return "Report uninitialized non-null fields, null returns/arguments, and proven null dereferences";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		nonNullFields(context, findings);
		nullReturns(context, findings);
		nullArguments(context, findings);
		nullDereferences(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void nonNullFields(InspectionContext context, List<Finding> findings) {
		for (FieldDeclaration field : context.compilationUnit().findAll(FieldDeclaration.class)) {
			if (!annotated(field, NON_NULL) || field.isStatic() || validationConstraint(context, field)) {
				continue;
			}
			ClassOrInterfaceDeclaration owner = AstSupport.ancestor(field, ClassOrInterfaceDeclaration.class)
				.orElse(null);
			if (owner == null) {
				continue;
			}
			for (VariableDeclarator variable : field.getVariables()) {
				if (variable.getType().isPrimitiveType() || variable.getInitializer().isPresent()) {
					continue;
				}
				List<ConstructorDeclaration> constructors = owner.getConstructors();
				if (constructors.isEmpty() || constructors.stream()
					.anyMatch(constructor -> !assigned(constructor, variable.getNameAsString()))) {
					findings.add(Finding.at(variable, "Non-null field is not initialized on every constructor path"));
				}
			}
		}
	}

	private static boolean assigned(Node node, String name) {
		return node.findAll(AssignExpr.class)
			.stream()
			.anyMatch(assignment -> assignment.getTarget() instanceof NameExpr target
					&& target.getNameAsString().equals(name)
					|| assignment.getTarget() instanceof FieldAccessExpr access && access.getNameAsString().equals(name)
							&& access.getScope().isThisExpr());
	}

	private static void nullReturns(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			if (!annotated(method, NON_NULL) || method.getBody().isEmpty()) {
				continue;
			}
			method.getBody()
				.orElseThrow()
				.findAll(ReturnStmt.class)
				.stream()
				.filter(returned -> returned.getExpression().orElse(null) instanceof NullLiteralExpr)
				.forEach(returned -> findings
					.add(Finding.at(returned, "Return of null violates the method's non-null contract")));
		}
	}

	private static void nullArguments(InspectionContext context, List<Finding> findings) {
		List<MethodDeclaration> methods = context.compilationUnit().findAll(MethodDeclaration.class);
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			methods.stream()
				.filter(method -> method.getNameAsString().equals(call.getNameAsString())
						&& method.getParameters().size() == call.getArguments().size())
				.findFirst()
				.ifPresent(method -> {
					for (int index = 0; index < method.getParameters().size(); index++) {
						if (annotated(method.getParameter(index), NON_NULL)
								&& call.getArgument(index) instanceof NullLiteralExpr) {
							findings.add(Finding.at(call.getArgument(index),
									"Nullability problem: null is passed to a non-null parameter"));
						}
					}
				});
		}
	}

	private static void nullDereferences(InspectionContext context, List<Finding> findings) {
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (!(variable.getInitializer().orElse(null) instanceof NullLiteralExpr)
					|| AstSupport.ancestor(variable, FieldDeclaration.class).isPresent()) {
				continue;
			}
			BlockStmt block = AstSupport.ancestor(variable, BlockStmt.class).orElse(null);
			if (block == null) {
				continue;
			}
			String name = variable.getNameAsString();
			for (MethodCallExpr call : block.findAll(MethodCallExpr.class)) {
				if (!(call.getScope().orElse(null) instanceof NameExpr scope) || !scope.getNameAsString().equals(name)
						|| call.getBegin().orElseThrow().isBefore(variable.getBegin().orElseThrow())) {
					continue;
				}
				boolean reassigned = block.findAll(AssignExpr.class)
					.stream()
					.anyMatch(assignment -> assignment.getTarget() instanceof NameExpr target
							&& target.getNameAsString().equals(name)
							&& assignment.getBegin().orElseThrow().isAfter(variable.getBegin().orElseThrow())
							&& assignment.getBegin().orElseThrow().isBefore(call.getBegin().orElseThrow()));
				if (!reassigned && !guardedNonNull(call, name)) {
					findings.add(Finding.at(call,
							"Nullability and data flow problem: definitely-null value is dereferenced"));
				}
			}
		}
	}

	private static boolean validationConstraint(InspectionContext context, FieldDeclaration field) {
		if (field.getAnnotations()
			.stream()
			.noneMatch(annotation -> "NotNull".equals(annotation.getName().getIdentifier()))) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(importDeclaration -> !importDeclaration.isStatic() && !importDeclaration.isAsterisk()
					&& Set.of("jakarta.validation.constraints.NotNull", "javax.validation.constraints.NotNull")
						.contains(importDeclaration.getNameAsString()));
	}

	private static boolean guardedNonNull(Node node, String name) {
		Node child = node;
		Node parent = node.getParentNode().orElse(null);
		while (parent != null) {
			if (parent instanceof BinaryExpr binary && within(child, binary.getRight())
					&& (binary.getOperator() == BinaryExpr.Operator.AND && nonNullWhenTrue(binary.getLeft(), name)
							|| binary.getOperator() == BinaryExpr.Operator.OR
									&& nullWhenTrue(binary.getLeft(), name))) {
				return true;
			}
			if (parent instanceof IfStmt conditional && within(child, conditional.getThenStmt())
					&& nonNullWhenTrue(conditional.getCondition(), name)) {
				return true;
			}
			child = parent;
			parent = parent.getParentNode().orElse(null);
		}
		return false;
	}

	private static boolean nonNullWhenTrue(Expression expression, String name) {
		Expression value = unwrap(expression);
		if (value instanceof BinaryExpr binary) {
			if (binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS && nullComparison(binary, name)) {
				return true;
			}
			return binary.getOperator() == BinaryExpr.Operator.AND
					&& (nonNullWhenTrue(binary.getLeft(), name) || nonNullWhenTrue(binary.getRight(), name));
		}
		return false;
	}

	private static boolean nullWhenTrue(Expression expression, String name) {
		Expression value = unwrap(expression);
		if (value instanceof BinaryExpr binary) {
			if (binary.getOperator() == BinaryExpr.Operator.EQUALS && nullComparison(binary, name)) {
				return true;
			}
			return binary.getOperator() == BinaryExpr.Operator.OR
					&& (nullWhenTrue(binary.getLeft(), name) || nullWhenTrue(binary.getRight(), name));
		}
		return false;
	}

	private static boolean nullComparison(BinaryExpr binary, String name) {
		return binary.getLeft() instanceof NameExpr left && left.getNameAsString().equals(name)
				&& binary.getRight() instanceof NullLiteralExpr
				|| binary.getRight() instanceof NameExpr right && right.getNameAsString().equals(name)
						&& binary.getLeft() instanceof NullLiteralExpr;
	}

	private static Expression unwrap(Expression expression) {
		Expression value = expression;
		while (value instanceof EnclosedExpr enclosed) {
			value = enclosed.getInner();
		}
		return value;
	}

	private static boolean within(Node child, Node possibleAncestor) {
		return child == possibleAncestor || possibleAncestor.isAncestorOf(child);
	}

	private static boolean annotated(NodeWithAnnotations<?> node, Set<String> names) {
		return node.getAnnotations()
			.stream()
			.anyMatch(annotation -> names.contains(annotation.getName().getIdentifier()));
	}

}
