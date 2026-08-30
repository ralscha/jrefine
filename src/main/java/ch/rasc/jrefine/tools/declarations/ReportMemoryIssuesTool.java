package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.LocalClassDeclarationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reports source shapes that retain outer objects or create avoidable long-lived
 * allocations.
 */
public final class ReportMemoryIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-memory-issues";
	}

	@Override
	public String description() {
		return "Report retaining inner classes, explicit GC, builder fields, and zero-length array allocation";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		builderFields(context, findings);
		garbageCollection(context, findings);
		staticClassCandidates(context, findings);
		returnedInnerInstances(context, findings);
		zeroLengthArrays(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void builderFields(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(FieldDeclaration.class)
			.stream()
			.filter(field -> Set.of("StringBuffer", "StringBuilder")
				.contains(simple(field.getElementType().asString())))
			.forEach(field -> findings
				.add(Finding.at(field, "StringBuilder or StringBuffer field may retain excess capacity")));
	}

	private static void garbageCollection(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"gc".equals(call.getNameAsString()) || !call.getArguments().isEmpty() || call.getScope().isEmpty()) {
				continue;
			}
			Expression scope = call.getScope().orElseThrow();
			boolean system = Set.of("System", "java.lang.System").contains(scope.toString());
			boolean runtime = scope instanceof MethodCallExpr receiver
					&& "getRuntime".equals(receiver.getNameAsString())
					&& receiver.getScope()
						.filter(value -> Set.of("Runtime", "java.lang.Runtime").contains(value.toString()))
						.isPresent();
			if (!runtime && scope instanceof NameExpr) {
				runtime = TypeLookup.visibleType(context.compilationUnit(), scope, call)
					.map(ReportMemoryIssuesTool::simple)
					.filter(type -> "Runtime".equals(type))
					.isPresent();
			}
			if (system || runtime) {
				findings.add(Finding.at(call, "Call to System.gc() or Runtime.gc()"));
			}
		}
	}

	private static void staticClassCandidates(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration nested : context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)) {
			ClassOrInterfaceDeclaration outer = nested.getParentNode()
				.filter(ClassOrInterfaceDeclaration.class::isInstance)
				.map(ClassOrInterfaceDeclaration.class::cast)
				.orElse(null);
			if (outer == null || outer.isInterface() || nested.isStatic()) {
				continue;
			}
			if (independent(nested, outer)) {
				findings.add(Finding.at(nested, "Inner class may be static"));
			}
		}
	}

	private static boolean independent(Node node, ClassOrInterfaceDeclaration outer) {
		if (node.findAll(ThisExpr.class)
			.stream()
			.anyMatch(expression -> expression.getTypeName()
				.filter(name -> name.asString().equals(outer.getNameAsString()))
				.isPresent())) {
			return false;
		}
		Set<String> fields = outer.getFields()
			.stream()
			.filter(field -> !field.isStatic())
			.flatMap(field -> field.getVariables().stream())
			.map(variable -> variable.getNameAsString())
			.collect(java.util.stream.Collectors.toSet());
		if (node.findAll(NameExpr.class).stream().anyMatch(name -> fields.contains(name.getNameAsString()))) {
			return false;
		}
		Set<String> methods = outer.getMethods()
			.stream()
			.filter(method -> !method.isStatic())
			.map(MethodDeclaration::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		return node.findAll(MethodCallExpr.class)
			.stream()
			.noneMatch(call -> call.getScope().isEmpty() && methods.contains(call.getNameAsString()));
	}

	private static void returnedInnerInstances(InspectionContext context, List<Finding> findings) {
		Set<String> local = context.compilationUnit()
			.findAll(LocalClassDeclarationStmt.class)
			.stream()
			.map(statement -> statement.getClassDeclaration().getNameAsString())
			.collect(java.util.stream.Collectors.toSet());
		Set<String> inner = context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> type.getParentNode().filter(ClassOrInterfaceDeclaration.class::isInstance).isPresent()
					&& !type.isStatic())
			.map(ClassOrInterfaceDeclaration::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		for (ReturnStmt returned : context.compilationUnit().findAll(ReturnStmt.class)) {
			if (!(returned.getExpression().orElse(null) instanceof ObjectCreationExpr creation)) {
				continue;
			}
			if (creation.getAnonymousClassBody().isPresent() || local.contains(creation.getType().getNameAsString())
					|| inner.contains(creation.getType().getNameAsString())) {
				findings.add(Finding.at(returned,
						"Return of anonymous, local, or inner class instance may retain its owner"));
			}
		}
	}

	private static void zeroLengthArrays(InspectionContext context, List<Finding> findings) {
		Set<ArrayCreationExpr> constants = Collections.newSetFromMap(new IdentityHashMap<>());
		HashSet<String> elementConstants = new HashSet<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			type.getFields()
				.stream()
				.filter(field -> field.isStatic() && field.isFinal())
				.flatMap(field -> field.getVariables().stream())
				.map(variable -> variable.getInitializer().orElse(null))
				.filter(ArrayCreationExpr.class::isInstance)
				.map(ArrayCreationExpr.class::cast)
				.filter(ReportMemoryIssuesTool::zeroLength)
				.forEach(array -> {
					constants.add(array);
					if (simple(array.getElementType().asString()).equals(type.getNameAsString())) {
						elementConstants.add(type.getNameAsString());
					}
				});
		}
		context.compilationUnit()
			.findAll(ArrayCreationExpr.class)
			.stream()
			.filter(ReportMemoryIssuesTool::zeroLength)
			.forEach(array -> {
				if (!constants.contains(array)
						&& elementConstants.contains(simple(array.getElementType().asString()))) {
					findings.add(Finding.at(array,
							"Unnecessary zero-length array usage; an element-type constant is available"));
				}
			});
	}

	private static boolean zeroLength(ArrayCreationExpr array) {
		if (array.getInitializer().filter(initializer -> initializer.getValues().isEmpty()).isPresent()) {
			return true;
		}
		return array.getLevels().size() == 1 && array.getLevels()
			.get(0)
			.getDimension()
			.filter(expression -> expression.isIntegerLiteralExpr()
					&& expression.asIntegerLiteralExpr().asNumber().intValue() == 0)
			.isPresent();
	}

	private static String simple(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		while (currentType.endsWith("[]")) {
			currentType = currentType.substring(0, currentType.length() - 2);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

}
