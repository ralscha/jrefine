package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Reports source-local state that Java serialization cannot safely reconstruct. */
public final class ReportSerializationStateBugsTool implements InspectionTool {

	@Override
	public String id() {
		return "report-serialization-state-bugs";
	}

	@Override
	public String description() {
		return "Report non-serializable fields, ignored record hooks, and state not restored on deserialization";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (!type.isInterface() && serializable(context, type, new HashSet<>())) {
				if (!externalizable(context, type, new HashSet<>())) {
					inspectClass(context, type, findings);
				}
			}
		}
		for (RecordDeclaration record : context.compilationUnit().findAll(RecordDeclaration.class)) {
			if (serializable(context, record, new HashSet<>())) {
				ignoredRecordMembers(context, record, findings);
			}
		}
		httpSessionBindings(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void inspectClass(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		MethodDeclaration readObject = type.getMethodsByName("readObject")
			.stream()
			.filter(method -> validReadObject(context, method))
			.findFirst()
			.orElse(null);
		Set<String> assigned = readObject == null ? Set.of() : assignedFields(context, readObject);
		boolean defaultState = readObject != null
				&& callsInputMethod(readObject, Set.of("defaultReadObject", "readFields"));
		boolean delegated = readObject != null && callsRestorationHelper(readObject);

		for (FieldDeclaration field : type.getFields()) {
			if (field.isStatic()) {
				continue;
			}
			for (VariableDeclarator variable : field.getVariables()) {
				if (!field.isTransient()
						&& definitelyNonSerializableLocalType(context, variable.getType().asString())) {
					findings.add(Finding.at(variable, "Serializable class stores source-local non-serializable field '"
							+ variable.getNameAsString() + "'"));
				}
				if (field.isTransient() && nonDefaultInitializer(variable)
						&& !assigned.contains(variable.getNameAsString()) && !delegated) {
					findings.add(
							Finding.at(variable, "Transient field initializer is not restored during deserialization"));
				}
				if (readObject != null && !field.isTransient() && !defaultState && !delegated
						&& !assigned.contains(variable.getNameAsString())) {
					findings.add(Finding.at(variable, "Custom readObject() does not restore this serialized field"));
				}
			}
		}
		unconstructableAncestor(context, type, findings);
	}

	private static void ignoredRecordMembers(InspectionContext context, RecordDeclaration record,
			List<Finding> findings) {
		record.getFields()
			.stream()
			.flatMap(field -> field.getVariables().stream())
			.filter(variable -> "serialPersistentFields".equals(variable.getNameAsString()))
			.forEach(variable -> findings
				.add(Finding.at(variable, "Serializable record ignores serialPersistentFields")));
		for (MethodDeclaration method : record.getMethods()) {
			boolean ignored = "readObjectNoData".equals(method.getNameAsString()) && method.getParameters().isEmpty()
					|| "readObject".equals(method.getNameAsString())
							&& ioParameter(context, method, "ObjectInputStream")
					|| "writeObject".equals(method.getNameAsString())
							&& ioParameter(context, method, "ObjectOutputStream");
			if (ignored) {
				findings.add(Finding.at(method, "Serializable record ignores this custom serialization hook"));
			}
		}
	}

	private static void unconstructableAncestor(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		ClassOrInterfaceDeclaration parent = firstNonSerializableParent(context, type, new HashSet<>());
		if (parent == null || accessibleNoArgConstructor(parent)) {
			return;
		}
		findings.add(Finding.at(type, "First non-serializable superclass has no accessible no-argument constructor"));
	}

	private static ClassOrInterfaceDeclaration firstNonSerializableParent(InspectionContext context,
			ClassOrInterfaceDeclaration type, Set<String> visiting) {
		if (type.getExtendedTypes().isEmpty() || !visiting.add(type.getNameAsString())) {
			return null;
		}
		TypeDeclaration<?> declaration = localType(context, type.getExtendedTypes().get(0).asString()).orElse(null);
		if (!(declaration instanceof ClassOrInterfaceDeclaration parent) || parent.isInterface()) {
			return null;
		}
		if (serializable(context, parent, new HashSet<>())) {
			return firstNonSerializableParent(context, parent, visiting);
		}
		return definitelyNonSerializable(context, parent, new HashSet<>()) ? parent : null;
	}

	private static boolean accessibleNoArgConstructor(ClassOrInterfaceDeclaration type) {
		List<ConstructorDeclaration> constructors = type.getConstructors();
		if (constructors.isEmpty()) {
			return !type.isPrivate();
		}
		return constructors.stream()
			.anyMatch(constructor -> constructor.getParameters().isEmpty() && !constructor.isPrivate());
	}

	private static Set<String> assignedFields(InspectionContext context, MethodDeclaration method) {
		HashSet<String> result = new HashSet<>();
		for (AssignExpr assignment : method.getBody().orElseThrow().findAll(AssignExpr.class)) {
			if (!directlyWithin(assignment, method)) {
				continue;
			}
			Expression target = assignment.getTarget();
			if (target instanceof FieldAccessExpr access && access.getScope() instanceof ThisExpr) {
				result.add(access.getNameAsString());
			}
			else if (target instanceof NameExpr name
					&& !TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(),
							name.getNameAsString(), assignment)) {
				result.add(name.getNameAsString());
			}
		}
		return Set.copyOf(result);
	}

	private static boolean callsInputMethod(MethodDeclaration readObject, Set<String> methods) {
		String input = readObject.getParameter(0).getNameAsString();
		return readObject.getBody()
			.orElseThrow()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> directlyWithin(call, readObject))
			.anyMatch(call -> methods.contains(call.getNameAsString()) && call.getScope()
				.filter(scope -> scope instanceof NameExpr name && name.getNameAsString().equals(input))
				.isPresent());
	}

	private static boolean callsRestorationHelper(MethodDeclaration readObject) {
		String input = readObject.getParameter(0).getNameAsString();
		return readObject.getBody()
			.orElseThrow()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> directlyWithin(call, readObject))
			.filter(call -> !Set
				.of("defaultReadObject", "readFields", "readObject", "readBoolean", "readByte", "readChar",
						"readDouble", "readFloat", "readInt", "readLong", "readShort", "readUTF")
				.contains(call.getNameAsString()))
			.anyMatch(call -> call.getArguments().stream().anyMatch(ThisExpr.class::isInstance)
					|| call.getScope().isEmpty()
					|| call.getScope().filter(scope -> scope instanceof ThisExpr).isPresent()
					|| call.getScope()
						.filter(scope -> scope instanceof NameExpr name && !name.getNameAsString().equals(input))
						.isPresent());
	}

	private static boolean nonDefaultInitializer(VariableDeclarator variable) {
		Expression initializer = variable.getInitializer().orElse(null);
		if (initializer == null) {
			return false;
		}
		String value = initializer.toString().replace("_", "").replace(" ", "").toLowerCase(java.util.Locale.ROOT);
		return !Set.of("null", "false", "0", "0l", "0.0", "0.0f", "0.0d", "'\\0'", "'\\u0000'").contains(value);
	}

	private static boolean validReadObject(InspectionContext context, MethodDeclaration method) {
		return method.isPrivate() && !method.isStatic() && method.getType().isVoidType()
				&& "readObject".equals(method.getNameAsString()) && ioParameter(context, method, "ObjectInputStream");
	}

	private static boolean ioParameter(InspectionContext context, MethodDeclaration method, String type) {
		return method.getParameters().size() == 1 && TypeLookup.isKnownType(context.compilationUnit(),
				method.getParameter(0).getType().asString(), "java.io", Set.of(type));
	}

	private static void httpSessionBindings(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"setAttribute".equals(call.getNameAsString()) || call.getArguments().size() != 2
					|| !httpSessionReceiver(context, call)) {
				continue;
			}
			String type = expressionType(context, call.getArgument(1), call);
			if (type != null && definitelyNonSerializableLocalType(context, type)) {
				findings.add(Finding.at(call.getArgument(1),
						"Source-local non-serializable object is stored in HttpSession"));
			}
		}
	}

	private static boolean httpSessionReceiver(InspectionContext context, MethodCallExpr call) {
		return call.getScope()
			.flatMap(scope -> visibleType(context, scope, call))
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, "jakarta.servlet.http",
					Set.of("HttpSession"))
					|| TypeLookup.isKnownType(context.compilationUnit(), type, "javax.servlet.http",
							Set.of("HttpSession")))
			.isPresent();
	}

	private static Optional<String> visibleType(InspectionContext context, Expression expression, Node use) {
		Optional<String> type = TypeLookup.visibleType(context.compilationUnit(), expression, use);
		if (type.isPresent()) {
			return type;
		}
		String name = expression instanceof NameExpr simple ? simple.getNameAsString()
				: expression instanceof FieldAccessExpr access ? access.getNameAsString() : null;
		if (name == null || expression instanceof NameExpr
				&& TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name, use)) {
			return Optional.empty();
		}
		TypeDeclaration<?> owner = use.findAncestor(TypeDeclaration.class).orElse(null);
		return owner == null ? Optional.empty()
				: owner.getFields()
					.stream()
					.filter(field -> field.getVariables()
						.stream()
						.anyMatch(variable -> variable.getNameAsString().equals(name)))
					.findFirst()
					.map(field -> field.getElementType().asString());
	}

	private static String expressionType(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof ObjectCreationExpr creation) {
			return creation.getType().asString();
		}
		return visibleType(context, expression, use).orElse(null);
	}

	private static boolean definitelyNonSerializableLocalType(InspectionContext context, String spelling) {
		TypeDeclaration<?> declaration = localType(context, spelling).orElse(null);
		return declaration != null && definitelyNonSerializable(context, declaration, new HashSet<>());
	}

	private static boolean definitelyNonSerializable(InspectionContext context, TypeDeclaration<?> type,
			Set<String> visiting) {
		if (type instanceof EnumDeclaration
				|| type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()
				|| serializable(context, type, new HashSet<>()) || !visiting.add(type.getNameAsString())) {
			return false;
		}
		List<String> relations = relations(type).toList();
		if (relations.isEmpty()) {
			return true;
		}
		return relations.stream()
			.map(relation -> localType(context, relation).orElse(null))
			.allMatch(parent -> parent != null && definitelyNonSerializable(context, parent, visiting));
	}

	private static boolean serializable(InspectionContext context, TypeDeclaration<?> type, Set<String> visiting) {
		if (type instanceof EnumDeclaration) {
			return true;
		}
		if (!visiting.add(type.getNameAsString())) {
			return false;
		}
		List<String> relations = relations(type).toList();
		if (relations.stream()
			.anyMatch(relation -> TypeLookup.isKnownType(context.compilationUnit(), relation, "java.io",
					Set.of("Serializable", "Externalizable")))) {
			return true;
		}
		return relations.stream()
			.map(relation -> localType(context, relation).orElse(null))
			.anyMatch(parent -> parent != null && serializable(context, parent, visiting));
	}

	private static boolean externalizable(InspectionContext context, TypeDeclaration<?> type, Set<String> visiting) {
		if (!visiting.add(type.getNameAsString())) {
			return false;
		}
		List<String> relations = relations(type).toList();
		if (relations.stream()
			.anyMatch(relation -> TypeLookup.isKnownType(context.compilationUnit(), relation, "java.io",
					Set.of("Externalizable")))) {
			return true;
		}
		return relations.stream()
			.map(relation -> localType(context, relation).orElse(null))
			.anyMatch(parent -> parent != null && externalizable(context, parent, visiting));
	}

	private static Stream<String> relations(TypeDeclaration<?> type) {
		if (type instanceof ClassOrInterfaceDeclaration declaration) {
			return Stream.concat(declaration.getExtendedTypes().stream(), declaration.getImplementedTypes().stream())
				.map(Object::toString);
		}
		if (type instanceof RecordDeclaration declaration) {
			return declaration.getImplementedTypes().stream().map(Object::toString);
		}
		if (type instanceof EnumDeclaration declaration) {
			return declaration.getImplementedTypes().stream().map(Object::toString);
		}
		return Stream.empty();
	}

	private static Optional<TypeDeclaration<?>> localType(InspectionContext context, String spelling) {
		String simple = TypeLookup.simpleName(spelling);
		List<TypeDeclaration<?>> matches = Stream
			.<TypeDeclaration<?>>concat(context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class).stream(),
					Stream.concat(context.compilationUnit().findAll(RecordDeclaration.class).stream(),
							context.compilationUnit().findAll(EnumDeclaration.class).stream()))
			.filter(type -> type.getNameAsString().equals(simple))
			.toList();
		return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
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
