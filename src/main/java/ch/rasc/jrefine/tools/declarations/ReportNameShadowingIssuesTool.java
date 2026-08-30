package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reports lexical name shadowing that makes member and local references ambiguous to
 * readers.
 */
public final class ReportNameShadowingIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-name-shadowing-issues";
	}

	@Override
	public String description() {
		return "Report name shadowing and ambiguous inherited member access";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		localVariables(context, findings);
		parameters(context, findings);
		patternVariables(context, findings);
		nestedFields(context, findings);
		subclassFields(context, findings);
		anonymousFields(context, findings);
		typeParameters(context, findings);
		ambiguousInheritedAccess(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void localVariables(InspectionContext context, List<Finding> findings) {
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (AstSupport.ancestor(variable, FieldDeclaration.class).isPresent()) {
				continue;
			}
			String name = variable.getNameAsString();
			if (lexicalFieldNames(variable).contains(name) && !qualifiedThisReference(variable, name)) {
				findings.add(Finding.at(variable, "Local variable shadows field '" + name + "'"));
			}
		}
	}

	private static void parameters(InspectionContext context, List<Finding> findings) {
		for (Parameter parameter : context.compilationUnit().findAll(Parameter.class)) {
			String name = parameter.getNameAsString();
			if (!lexicalFieldNames(parameter).contains(name) || qualifiedThisReference(parameter, name)
					|| forwardedParameter(parameter)) {
				continue;
			}
			String kind = parameter.getParentNode().filter(LambdaExpr.class::isInstance).isPresent()
					? "Lambda parameter" : "Parameter";
			findings.add(Finding.at(parameter, kind + " shadows field '" + name + "'"));
		}
	}

	private static void patternVariables(InspectionContext context, List<Finding> findings) {
		for (TypePatternExpr pattern : context.compilationUnit().findAll(TypePatternExpr.class)) {
			String name = pattern.getNameAsString();
			if (lexicalFieldNames(pattern).contains(name) && !qualifiedThisReference(pattern, name)) {
				findings.add(Finding.at(pattern, "Pattern variable shadows field '" + name + "'"));
			}
		}
	}

	private static Set<String> lexicalFieldNames(Node node) {
		Optional<Node> current = node.getParentNode();
		while (current.isPresent()) {
			Node parent = current.orElseThrow();
			if (parent instanceof TypeDeclaration<?> type) {
				boolean staticContext = staticContextBefore(node, type);
				return fieldNames(
						type.getFields().stream().filter(field -> !staticContext || field.isStatic()).toList());
			}
			if (parent instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
				return fieldNames(creation.getAnonymousClassBody()
					.orElseThrow()
					.stream()
					.filter(FieldDeclaration.class::isInstance)
					.map(FieldDeclaration.class::cast)
					.toList());
			}
			current = parent.getParentNode();
		}
		return Set.of();
	}

	private static boolean staticContextBefore(Node node, TypeDeclaration<?> owner) {
		Optional<Node> current = Optional.of(node);
		while (current.isPresent()) {
			Node value = current.orElseThrow();
			if (value == owner) {
				return false;
			}
			if (value instanceof com.github.javaparser.ast.body.MethodDeclaration method) {
				return method.isStatic();
			}
			if (value instanceof com.github.javaparser.ast.body.ConstructorDeclaration) {
				return false;
			}
			if (value instanceof InitializerDeclaration initializer) {
				return initializer.isStatic();
			}
			if (value instanceof FieldDeclaration field) {
				return field.isStatic();
			}
			current = value.getParentNode();
		}
		return false;
	}

	private static Set<String> fieldNames(List<FieldDeclaration> fields) {
		return fields.stream()
			.flatMap(field -> field.getVariables().stream())
			.map(VariableDeclarator::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
	}

	private static boolean qualifiedThisReference(Node declaration, String name) {
		Node boundary = assignmentBoundary(declaration).orElse(null);
		if (boundary == null) {
			return false;
		}
		return boundary.findAll(FieldAccessExpr.class)
			.stream()
			.filter(access -> access.getScope().isThisExpr())
			.filter(access -> access.getNameAsString().equals(name))
			.anyMatch(access -> assignmentBoundary(access).orElse(null) == boundary);
	}

	private static boolean forwardedParameter(Parameter parameter) {
		ConstructorDeclaration constructor = AstSupport.ancestor(parameter, ConstructorDeclaration.class).orElse(null);
		if (constructor != null && !constructor.getBody().getStatements().isEmpty()
				&& constructor.getBody().getStatement(0) instanceof ExplicitConstructorInvocationStmt invocation
				&& invocation.isThis()
				&& invocation.getArguments()
					.stream()
					.anyMatch(argument -> references(argument, parameter.getNameAsString()))) {
			return true;
		}
		com.github.javaparser.ast.body.MethodDeclaration method = AstSupport
			.ancestor(parameter, com.github.javaparser.ast.body.MethodDeclaration.class)
			.orElse(null);
		TypeDeclaration<?> owner = method == null ? null
				: AstSupport.ancestor(method, TypeDeclaration.class).orElse(null);
		if (method == null || owner == null || method.getBody().isEmpty()) {
			return false;
		}
		return method.getBody()
			.orElseThrow()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> directlyWithin(call, method))
			.filter(call -> call.getNameAsString().equals(method.getNameAsString()))
			.filter(call -> call.getScope().isEmpty() || call.getScope().orElseThrow().isThisExpr())
			.filter(call -> owner.getMethodsByName(method.getNameAsString())
				.stream()
				.filter(overload -> overload != method)
				.anyMatch(overload -> overload.getParameters().size() == call.getArguments().size()))
			.flatMap(call -> call.getArguments().stream())
			.anyMatch(argument -> references(argument, parameter.getNameAsString()));
	}

	private static boolean references(Node node, String name) {
		return node.findAll(NameExpr.class).stream().anyMatch(reference -> reference.getNameAsString().equals(name));
	}

	private static boolean directlyWithin(Node node, CallableDeclaration<?> callable) {
		Optional<Node> current = node.getParentNode();
		while (current.isPresent()) {
			Node parent = current.orElseThrow();
			if (parent == callable) {
				return true;
			}
			if (parent instanceof CallableDeclaration<?> || parent instanceof LambdaExpr) {
				return false;
			}
			current = parent.getParentNode();
		}
		return false;
	}

	private static Optional<Node> assignmentBoundary(Node node) {
		Optional<Node> current = Optional.of(node);
		while (current.isPresent()) {
			Node value = current.orElseThrow();
			if (value instanceof CallableDeclaration<?> || value instanceof LambdaExpr
					|| value instanceof InitializerDeclaration) {
				return Optional.of(value);
			}
			current = value.getParentNode();
		}
		return Optional.empty();
	}

	private static void nestedFields(InspectionContext context, List<Finding> findings) {
		for (TypeDeclaration<?> nested : context.compilationUnit().findAll(TypeDeclaration.class)) {
			HashSet<String> outerNames = new HashSet<>();
			Optional<Node> current = nested.getParentNode();
			while (current.isPresent()) {
				Node parent = current.orElseThrow();
				if (parent instanceof TypeDeclaration<?> outer) {
					outerNames.addAll(fieldNames(outer.getFields()));
				}
				current = parent.getParentNode();
			}
			if (outerNames.isEmpty()) {
				continue;
			}
			nested.getFields()
				.stream()
				.flatMap(field -> field.getVariables().stream())
				.filter(variable -> outerNames.contains(variable.getNameAsString()))
				.forEach(variable -> findings.add(Finding.at(variable,
						"Inner-class field hides outer field '" + variable.getNameAsString() + "'")));
		}
	}

	private static void subclassFields(InspectionContext context, List<Finding> findings) {
		Map<String, ClassOrInterfaceDeclaration> types = uniqueTypes(context);
		for (ClassOrInterfaceDeclaration subtype : context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)) {
			if (types.get(subtype.getNameAsString()) != subtype) {
				continue;
			}
			Set<String> inherited = inheritedFieldNames(subtype, types, new HashSet<>());
			subtype.getFields()
				.stream()
				.flatMap(field -> field.getVariables().stream())
				.filter(variable -> inherited.contains(variable.getNameAsString()))
				.forEach(variable -> findings.add(Finding.at(variable,
						"Subclass field hides superclass field '" + variable.getNameAsString() + "'")));
		}
	}

	private static Set<String> inheritedFieldNames(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited) {
		HashSet<String> result = new HashSet<>();
		for (ClassOrInterfaceType reference : type.getExtendedTypes()) {
			if (!unqualified(reference.asString())) {
				continue;
			}
			String name = simpleType(reference.asString());
			if (!visited.add(name)) {
				continue;
			}
			ClassOrInterfaceDeclaration parent = types.get(name);
			if (parent == null) {
				continue;
			}
			parent.getFields()
				.stream()
				.flatMap(field -> field.getVariables().stream())
				.map(VariableDeclarator::getNameAsString)
				.forEach(result::add);
			result.addAll(inheritedFieldNames(parent, types, visited));
		}
		return result;
	}

	private static void anonymousFields(InspectionContext context, List<Finding> findings) {
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			if (creation.getAnonymousClassBody().isEmpty()) {
				continue;
			}
			creation.getAnonymousClassBody()
				.orElseThrow()
				.stream()
				.filter(FieldDeclaration.class::isInstance)
				.map(FieldDeclaration.class::cast)
				.flatMap(field -> field.getVariables().stream())
				.filter(variable -> TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(),
						variable.getNameAsString(), creation))
				.forEach(variable -> findings.add(Finding.at(variable, "Anonymous-class field hides variable '"
						+ variable.getNameAsString() + "' from the containing method")));
		}
	}

	private static void typeParameters(InspectionContext context, List<Finding> findings) {
		Set<String> visibleTypes = new HashSet<>();
		context.compilationUnit()
			.findAll(TypeDeclaration.class)
			.stream()
			.map(TypeDeclaration::getNameAsString)
			.forEach(visibleTypes::add);
		context.compilationUnit()
			.getImports()
			.stream()
			.filter(imported -> !imported.isAsterisk() && !imported.isStatic())
			.map(imported -> imported.getName().getIdentifier())
			.forEach(visibleTypes::add);
		context.compilationUnit()
			.findAll(TypeParameter.class)
			.stream()
			.filter(parameter -> visibleTypes.contains(parameter.getNameAsString()))
			.forEach(parameter -> findings
				.add(Finding.at(parameter, "Type parameter hides visible type '" + parameter.getNameAsString() + "'")));
	}

	private static void ambiguousInheritedAccess(InspectionContext context, List<Finding> findings) {
		Map<String, ClassOrInterfaceDeclaration> types = uniqueTypes(context);
		for (ClassOrInterfaceDeclaration inner : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			TypeDeclaration<?> outer = inner.findAncestor(TypeDeclaration.class).orElse(null);
			ClassOrInterfaceDeclaration parent = directParent(inner, types);
			if (inner.isInterface() || outer == null || parent == null || parent.isInterface()) {
				continue;
			}
			boolean outerInstance = !inner.isStatic()
					&& !(outer instanceof ClassOrInterfaceDeclaration outerClass && outerClass.isInterface())
					&& !staticContextBefore(inner, outer);
			inspectAmbiguousAccess(context, inner, parent, outer, inner.getFields(), inner.getMethods(), outerInstance,
					findings);
		}
		for (ObjectCreationExpr creation : context.compilationUnit().findAll(ObjectCreationExpr.class)) {
			if (creation.getAnonymousClassBody().isEmpty()) {
				continue;
			}
			TypeDeclaration<?> outer = creation.findAncestor(TypeDeclaration.class).orElse(null);
			ClassOrInterfaceDeclaration parent = directParent(creation, types);
			if (outer == null || parent == null || parent.isInterface()) {
				continue;
			}
			List<FieldDeclaration> fields = creation.getAnonymousClassBody()
				.orElseThrow()
				.stream()
				.filter(FieldDeclaration.class::isInstance)
				.map(FieldDeclaration.class::cast)
				.toList();
			List<MethodDeclaration> methods = creation.getAnonymousClassBody()
				.orElseThrow()
				.stream()
				.filter(MethodDeclaration.class::isInstance)
				.map(MethodDeclaration.class::cast)
				.toList();
			boolean outerInstance = !(outer instanceof ClassOrInterfaceDeclaration outerClass
					&& outerClass.isInterface()) && !staticContextBefore(creation, outer);
			inspectAmbiguousAccess(context, creation, parent, outer, fields, methods, outerInstance, findings);
		}
	}

	private static void inspectAmbiguousAccess(InspectionContext context, Node boundary,
			ClassOrInterfaceDeclaration parent, TypeDeclaration<?> outer, List<FieldDeclaration> ownFields,
			List<MethodDeclaration> ownMethods, boolean outerInstance, List<Finding> findings) {
		Set<String> inheritedFields = parent.getFields()
			.stream()
			.filter(field -> !field.isPrivate() && !field.isStatic())
			.flatMap(field -> field.getVariables().stream())
			.map(VariableDeclarator::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		Set<String> declaredFields = fieldNames(ownFields);
		boundary.findAll(NameExpr.class)
			.stream()
			.filter(reference -> directlyWithin(reference, boundary))
			.filter(reference -> inheritedFields.contains(reference.getNameAsString()))
			.filter(reference -> !declaredFields.contains(reference.getNameAsString()))
			.filter(reference -> !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(),
					reference.getNameAsString(), reference))
			.filter(reference -> surroundingValue(context, reference, outer, outerInstance))
			.forEach(reference -> findings.add(Finding.at(reference,
					"Inherited field access looks like access to surrounding '" + reference.getNameAsString() + "'")));

		boundary.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> directlyWithin(call, boundary))
			.filter(call -> call.getScope().isEmpty() && call.getArguments().isEmpty()
					&& call.getTypeArguments().isEmpty())
			.filter(call -> ownMethods.stream()
				.noneMatch(method -> method.getNameAsString().equals(call.getNameAsString())
						&& method.getParameters().isEmpty()))
			.filter(call -> inheritedNoArgMethod(parent, call.getNameAsString()))
			.filter(call -> surroundingNoArgMethod(outer, call.getNameAsString(), outerInstance))
			.forEach(call -> findings.add(Finding.at(call,
					"Inherited method call looks like call to surrounding '" + call.getNameAsString() + "()'")));
	}

	private static boolean surroundingValue(InspectionContext context, NameExpr reference, TypeDeclaration<?> outer,
			boolean outerInstance) {
		String name = reference.getNameAsString();
		boolean captured = TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name,
				reference);
		if (captured) {
			return true;
		}
		return outer.getFields()
			.stream()
			.filter(field -> field.isStatic() || outerInstance)
			.flatMap(field -> field.getVariables().stream())
			.anyMatch(variable -> variable.getNameAsString().equals(name));
	}

	private static boolean inheritedNoArgMethod(ClassOrInterfaceDeclaration parent, String name) {
		return parent.getMethodsByName(name)
			.stream()
			.filter(method -> method.getParameters().isEmpty())
			.filter(method -> !method.isPrivate() && !method.isStatic() && !method.isAbstract())
			.count() == 1;
	}

	private static boolean surroundingNoArgMethod(TypeDeclaration<?> outer, String name, boolean outerInstance) {
		return outer.getMethodsByName(name)
			.stream()
			.anyMatch(method -> method.getParameters().isEmpty() && (method.isStatic() || outerInstance));
	}

	private static boolean directlyWithin(Node node, Node boundary) {
		Optional<Node> current = node.getParentNode();
		while (current.isPresent()) {
			Node value = current.orElseThrow();
			if (value == boundary) {
				return true;
			}
			if (value instanceof TypeDeclaration<?>
					|| value instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
				return false;
			}
			current = value.getParentNode();
		}
		return false;
	}

	private static ClassOrInterfaceDeclaration directParent(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types) {
		if (type.getExtendedTypes().size() != 1 || type.getExtendedTypes().get(0).getScope().isPresent()) {
			return null;
		}
		return types.get(type.getExtendedTypes().get(0).getNameAsString());
	}

	private static ClassOrInterfaceDeclaration directParent(ObjectCreationExpr creation,
			Map<String, ClassOrInterfaceDeclaration> types) {
		if (creation.getType().getScope().isPresent()) {
			return null;
		}
		return types.get(creation.getType().getNameAsString());
	}

	private static Map<String, ClassOrInterfaceDeclaration> uniqueTypes(InspectionContext context) {
		HashMap<String, List<ClassOrInterfaceDeclaration>> grouped = new HashMap<>();
		context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.forEach(type -> grouped.computeIfAbsent(type.getNameAsString(), ignored -> new ArrayList<>()).add(type));
		HashMap<String, ClassOrInterfaceDeclaration> result = new HashMap<>();
		grouped.forEach((name, declarations) -> {
			if (declarations.size() == 1) {
				result.put(name, declarations.getFirst());
			}
		});
		return result;
	}

	private static String simpleType(String type) {
		String value = type;
		int generic = value.indexOf('<');
		if (generic >= 0) {
			value = value.substring(0, generic);
		}
		int dot = value.lastIndexOf('.');
		return dot < 0 ? value : value.substring(dot + 1);
	}

	private static boolean unqualified(String type) {
		String value = type;
		int generic = value.indexOf('<');
		if (generic >= 0) {
			value = value.substring(0, generic);
		}
		return !value.contains(".");
	}

}
