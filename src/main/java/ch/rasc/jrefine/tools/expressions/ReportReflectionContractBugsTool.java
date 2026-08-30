package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reports source-local reflective lookups and invocations that cannot match their target.
 */
public final class ReportReflectionContractBugsTool implements InspectionTool {

	private static final Set<String> METHOD_LOOKUPS = Set.of("getMethod", "getDeclaredMethod");

	private static final Set<String> FIELD_LOOKUPS = Set.of("getField", "getDeclaredField");

	private static final Set<String> CONSTRUCTOR_LOOKUPS = Set.of("getConstructor", "getDeclaredConstructor");

	private static final Set<String> HANDLE_METHOD_LOOKUPS = Set.of("findVirtual", "findStatic");

	private static final Set<String> HANDLE_FIELD_LOOKUPS = Set.of("findGetter", "findSetter", "findStaticGetter",
			"findStaticSetter", "findVarHandle", "findStaticVarHandle");

	@Override
	public String id() {
		return "report-reflection-contract-bugs";
	}

	@Override
	public String description() {
		return "Report source-local reflection and MethodHandle lookups or invocations with impossible signatures";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			String name = call.getNameAsString();
			if (METHOD_LOOKUPS.contains(name)) {
				methodLookup(context, call, findings);
			}
			else if (FIELD_LOOKUPS.contains(name)) {
				fieldLookup(context, call, findings);
			}
			else if (CONSTRUCTOR_LOOKUPS.contains(name)) {
				constructorLookup(context, call, findings);
			}
			else if ("invoke".equals(name)) {
				reflectiveInvocation(context, call, findings);
			}
			else if ("newInstance".equals(name)) {
				constructorInvocation(context, call, findings);
			}
			if (HANDLE_METHOD_LOOKUPS.contains(name)) {
				methodHandleLookup(context, call, findings);
			}
			else if (HANDLE_FIELD_LOOKUPS.contains(name)) {
				fieldHandleLookup(context, call, findings);
			}
			else if ("findConstructor".equals(name)) {
				constructorHandleLookup(context, call, findings);
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void methodLookup(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		LookupTarget target = lookupTarget(context, call, 1);
		if (target == null || call.getArguments().isEmpty()
				|| !(call.getArgument(0) instanceof StringLiteralExpr literal)) {
			return;
		}
		List<String> parameters = classArguments(call, 1);
		if (parameters == null) {
			return;
		}
		List<MethodDeclaration> named = target.type().getMethodsByName(literal.asString());
		MethodDeclaration match = named.stream()
			.filter(method -> parameterTypes(method).equals(parameters))
			.findFirst()
			.orElse(null);
		boolean declared = "getDeclaredMethod".equals(call.getNameAsString());
		boolean implicit = implicitGeneratedMethod(target.type(), literal.asString(), parameters, null, null)
				|| !declared && inheritsObjectMethods(target.type())
						&& implicitObjectMethod(literal.asString(), parameters, null);
		if (match == null && !implicit) {
			if (declared || !hasExternalParents(target.type())) {
				findings.add(Finding.at(call, "Reflective method lookup does not match a source-local method"));
			}
		}
		else if (!declared && match != null && !match.isPublic()) {
			findings.add(Finding.at(call, "Class.getMethod() cannot access a non-public source-local method"));
		}
	}

	private static void fieldLookup(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		LookupTarget target = lookupTarget(context, call, 1);
		if (target == null || call.getArguments().size() != 1
				|| !(call.getArgument(0) instanceof StringLiteralExpr literal)) {
			return;
		}
		FieldDeclaration field = field(target.type(), literal.asString()).orElse(null);
		ImplicitField implicit = implicitField(target.type(), literal.asString()).orElse(null);
		boolean declared = "getDeclaredField".equals(call.getNameAsString());
		if (field == null && implicit == null) {
			if (declared || !hasExternalParents(target.type())) {
				findings.add(Finding.at(call, "Reflective field lookup does not match a source-local field"));
			}
		}
		else if (!declared && (field != null && !field.isPublic() || field == null && !implicit.isPublic())) {
			if (!hasExternalParents(target.type())) {
				findings.add(Finding.at(call, "Class.getField() cannot access a non-public source-local field"));
			}
		}
	}

	private static void constructorLookup(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		LookupTarget target = lookupTarget(context, call, 0);
		if (target == null || !(target.type() instanceof ClassOrInterfaceDeclaration type) || type.isInterface()) {
			return;
		}
		List<String> parameters = classArguments(call, 0);
		if (parameters == null) {
			return;
		}
		ConstructorDeclaration match = type.getConstructors()
			.stream()
			.filter(constructor -> parameterTypes(constructor.getParameters()).equals(parameters))
			.findFirst()
			.orElse(null);
		boolean implicitNoArg = type.getConstructors().isEmpty() && parameters.isEmpty();
		boolean declared = "getDeclaredConstructor".equals(call.getNameAsString());
		if (match == null && !implicitNoArg) {
			findings.add(Finding.at(call, "Reflective constructor lookup does not match a source-local constructor"));
		}
		else if (!declared && (match != null && !match.isPublic() || match == null && !type.isPublic())) {
			findings
				.add(Finding.at(call, "Class.getConstructor() cannot access a non-public source-local constructor"));
		}
	}

	private static LookupTarget lookupTarget(InspectionContext context, MethodCallExpr call, int minimumArguments) {
		if (call.getArguments().size() < minimumArguments
				|| !(call.getScope().orElse(null) instanceof ClassExpr literal)) {
			return null;
		}
		TypeDeclaration<?> type = localType(context, literal.getType().asString()).orElse(null);
		return type == null ? null : new LookupTarget(type);
	}

	private static void reflectiveInvocation(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		MethodCallExpr lookup = originatingLookup(context, call).orElse(null);
		if (lookup == null || !METHOD_LOOKUPS.contains(lookup.getNameAsString())) {
			return;
		}
		List<String> parameters = classArguments(lookup, 1);
		if (parameters == null || spreadArrayArgument(context, call, 1)) {
			return;
		}
		int actual = Math.max(0, call.getArguments().size() - 1);
		if (actual != parameters.size()) {
			findings.add(Finding.at(call, "Method.invoke() argument count does not match the reflective lookup"));
			return;
		}
		ResolvedMethod method = resolvedMethod(context, lookup);
		if (method != null && !method.method().isStatic()
				&& (call.getArguments().isEmpty() || call.getArgument(0) instanceof NullLiteralExpr)) {
			findings.add(Finding.at(call, "Instance method is invoked reflectively with a null receiver"));
		}
	}

	private static void constructorInvocation(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		MethodCallExpr lookup = originatingLookup(context, call).orElse(null);
		if (lookup == null || !CONSTRUCTOR_LOOKUPS.contains(lookup.getNameAsString())) {
			return;
		}
		List<String> parameters = classArguments(lookup, 0);
		if (parameters == null || spreadArrayArgument(context, call, 0)) {
			return;
		}
		if (call.getArguments().size() != parameters.size()) {
			findings
				.add(Finding.at(call, "Constructor.newInstance() argument count does not match the reflective lookup"));
		}
	}

	private static Optional<MethodCallExpr> originatingLookup(InspectionContext context, MethodCallExpr invocation) {
		Expression scope = invocation.getScope().orElse(null);
		if (scope instanceof MethodCallExpr call) {
			return Optional.of(call);
		}
		if (!(scope instanceof NameExpr name)) {
			return Optional.empty();
		}
		return visibleVariable(context, name.getNameAsString(), invocation)
			.filter(variable -> !reassigned(context, variable, name.getNameAsString(), invocation))
			.flatMap(VariableDeclarator::getInitializer)
			.filter(MethodCallExpr.class::isInstance)
			.map(MethodCallExpr.class::cast);
	}

	private static boolean spreadArrayArgument(InspectionContext context, MethodCallExpr call, int firstArgument) {
		if (call.getArguments().size() != firstArgument + 1) {
			return false;
		}
		Expression argument = call.getArgument(firstArgument);
		return argument instanceof ArrayCreationExpr || argument instanceof ArrayInitializerExpr
				|| TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), argument, call)
					.filter(type -> type.endsWith("[]"))
					.isPresent();
	}

	private static ResolvedMethod resolvedMethod(InspectionContext context, MethodCallExpr lookup) {
		LookupTarget target = lookupTarget(context, lookup, 1);
		if (target == null || !(lookup.getArgument(0) instanceof StringLiteralExpr literal)) {
			return null;
		}
		List<String> parameters = classArguments(lookup, 1);
		if (parameters == null) {
			return null;
		}
		return target.type()
			.getMethodsByName(literal.asString())
			.stream()
			.filter(method -> parameterTypes(method).equals(parameters))
			.findFirst()
			.map(ResolvedMethod::new)
			.orElse(null);
	}

	private static void methodHandleLookup(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		if (!methodHandlesLookup(context, call) || call.getArguments().size() != 3
				|| !(call.getArgument(0) instanceof ClassExpr owner)
				|| !(call.getArgument(1) instanceof StringLiteralExpr name)) {
			return;
		}
		TypeDeclaration<?> type = localType(context, owner.getType().asString()).orElse(null);
		MethodTypeShape shape = methodType(context, call.getArgument(2));
		if (type == null || shape == null) {
			return;
		}
		boolean requireStatic = "findStatic".equals(call.getNameAsString());
		List<MethodDeclaration> named = type.getMethodsByName(name.asString());
		MethodDeclaration match = named.stream()
			.filter(method -> method.isStatic() == requireStatic)
			.filter(method -> parameterTypes(method).equals(shape.parameters()))
			.filter(method -> normalize(method.getType().asString()).equals(shape.returnType()))
			.findFirst()
			.orElse(null);
		boolean implicit = implicitGeneratedMethod(type, name.asString(), shape.parameters(), shape.returnType(),
				requireStatic)
				|| !requireStatic && implicitObjectMethod(name.asString(), shape.parameters(), shape.returnType());
		if (match == null && !implicit && (!hasExternalParents(type) || !named.isEmpty())) {
			findings.add(Finding.at(call, "MethodHandle lookup type does not match a source-local method"));
		}
	}

	private static void constructorHandleLookup(InspectionContext context, MethodCallExpr call,
			List<Finding> findings) {
		if (!methodHandlesLookup(context, call) || call.getArguments().size() != 2
				|| !(call.getArgument(0) instanceof ClassExpr owner)) {
			return;
		}
		TypeDeclaration<?> declaration = localType(context, owner.getType().asString()).orElse(null);
		MethodTypeShape shape = methodType(context, call.getArgument(1));
		if (!(declaration instanceof ClassOrInterfaceDeclaration type) || type.isInterface() || shape == null) {
			return;
		}
		boolean signature = "void".equals(shape.returnType()) && (type.getConstructors()
			.stream()
			.anyMatch(constructor -> parameterTypes(constructor.getParameters()).equals(shape.parameters()))
				|| type.getConstructors().isEmpty() && shape.parameters().isEmpty());
		if (!signature) {
			findings.add(
					Finding.at(call, "MethodHandle constructor lookup type does not match a source-local constructor"));
		}
	}

	private static void fieldHandleLookup(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		if (!methodHandlesLookup(context, call) || call.getArguments().size() != 3
				|| !(call.getArgument(0) instanceof ClassExpr owner)
				|| !(call.getArgument(1) instanceof StringLiteralExpr name)
				|| !(call.getArgument(2) instanceof ClassExpr fieldType)) {
			return;
		}
		TypeDeclaration<?> type = localType(context, owner.getType().asString()).orElse(null);
		if (type == null) {
			return;
		}
		boolean requireStatic = call.getNameAsString().startsWith("findStatic");
		FieldDeclaration field = field(type, name.asString()).orElse(null);
		ImplicitField implicit = implicitField(type, name.asString()).orElse(null);
		boolean match = field != null && field.isStatic() == requireStatic
				&& field.getVariables()
					.stream()
					.filter(variable -> variable.getNameAsString().equals(name.asString()))
					.anyMatch(variable -> normalize(variable.getType().asString())
						.equals(normalize(fieldType.getType().asString())))
				|| field == null && implicit != null && implicit.isStatic() == requireStatic
						&& implicit.type().equals(normalize(fieldType.getType().asString()));
		if (!match && (field != null || implicit != null || !hasExternalParents(type))) {
			findings
				.add(Finding.at(call, "MethodHandle/VarHandle field lookup type does not match a source-local field"));
		}
	}

	private static MethodTypeShape methodType(InspectionContext context, Expression expression) {
		if (!(expression instanceof MethodCallExpr call) || !"methodType".equals(call.getNameAsString())
				|| call.getArguments().isEmpty()
				|| call.getScope()
					.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(),
							"java.lang.invoke", Set.of("MethodType")))
					.isEmpty()
				|| !(call.getArgument(0) instanceof ClassExpr result)) {
			return null;
		}
		ArrayList<String> parameters = new ArrayList<>();
		for (int index = 1; index < call.getArguments().size(); index++) {
			if (!(call.getArgument(index) instanceof ClassExpr parameter)) {
				return null;
			}
			parameters.add(normalize(parameter.getType().asString()));
		}
		return new MethodTypeShape(normalize(result.getType().asString()), List.copyOf(parameters));
	}

	private static boolean methodHandlesLookup(InspectionContext context, MethodCallExpr operation) {
		Expression scope = operation.getScope().orElse(null);
		if (scope instanceof MethodCallExpr factory) {
			return methodHandlesFactory(context, factory);
		}
		if (!(scope instanceof NameExpr name)) {
			return false;
		}
		return visibleVariable(context, name.getNameAsString(), operation)
			.filter(variable -> !reassigned(context, variable, name.getNameAsString(), operation))
			.flatMap(VariableDeclarator::getInitializer)
			.filter(MethodCallExpr.class::isInstance)
			.map(MethodCallExpr.class::cast)
			.filter(factory -> methodHandlesFactory(context, factory))
			.isPresent();
	}

	private static boolean methodHandlesFactory(InspectionContext context, MethodCallExpr call) {
		return Set.of("lookup", "publicLookup", "privateLookupIn").contains(call.getNameAsString()) && call.getScope()
			.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), "java.lang.invoke",
					Set.of("MethodHandles")))
			.isPresent();
	}

	private static List<String> classArguments(MethodCallExpr call, int start) {
		ArrayList<String> result = new ArrayList<>();
		for (int index = start; index < call.getArguments().size(); index++) {
			if (!(call.getArgument(index) instanceof ClassExpr type)) {
				return null;
			}
			result.add(normalize(type.getType().asString()));
		}
		return List.copyOf(result);
	}

	private static List<String> parameterTypes(MethodDeclaration method) {
		return parameterTypes(method.getParameters());
	}

	private static List<String> parameterTypes(List<Parameter> parameters) {
		return parameters.stream()
			.map(parameter -> normalize(parameter.getType().asString() + (parameter.isVarArgs() ? "[]" : "")))
			.toList();
	}

	private static Optional<FieldDeclaration> field(TypeDeclaration<?> type, String name) {
		return type.getFields()
			.stream()
			.filter(declaration -> declaration.getVariables()
				.stream()
				.anyMatch(variable -> variable.getNameAsString().equals(name)))
			.findFirst();
	}

	private static Optional<ImplicitField> implicitField(TypeDeclaration<?> type, String name) {
		if (type instanceof RecordDeclaration record) {
			return record.getParameters()
				.stream()
				.filter(parameter -> parameter.getNameAsString().equals(name))
				.findFirst()
				.map(parameter -> new ImplicitField(normalize(parameter.getType().asString()), false, false));
		}
		if (type instanceof EnumDeclaration declaration
				&& declaration.getEntries().stream().anyMatch(entry -> entry.getNameAsString().equals(name))) {
			return Optional.of(new ImplicitField(declaration.getNameAsString(), true, true));
		}
		return Optional.empty();
	}

	private static boolean implicitGeneratedMethod(TypeDeclaration<?> type, String name, List<String> parameters,
			String returnType, Boolean requireStatic) {
		if (type instanceof RecordDeclaration record) {
			Optional<Parameter> component = record.getParameters()
				.stream()
				.filter(parameter -> parameter.getNameAsString().equals(name))
				.findFirst();
			if (component.isPresent() && parameters.isEmpty()
					&& (returnType == null
							|| normalize(component.orElseThrow().getType().asString()).equals(returnType))
					&& (requireStatic == null || !requireStatic)) {
				return true;
			}
			boolean objectMethod = "toString".equals(name) && parameters.isEmpty()
					&& (returnType == null || "String".equals(returnType))
					|| "hashCode".equals(name) && parameters.isEmpty()
							&& (returnType == null || "int".equals(returnType))
					|| "equals".equals(name) && parameters.equals(List.of("Object"))
							&& (returnType == null || "boolean".equals(returnType));
			if (objectMethod && (requireStatic == null || !requireStatic)) {
				return true;
			}
		}
		if (type instanceof EnumDeclaration declaration) {
			boolean values = "values".equals(name) && parameters.isEmpty()
					&& (returnType == null || (declaration.getNameAsString() + "[]").equals(returnType));
			boolean valueOf = "valueOf".equals(name) && parameters.equals(List.of("String"))
					&& (returnType == null || declaration.getNameAsString().equals(returnType));
			if ((values || valueOf) && (requireStatic == null || requireStatic)) {
				return true;
			}
		}
		return false;
	}

	private static boolean implicitObjectMethod(String name, List<String> parameters, String returnType) {
		String expectedReturn = switch (name) {
			case "equals" -> parameters.equals(List.of("Object")) ? "boolean" : null;
			case "getClass" -> parameters.isEmpty() ? "Class" : null;
			case "hashCode" -> parameters.isEmpty() ? "int" : null;
			case "notify", "notifyAll" -> parameters.isEmpty() ? "void" : null;
			case "toString" -> parameters.isEmpty() ? "String" : null;
			case "wait" ->
				parameters.isEmpty() || parameters.equals(List.of("long")) || parameters.equals(List.of("long", "int"))
						? "void" : null;
			default -> null;
		};
		return expectedReturn != null && (returnType == null || expectedReturn.equals(returnType));
	}

	private static Optional<TypeDeclaration<?>> localType(InspectionContext context, String spelling) {
		String simple = TypeLookup.simpleName(spelling);
		ArrayList<TypeDeclaration<?>> matches = new ArrayList<>();
		context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> type.getNameAsString().equals(simple))
			.forEach(matches::add);
		context.compilationUnit()
			.findAll(EnumDeclaration.class)
			.stream()
			.filter(type -> type.getNameAsString().equals(simple))
			.forEach(matches::add);
		context.compilationUnit()
			.findAll(RecordDeclaration.class)
			.stream()
			.filter(type -> type.getNameAsString().equals(simple))
			.forEach(matches::add);
		return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
	}

	private static boolean hasExternalParents(TypeDeclaration<?> type) {
		if (type instanceof ClassOrInterfaceDeclaration declaration) {
			return !declaration.getExtendedTypes().isEmpty() || !declaration.getImplementedTypes().isEmpty();
		}
		if (type instanceof EnumDeclaration declaration) {
			return !declaration.getImplementedTypes().isEmpty();
		}
		return type instanceof RecordDeclaration declaration && !declaration.getImplementedTypes().isEmpty();
	}

	private static boolean inheritsObjectMethods(TypeDeclaration<?> type) {
		return !(type instanceof ClassOrInterfaceDeclaration declaration) || !declaration.isInterface();
	}

	private static Optional<VariableDeclarator> visibleVariable(InspectionContext context, String name, Node use) {
		return context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> variable.getNameAsString().equals(name))
			.filter(variable -> before(variable, use))
			.filter(variable -> variable.findAncestor(BlockStmt.class)
				.filter(block -> block.isAncestorOf(use))
				.isPresent())
			.max(Comparator.comparingInt(variable -> variable.getBegin()
				.map(position -> position.line * 100_000 + position.column)
				.orElse(0)));
	}

	private static boolean reassigned(InspectionContext context, VariableDeclarator variable, String name, Node use) {
		return context.compilationUnit()
			.findAll(AssignExpr.class)
			.stream()
			.filter(assignment -> assignment.getTarget() instanceof NameExpr target
					&& target.getNameAsString().equals(name))
			.anyMatch(assignment -> before(variable, assignment) && before(assignment, use));
	}

	private static String normalize(String type) {
		String value = type.replace(" ", "");
		int generic = value.indexOf('<');
		if (generic >= 0) {
			value = value.substring(0, generic) + value.substring(value.lastIndexOf('>') + 1);
		}
		int dimensions = 0;
		while (value.endsWith("[]")) {
			dimensions++;
			value = value.substring(0, value.length() - 2);
		}
		int dot = value.lastIndexOf('.');
		if (dot >= 0) {
			value = value.substring(dot + 1);
		}
		return value + "[]".repeat(dimensions);
	}

	private static boolean before(Node left, Node right) {
		Position first = left.getBegin().orElse(Position.HOME);
		Position second = right.getBegin().orElse(Position.HOME);
		return first.line < second.line || first.line == second.line && first.column < second.column;
	}

	private record LookupTarget(TypeDeclaration<?> type) {
	}

	private record ResolvedMethod(MethodDeclaration method) {
	}

	private record MethodTypeShape(String returnType, List<String> parameters) {
	}

	private record ImplicitField(String type, boolean isStatic, boolean isPublic) {
	}

}
