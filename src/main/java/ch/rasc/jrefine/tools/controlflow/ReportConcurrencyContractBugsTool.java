package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reports source-local concurrency declarations whose runtime contracts do not agree. */
public final class ReportConcurrencyContractBugsTool implements InspectionTool {

	private static final Set<String> UPDATER_TYPES = Set.of("AtomicIntegerFieldUpdater", "AtomicLongFieldUpdater",
			"AtomicReferenceFieldUpdater");

	@Override
	public String id() {
		return "report-concurrency-contract-bugs";
	}

	@Override
	public String description() {
		return "Report broken publication, atomic-updater, initialization, and override contracts";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		Map<String, ClassOrInterfaceDeclaration> types = uniqueTypes(context);
		doubleCheckedLocking(context, findings);
		atomicFieldUpdaters(context, types, findings);
		staticInitializerSubclassReferences(types, findings);
		unsynchronizedOverrides(types, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void doubleCheckedLocking(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			for (FieldDeclaration field : type.getFields()) {
				if (field.isVolatile() || field.isFinal()) {
					continue;
				}
				for (VariableDeclarator variable : field.getVariables()) {
					String name = variable.getNameAsString();
					for (IfStmt outer : directDescendants(type, IfStmt.class)) {
						if (!nullCheck(context, type, outer.getCondition(), name, outer)) {
							continue;
						}
						SynchronizedStmt locked = outer.getThenStmt()
							.findAll(SynchronizedStmt.class)
							.stream()
							.filter(statement -> directlyWithin(statement, outer.getThenStmt(), type))
							.findFirst()
							.orElse(null);
						if (locked == null || !secondCheckAssigns(context, type, locked, name)) {
							continue;
						}
						findings.add(Finding.at(outer,
								"Double-checked locking publishes non-volatile field '" + name + "' unsafely"));
					}
				}
			}
		}
	}

	private static boolean secondCheckAssigns(InspectionContext context, ClassOrInterfaceDeclaration type,
			SynchronizedStmt locked, String field) {
		return locked.getBody()
			.findAll(IfStmt.class)
			.stream()
			.filter(statement -> directlyWithin(statement, locked.getBody(), type))
			.filter(statement -> nullCheck(context, type, statement.getCondition(), field, statement))
			.anyMatch(statement -> statement.getThenStmt()
				.findAll(AssignExpr.class)
				.stream()
				.filter(assignment -> directlyWithin(assignment, statement.getThenStmt(), type))
				.anyMatch(assignment -> fieldExpression(context, type, assignment.getTarget(), field, assignment)
						&& !(assignment.getValue() instanceof NullLiteralExpr)));
	}

	private static void atomicFieldUpdaters(InspectionContext context, Map<String, ClassOrInterfaceDeclaration> types,
			List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			String updater = updaterOwner(context, call);
			if (updater == null || !"newUpdater".equals(call.getNameAsString()) || call.getArguments().size() < 2
					|| !(call.getArgument(0) instanceof ClassExpr ownerClass)
					|| !(call.getArgument(call.getArguments().size() - 1) instanceof StringLiteralExpr fieldName)) {
				continue;
			}
			ClassOrInterfaceDeclaration owner = types.get(TypeLookup.simpleName(ownerClass.getType().asString()));
			if (owner == null) {
				continue;
			}
			VariableDeclarator target = owner.getFields()
				.stream()
				.flatMap(field -> field.getVariables().stream())
				.filter(variable -> variable.getNameAsString().equals(fieldName.asString()))
				.findFirst()
				.orElse(null);
			String problem = updaterProblem(updater, call, target);
			if (problem != null) {
				findings.add(Finding.at(call, "AtomicFieldUpdater declaration is inconsistent: " + problem));
			}
		}
	}

	private static String updaterOwner(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty()) {
			return null;
		}
		String spelling = call.getScope().orElseThrow().toString();
		return UPDATER_TYPES.stream()
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.util.concurrent.atomic",
					Set.of(type)))
			.findFirst()
			.orElse(null);
	}

	private static String updaterProblem(String updater, MethodCallExpr call, VariableDeclarator target) {
		if (target == null) {
			return "target field does not exist in the source-local class";
		}
		FieldDeclaration declaration = target.findAncestor(FieldDeclaration.class).orElseThrow();
		if (declaration.isStatic()) {
			return "target field must be an instance field";
		}
		if (!declaration.isVolatile()) {
			return "target field must be volatile";
		}
		String targetType = normalizedType(target.getType().asString());
		if ("AtomicIntegerFieldUpdater".equals(updater) && !"int".equals(targetType)) {
			return "integer updater target must have type int";
		}
		if ("AtomicLongFieldUpdater".equals(updater) && !"long".equals(targetType)) {
			return "long updater target must have type long";
		}
		if (!"AtomicReferenceFieldUpdater".equals(updater)) {
			return null;
		}
		if (call.getArguments().size() != 3 || !(call.getArgument(1) instanceof ClassExpr valueClass)) {
			return "reference updater must declare its value class";
		}
		return normalizedType(valueClass.getType().asString()).equals(targetType) ? null
				: "reference updater value class does not match target field type";
	}

	private static void staticInitializerSubclassReferences(Map<String, ClassOrInterfaceDeclaration> types,
			List<Finding> findings) {
		for (ClassOrInterfaceDeclaration parent : types.values()) {
			Map<String, ClassOrInterfaceDeclaration> subclasses = types.values()
				.stream()
				.filter(candidate -> candidate != parent)
				.filter(candidate -> ancestors(candidate, types, new HashSet<>()).contains(parent))
				.collect(java.util.stream.Collectors.toMap(ClassOrInterfaceDeclaration::getNameAsString,
						candidate -> candidate));
			if (subclasses.isEmpty()) {
				continue;
			}
			for (FieldDeclaration field : parent.getFields()) {
				if (!field.isStatic()) {
					continue;
				}
				for (VariableDeclarator variable : field.getVariables()) {
					variable.getInitializer()
						.ifPresent(initializer -> subclassReferences(initializer, parent, subclasses, findings));
				}
			}
			parent.getMembers()
				.stream()
				.filter(InitializerDeclaration.class::isInstance)
				.map(InitializerDeclaration.class::cast)
				.filter(InitializerDeclaration::isStatic)
				.forEach(initializer -> subclassReferences(initializer.getBody(), parent, subclasses, findings));
		}
	}

	private static void subclassReferences(Node root, ClassOrInterfaceDeclaration owner,
			Map<String, ClassOrInterfaceDeclaration> subclasses, List<Finding> findings) {
		root.findAll(ObjectCreationExpr.class)
			.stream()
			.filter(reference -> directlyWithin(reference, root, owner))
			.filter(reference -> subclasses.containsKey(TypeLookup.simpleName(reference.getType().asString())))
			.forEach(reference -> findings
				.add(Finding.at(reference, "Static initializer references source-local subclass '"
						+ TypeLookup.simpleName(reference.getType().asString()) + "'")));
		root.findAll(FieldAccessExpr.class)
			.stream()
			.filter(reference -> directlyWithin(reference, root, owner))
			.filter(reference -> subclasses.containsKey(reference.getScope().toString()))
			.filter(reference -> staticFieldRequiresInitialization(subclasses.get(reference.getScope().toString()),
					reference.getNameAsString()))
			.forEach(reference -> findings.add(Finding.at(reference,
					"Static initializer references source-local subclass '" + reference.getScope() + "'")));
		root.findAll(MethodCallExpr.class)
			.stream()
			.filter(reference -> directlyWithin(reference, root, owner))
			.filter(reference -> reference.getScope()
				.filter(NameExpr.class::isInstance)
				.map(NameExpr.class::cast)
				.filter(scope -> subclasses.containsKey(scope.getNameAsString()))
				.isPresent())
			.filter(reference -> reference.getScope()
				.map(Object::toString)
				.map(subclasses::get)
				.filter(subclass -> subclass.getMethodsByName(reference.getNameAsString())
					.stream()
					.anyMatch(method -> method.isStatic()
							&& method.getParameters().size() == reference.getArguments().size()))
				.isPresent())
			.forEach(reference -> findings
				.add(Finding.at(reference, "Static initializer references source-local subclass '"
						+ reference.getScope().orElseThrow() + "'")));
	}

	private static boolean staticFieldRequiresInitialization(ClassOrInterfaceDeclaration type, String name) {
		FieldDeclaration field = type.getFields()
			.stream()
			.filter(candidate -> candidate.isStatic()
					&& candidate.getVariables().stream().anyMatch(variable -> variable.getNameAsString().equals(name)))
			.findFirst()
			.orElse(null);
		if (field == null) {
			return false;
		}
		return !field.isFinal() || !(field.getElementType().isPrimitiveType()
				|| "String".equals(normalizedType(field.getElementType().asString())));
	}

	private static void unsynchronizedOverrides(Map<String, ClassOrInterfaceDeclaration> types,
			List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : types.values()) {
			Set<ClassOrInterfaceDeclaration> parents = ancestors(type, types, new HashSet<>());
			for (MethodDeclaration method : directMethods(type)) {
				if (method.isSynchronized() || method.isStatic() || method.isPrivate()) {
					continue;
				}
				parents.stream()
					.flatMap(parent -> directMethods(parent).stream())
					.filter(parent -> parent.isSynchronized() && !parent.isStatic() && !parent.isPrivate())
					.filter(parent -> sameSignature(method, parent))
					.findFirst()
					.ifPresent(parent -> findings.add(Finding.at(method,
							"Unsynchronized method overrides synchronized method in source-local parent")));
			}
		}
	}

	private static boolean nullCheck(InspectionContext context, ClassOrInterfaceDeclaration owner,
			Expression expression, String field, Node use) {
		if (!(expression instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.EQUALS) {
			return false;
		}
		return binary.getLeft() instanceof NullLiteralExpr
				&& fieldExpression(context, owner, binary.getRight(), field, use)
				|| binary.getRight() instanceof NullLiteralExpr
						&& fieldExpression(context, owner, binary.getLeft(), field, use);
	}

	private static boolean fieldExpression(InspectionContext context, ClassOrInterfaceDeclaration owner,
			Expression expression, String field, Node use) {
		if (expression instanceof NameExpr name && name.getNameAsString().equals(field)) {
			return !TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), field, use);
		}
		return expression instanceof FieldAccessExpr access && access.getNameAsString().equals(field)
				&& (access.getScope() instanceof ThisExpr
						|| access.getScope().toString().equals(owner.getNameAsString()));
	}

	private static <T extends Node> List<T> directDescendants(ClassOrInterfaceDeclaration type, Class<T> nodeType) {
		return type.findAll(nodeType)
			.stream()
			.filter(node -> node.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null) == type)
			.toList();
	}

	private static boolean directlyWithin(Node node, Node root, ClassOrInterfaceDeclaration owner) {
		Node current = node;
		while (current != root) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof LambdaExpr
					|| parent instanceof TypeDeclaration<?> && parent != owner || parent instanceof MethodDeclaration) {
				return false;
			}
			current = parent;
		}
		return true;
	}

	private static Set<ClassOrInterfaceDeclaration> ancestors(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited) {
		LinkedHashSet<ClassOrInterfaceDeclaration> result = new LinkedHashSet<>();
		ArrayList<ClassOrInterfaceType> references = new ArrayList<>(type.getExtendedTypes());
		references.addAll(type.getImplementedTypes());
		for (ClassOrInterfaceType reference : references) {
			String name = TypeLookup.simpleName(reference.asString());
			if (!visited.add(name)) {
				continue;
			}
			ClassOrInterfaceDeclaration parent = types.get(name);
			if (parent != null) {
				result.add(parent);
				result.addAll(ancestors(parent, types, visited));
			}
		}
		return result;
	}

	private static List<MethodDeclaration> directMethods(ClassOrInterfaceDeclaration type) {
		return type.getMembers()
			.stream()
			.filter(MethodDeclaration.class::isInstance)
			.map(MethodDeclaration.class::cast)
			.toList();
	}

	private static boolean sameSignature(MethodDeclaration left, MethodDeclaration right) {
		if (!left.getNameAsString().equals(right.getNameAsString())
				|| left.getParameters().size() != right.getParameters().size()) {
			return false;
		}
		for (int index = 0; index < left.getParameters().size(); index++) {
			String leftType = signatureType(left.getParameter(index).getType().asString())
					+ (left.getParameter(index).isVarArgs() ? "[]" : "");
			String rightType = signatureType(right.getParameter(index).getType().asString())
					+ (right.getParameter(index).isVarArgs() ? "[]" : "");
			if (!leftType.equals(rightType)) {
				return false;
			}
		}
		return true;
	}

	private static String normalizedType(String type) {
		String value = eraseTypeArguments(type).replace(" ", "");
		int dot = value.lastIndexOf('.');
		return dot < 0 ? value : value.substring(dot + 1);
	}

	private static String signatureType(String type) {
		return eraseTypeArguments(type).replace(" ", "");
	}

	private static String eraseTypeArguments(String type) {
		StringBuilder result = new StringBuilder();
		int depth = 0;
		for (int index = 0; index < type.length(); index++) {
			char character = type.charAt(index);
			if (character == '<') {
				depth++;
			}
			else if (character == '>') {
				depth--;
			}
			else if (depth == 0) {
				result.append(character);
			}
		}
		return result.toString();
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

}
