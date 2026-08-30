package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reports mechanically inconsistent JavaBeans getters and setters. */
public final class ReportJavaBeansContractBugsTool implements InspectionTool {

	@Override
	public String id() {
		return "report-javabeans-contract-bugs";
	}

	@Override
	public String description() {
		return "Report JavaBeans accessors that read, write, or assign the wrong value";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (type.isInterface()) {
				continue;
			}
			inspectType(type, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void inspectType(ClassOrInterfaceDeclaration type, List<Finding> findings) {
		Map<String, FieldDeclaration> fields = new HashMap<>();
		type.getFields()
			.forEach(field -> field.getVariables().forEach(variable -> fields.put(variable.getNameAsString(), field)));
		for (MethodDeclaration method : type.getMethods()) {
			String property = getterProperty(method);
			if (property != null && fields.containsKey(property)) {
				String returned = returnedField(method, fields);
				if (returned != null && !returned.equals(property)) {
					findings.add(Finding.at(method,
							"Getter for property '" + property + "' returns field '" + returned + "'"));
				}
			}
			property = setterProperty(method);
			if (property == null) {
				continue;
			}
			selfAssignment(method, fields, findings);
			if (!fields.containsKey(property)) {
				continue;
			}
			String assigned = assignedField(method, fields);
			if (assigned != null && !assigned.equals(property)) {
				findings
					.add(Finding.at(method, "Setter for property '" + property + "' writes field '" + assigned + "'"));
			}
		}
	}

	private static String getterProperty(MethodDeclaration method) {
		if (!method.isPublic() || method.isStatic() || !method.getParameters().isEmpty()
				|| method.getType().isVoidType()) {
			return null;
		}
		String name = method.getNameAsString();
		if (name.startsWith("get") && name.length() > 3) {
			return decapitalize(name.substring(3));
		}
		if (name.startsWith("is") && name.length() > 2 && booleanType(method)) {
			return decapitalize(name.substring(2));
		}
		return null;
	}

	private static String setterProperty(MethodDeclaration method) {
		String name = method.getNameAsString();
		if (!method.isPublic() || method.isStatic() || method.getParameters().size() != 1 || !name.startsWith("set")
				|| name.length() <= 3) {
			return null;
		}
		return decapitalize(name.substring(3));
	}

	private static boolean booleanType(MethodDeclaration method) {
		String type = method.getType().asString();
		return "boolean".equals(type) || "Boolean".equals(type) || "java.lang.Boolean".equals(type);
	}

	private static String returnedField(MethodDeclaration method, Map<String, FieldDeclaration> fields) {
		if (method.getBody().isEmpty()) {
			return null;
		}
		List<ReturnStmt> returns = method.getBody()
			.orElseThrow()
			.findAll(ReturnStmt.class)
			.stream()
			.filter(statement -> directlyWithin(statement, method))
			.toList();
		if (returns.size() != 1 || returns.getFirst().getExpression().isEmpty()) {
			return null;
		}
		return fieldName(returns.getFirst().getExpression().orElseThrow(), fields);
	}

	private static String assignedField(MethodDeclaration method, Map<String, FieldDeclaration> fields) {
		if (method.getBody().isEmpty()) {
			return null;
		}
		String parameter = method.getParameter(0).getNameAsString();
		List<AssignExpr> assignments = method.getBody()
			.orElseThrow()
			.findAll(AssignExpr.class)
			.stream()
			.filter(assignment -> directlyWithin(assignment, method))
			.filter(assignment -> assignment.getValue() instanceof NameExpr name
					&& name.getNameAsString().equals(parameter))
			.toList();
		if (assignments.size() != 1) {
			return null;
		}
		return fieldName(assignments.getFirst().getTarget(), fields);
	}

	private static void selfAssignment(MethodDeclaration method, Map<String, FieldDeclaration> fields,
			List<Finding> findings) {
		if (method.getBody().isEmpty()) {
			return;
		}
		String parameter = method.getParameter(0).getNameAsString();
		for (AssignExpr assignment : method.getBody().orElseThrow().findAll(AssignExpr.class)) {
			if (!directlyWithin(assignment, method)) {
				continue;
			}
			if (sameName(assignment.getTarget(), assignment.getValue(), parameter)
					|| sameField(assignment.getTarget(), assignment.getValue())
					|| sameUnqualifiedField(assignment.getTarget(), assignment.getValue(), fields)) {
				findings.add(Finding.at(assignment, "Setter assigns a property value to itself"));
			}
		}
	}

	private static boolean sameName(Expression target, Expression value, String name) {
		return target instanceof NameExpr left && value instanceof NameExpr right && left.getNameAsString().equals(name)
				&& right.getNameAsString().equals(name);
	}

	private static boolean sameField(Expression target, Expression value) {
		return target instanceof FieldAccessExpr left && left.getScope() instanceof ThisExpr
				&& value instanceof FieldAccessExpr right && right.getScope() instanceof ThisExpr
				&& right.getNameAsString().equals(left.getNameAsString());
	}

	private static boolean sameUnqualifiedField(Expression target, Expression value,
			Map<String, FieldDeclaration> fields) {
		return target instanceof NameExpr left && value instanceof NameExpr right
				&& left.getNameAsString().equals(right.getNameAsString()) && fields.containsKey(left.getNameAsString())
				&& !shadowed(left) && !shadowed(right);
	}

	private static String fieldName(Expression expression, Map<String, FieldDeclaration> fields) {
		if (expression instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr
				&& fields.containsKey(access.getNameAsString())) {
			return access.getNameAsString();
		}
		if (expression instanceof NameExpr name && fields.containsKey(name.getNameAsString()) && !shadowed(name)) {
			return name.getNameAsString();
		}
		return null;
	}

	private static boolean shadowed(NameExpr use) {
		String name = use.getNameAsString();
		MethodDeclaration method = use.findAncestor(MethodDeclaration.class).orElse(null);
		if (method == null) {
			return false;
		}
		if (method.getParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
			return true;
		}
		return method.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> variable.getNameAsString().equals(name))
			.filter(variable -> before(variable, use))
			.anyMatch(variable -> variable.findAncestor(BlockStmt.class)
				.filter(block -> block.isAncestorOf(use))
				.isPresent());
	}

	private static boolean before(Node left, Node right) {
		Position first = left.getBegin().orElse(Position.HOME);
		Position second = right.getBegin().orElse(Position.HOME);
		return first.line < second.line || first.line == second.line && first.column < second.column;
	}

	private static String decapitalize(String value) {
		if (value.length() > 1 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) {
			return value;
		}
		return Character.toLowerCase(value.charAt(0)) + value.substring(1);
	}

	private static boolean directlyWithin(Node node, MethodDeclaration method) {
		Node current = node;
		while (current != method) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof LambdaExpr
					|| parent instanceof CallableDeclaration<?> && parent != method
					|| parent instanceof TypeDeclaration<?>) {
				return false;
			}
			current = parent;
		}
		return true;
	}

}
