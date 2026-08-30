package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.body.EnumDeclaration;

/**
 * Reports declaration styles involving arrays, generics, finality, returns, and sealed
 * types.
 */
public final class ReportDeclarationStyleIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-declaration-style-issues";
	}

	@Override
	public String description() {
		return "Report configurable declaration styles for arrays, generics, finality, returns, and sealed types";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		arrays(context, findings);
		wildcards(context, findings);
		fields(context, findings);
		declarations(context, findings);
		returns(context, findings);
		sealedPermits(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void arrays(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(ArrayInitializerExpr.class)
			.stream()
			.filter(initializer -> !(initializer.getParentNode().orElse(null) instanceof ArrayCreationExpr))
			.filter(initializer -> AstSupport.ancestor(initializer, AnnotationExpr.class).isEmpty())
			.forEach(initializer -> findings
				.add(Finding.at(initializer, "Array creation has no explicit 'new' expression")));
		List<EnumDeclaration> enums = context.compilationUnit().findAll(EnumDeclaration.class);
		for (ArrayCreationExpr creation : context.compilationUnit().findAll(ArrayCreationExpr.class)) {
			if (creation.getInitializer().isEmpty()) {
				continue;
			}
			EnumDeclaration enumType = enums.stream()
				.filter(value -> value.getNameAsString().equals(creation.getElementType().asString()))
				.findFirst()
				.orElse(null);
			if (enumType == null) {
				continue;
			}
			Set<String> values = creation.getInitializer()
				.orElseThrow()
				.getValues()
				.stream()
				.map(Object::toString)
				.map(ReportDeclarationStyleIssuesTool::simple)
				.collect(java.util.stream.Collectors.toSet());
			if (enumType.getEntries().stream().map(entry -> entry.getNameAsString()).allMatch(values::contains)) {
				findings.add(Finding.at(creation, "Array of enum constants can be replaced with Enum.values()"));
			}
		}
	}

	private static void wildcards(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			Set<String> parameters = method.getTypeParameters()
				.stream()
				.map(parameter -> parameter.getNameAsString())
				.collect(java.util.stream.Collectors.toSet());
			for (Parameter parameter : method.getParameters()) {
				String spelling = parameter.getType().asString();
				if (parameters.stream()
					.anyMatch(type -> spelling.matches(".*<" + type + ">.*")
							&& !method.getType().asString().contains(type))) {
					findings.add(Finding.at(parameter, "Generic method parameter can use a bounded wildcard"));
				}
			}
		}
	}

	private static void fields(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			for (FieldDeclaration field : type.getFields()) {
				if (!field.getAnnotations().isEmpty()) {
					continue;
				}
				for (VariableDeclarator variable : field.getVariables()) {
					List<AssignExpr> assignments = type.findAll(AssignExpr.class)
						.stream()
						.filter(assignment -> targetName(assignment.getTarget())
							.filter(variable.getNameAsString()::equals)
							.isPresent())
						.toList();
					boolean initializedOnce = variable.getInitializer().isPresent() && assignments.isEmpty()
							|| variable.getInitializer().isEmpty() && !assignments.isEmpty()
									&& assignments.stream()
										.allMatch(assignment -> AstSupport
											.ancestor(assignment, ConstructorDeclaration.class)
											.isPresent());
					if (!field.isFinal() && initializedOnce) {
						findings.add(Finding.at(variable, "Field may be final"));
					}
					if (variable.getInitializer().isEmpty() && !assignments.isEmpty()
							&& assignments.stream().allMatch(assignment -> assignment.getValue().isLiteralExpr())
							&& assignments.stream()
								.map(assignment -> assignment.getValue().toString())
								.distinct()
								.count() == 1) {
						findings.add(Finding.at(variable, "Field assignment can be moved to the field initializer"));
					}
				}
			}
		}
	}

	private static Optional<String> targetName(Expression expression) {
		if (expression instanceof NameExpr name) {
			return Optional.of(name.getNameAsString());
		}
		if (expression instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) {
			return Optional.of(access.getNameAsString());
		}
		return Optional.empty();
	}

	private static void declarations(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(FieldDeclaration.class)
			.stream()
			.filter(field -> field.getVariables().size() > 1)
			.forEach(field -> findings.add(Finding.at(field, "Multiple variables in one declaration")));
		context.compilationUnit()
			.findAll(VariableDeclarationExpr.class)
			.stream()
			.filter(declaration -> declaration.getVariables().size() > 1)
			.forEach(declaration -> findings.add(Finding.at(declaration, "Multiple variables in one declaration")));
	}

	private static void returns(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(ReturnStmt.class)
			.stream()
			.filter(returned -> returned.getExpression().orElse(null) instanceof ThisExpr)
			.forEach(returned -> findings.add(Finding.at(returned, "Return of 'this'")));
	}

	private static void sealedPermits(InspectionContext context, List<Finding> findings) {
		List<ClassOrInterfaceDeclaration> types = context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class);
		for (ClassOrInterfaceDeclaration type : types) {
			if (!type.hasModifier(Modifier.Keyword.SEALED) || !type.getPermittedTypes().isEmpty()) {
				continue;
			}
			List<ClassOrInterfaceDeclaration> subclasses = types.stream()
				.filter(candidate -> candidate.getExtendedTypes()
					.stream()
					.anyMatch(parent -> parent.getNameAsString().equals(type.getNameAsString()))
						|| candidate.getImplementedTypes()
							.stream()
							.anyMatch(parent -> parent.getNameAsString().equals(type.getNameAsString())))
				.toList();
			if (!subclasses.isEmpty()) {
				findings
					.add(Finding.at(type, "Same-file subclasses are missing from the sealed type's permits clause"));
			}
		}
	}

	private static String simple(String value) {
		int dot = value.lastIndexOf('.');
		return dot < 0 ? value : value.substring(dot + 1);
	}

}
