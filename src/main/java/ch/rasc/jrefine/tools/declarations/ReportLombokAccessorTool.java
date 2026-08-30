package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.github.javaparser.ast.expr.AnnotationExpr;

/** Reports trivial accessors in source files that already use Lombok. */
public final class ReportLombokAccessorTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-lombok-accessor";
	}

	@Override
	public String description() {
		return "Report standard accessors that can use Lombok @Getter or @Setter";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		if (!usesLombok(context)) {
			return new ToolResult(List.of(), false);
		}
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			ClassOrInterfaceDeclaration owner = AstSupport.ancestor(method, ClassOrInterfaceDeclaration.class)
				.orElse(null);
			if (owner == null || method.getAnnotations()
				.stream()
				.anyMatch(annotation -> "Override".equals(annotation.getName().getIdentifier()))) {
				continue;
			}
			getterField(method).flatMap(field -> matchingField(owner, method, field, true))
				.ifPresent(field -> findings
					.add(Finding.at(method, "Lombok @Getter may be used for field '" + field + "'")));
			setterField(method).flatMap(field -> matchingField(owner, method, field, false))
				.ifPresent(field -> findings
					.add(Finding.at(method, "Lombok @Setter may be used for field '" + field + "'")));
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean usesLombok(InspectionContext context) {
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> "lombok".equals(imported.getNameAsString())
					|| imported.getNameAsString().startsWith("lombok."))
				|| context.compilationUnit()
					.findAll(AnnotationExpr.class)
					.stream()
					.anyMatch(annotation -> annotation.getNameAsString().startsWith("lombok."));
	}

	private static Optional<String> getterField(MethodDeclaration method) {
		if (!method.getParameters().isEmpty() || method.getBody().isEmpty()
				|| method.getBody().orElseThrow().getStatements().size() != 1
				|| !(method.getBody().orElseThrow().getStatement(0) instanceof ReturnStmt returned)
				|| returned.getExpression().isEmpty()) {
			return Optional.empty();
		}
		return fieldName(returned.getExpression().orElseThrow());
	}

	private static Optional<String> setterField(MethodDeclaration method) {
		if (method.getParameters().size() != 1 || !method.getType().isVoidType() || method.getBody().isEmpty()
				|| method.getBody().orElseThrow().getStatements().size() != 1
				|| !(method.getBody().orElseThrow().getStatement(0) instanceof ExpressionStmt statement)
				|| !(statement.getExpression() instanceof AssignExpr assignment)
				|| assignment.getOperator() != AssignExpr.Operator.ASSIGN
				|| !(assignment.getValue() instanceof NameExpr value)
				|| !value.getNameAsString().equals(method.getParameter(0).getNameAsString())) {
			return Optional.empty();
		}
		return fieldName(assignment.getTarget()).filter(field -> !field.equals(method.getParameter(0).getNameAsString())
				|| assignment.getTarget() instanceof FieldAccessExpr);
	}

	private static Optional<String> fieldName(Expression expression) {
		if (expression instanceof NameExpr name) {
			return Optional.of(name.getNameAsString());
		}
		if (expression instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) {
			return Optional.of(access.getNameAsString());
		}
		return Optional.empty();
	}

	private static Optional<String> matchingField(ClassOrInterfaceDeclaration owner, MethodDeclaration method,
			String name, boolean getter) {
		return owner.getFields()
			.stream()
			.filter(field -> field.getVariables()
				.stream()
				.anyMatch(variable -> variable.getNameAsString().equals(name)))
			.filter(field -> field.isStatic() == method.isStatic())
			.filter(field -> matchingType(field, method, name, getter))
			.map(field -> name)
			.findFirst();
	}

	private static boolean matchingType(FieldDeclaration field, MethodDeclaration method, String name, boolean getter) {
		Type fieldType = field.getVariables()
			.stream()
			.filter(variable -> variable.getNameAsString().equals(name))
			.findFirst()
			.orElseThrow()
			.getType();
		Type accessorType = getter ? method.getType() : method.getParameter(0).getType();
		return fieldType.equals(accessorType);
	}

}
