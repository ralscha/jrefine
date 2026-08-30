package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/** Reports source-local method names and overload sets that create ambiguous APIs. */
public final class ReportApiNamingConflictsTool implements PolicyInspectionTool {

	private static final Set<String> ZERO_ARGUMENT_FUNCTIONS = Set.of("Supplier", "BooleanSupplier", "DoubleSupplier",
			"IntSupplier", "LongSupplier");

	private static final Set<String> ONE_ARGUMENT_FUNCTIONS = Set.of("Consumer", "Function", "Predicate",
			"UnaryOperator", "DoubleConsumer", "DoubleFunction", "DoublePredicate", "DoubleUnaryOperator",
			"IntConsumer", "IntFunction", "IntPredicate", "IntUnaryOperator", "LongConsumer", "LongFunction",
			"LongPredicate", "LongUnaryOperator", "DoubleToIntFunction", "DoubleToLongFunction", "IntToDoubleFunction",
			"IntToLongFunction", "LongToDoubleFunction", "LongToIntFunction", "ToDoubleFunction", "ToIntFunction",
			"ToLongFunction");

	private static final Set<String> TWO_ARGUMENT_FUNCTIONS = Set.of("BiConsumer", "BiFunction", "BiPredicate",
			"BinaryOperator", "DoubleBinaryOperator", "IntBinaryOperator", "LongBinaryOperator", "ObjDoubleConsumer",
			"ObjIntConsumer", "ObjLongConsumer", "ToDoubleBiFunction", "ToIntBiFunction", "ToLongBiFunction");

	@Override
	public String id() {
		return "report-api-naming-conflicts";
	}

	@Override
	public String description() {
		return "Report confusing method names, parameter drift, and lambda-unfriendly overload sets";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		if (generatedSource(context)) {
			return new ToolResult(List.of(), false);
		}
		ArrayList<Finding> findings = new ArrayList<>();
		Map<String, ClassOrInterfaceDeclaration> types = uniqueTypes(context);
		parameterNameDrift(context, types, findings);
		ancestorNames(context, findings);
		overloadSets(context, types, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void parameterNameDrift(InspectionContext context, Map<String, ClassOrInterfaceDeclaration> types,
			List<Finding> findings) {
		Set<Parameter> reported = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			for (MethodDeclaration method : directMethods(type)) {
				for (ClassOrInterfaceDeclaration parent : parentClosure(type, types, new HashSet<>())) {
					directMethods(parent).stream()
						.filter(candidate -> sameSignature(method, candidate))
						.findFirst()
						.ifPresent(candidate -> compareParameterNames(method, candidate, reported, findings,
								"Parameter name differs from overridden method"));
				}
			}
		}
		for (TypeDeclaration<?> type : context.compilationUnit().findAll(TypeDeclaration.class)) {
			Map<String, List<MethodDeclaration>> groups = sameNameAndArity(directMethods(type));
			for (List<MethodDeclaration> methods : groups.values()) {
				if (methods.size() < 2) {
					continue;
				}
				MethodDeclaration reference = methods.getFirst();
				methods.stream()
					.skip(1)
					.forEach(method -> compareParameterNames(method, reference, reported, findings,
							"Parameter name differs between overloads"));
			}
		}
	}

	private static void compareParameterNames(MethodDeclaration method, MethodDeclaration reference,
			Set<Parameter> reported, List<Finding> findings, String message) {
		for (int index = 0; index < method.getParameters().size(); index++) {
			Parameter parameter = method.getParameter(index);
			if (!parameter.getNameAsString().equals(reference.getParameter(index).getNameAsString())
					&& reported.add(parameter)) {
				findings.add(Finding.at(parameter,
						message + " ('" + reference.getParameter(index).getNameAsString() + "')"));
			}
		}
	}

	private static void ancestorNames(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			type.getExtendedTypes()
				.stream()
				.filter(parent -> simpleType(parent.asString()).equals(type.getNameAsString()))
				.forEach(parent -> findings.add(Finding.at(parent, "Class has the same name as an ancestor")));
			if (type.isInterface() || type.getExtendedTypes().isEmpty()) {
				continue;
			}
			String parentName = simpleType(type.getExtendedTypes().getFirst().orElseThrow().asString());
			directMethods(type).stream()
				.filter(method -> method.getNameAsString().equals(parentName))
				.forEach(method -> findings
					.add(Finding.at(method, "Method has the same name as parent class '" + parentName + "'")));
		}
		for (TypeDeclaration<?> type : context.compilationUnit().findAll(TypeDeclaration.class)) {
			directMethods(type).stream()
				.filter(method -> method.getNameAsString().equals(type.getNameAsString()))
				.forEach(
						method -> findings.add(Finding.at(method, "Method has the same name as its containing class")));
		}
	}

	private static void overloadSets(InspectionContext context, Map<String, ClassOrInterfaceDeclaration> types,
			List<Finding> findings) {
		Map<String, Integer> functionalInterfaces = sourceFunctionalInterfaces(context);
		for (TypeDeclaration<?> type : context.compilationUnit().findAll(TypeDeclaration.class)) {
			List<MethodDeclaration> methods = directMethods(type);
			methodsDifferingOnlyByCase(methods, findings);
			sameArityOverloads(methods, findings);
			lambdaUnfriendlyOverloads(context, methods, functionalInterfaces, findings);
			overloadedVarargs(type, methods, types, findings);
		}
	}

	private static void methodsDifferingOnlyByCase(List<MethodDeclaration> methods, List<Finding> findings) {
		Map<String, List<MethodDeclaration>> groups = new LinkedHashMap<>();
		methods.forEach(method -> groups
			.computeIfAbsent(method.getNameAsString().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
			.add(method));
		groups.values()
			.stream()
			.filter(group -> group.stream().map(MethodDeclaration::getNameAsString).distinct().count() > 1)
			.flatMap(List::stream)
			.forEach(method -> findings.add(Finding.at(method, "Methods in this class differ only by case")));
	}

	private static void sameArityOverloads(List<MethodDeclaration> methods, List<Finding> findings) {
		sameNameAndArity(methods).values()
			.stream()
			.filter(group -> group.size() > 1)
			.flatMap(List::stream)
			.forEach(method -> findings
				.add(Finding.at(method, "Overloaded methods have the same number of parameters")));
	}

	private static void lambdaUnfriendlyOverloads(InspectionContext context, List<MethodDeclaration> methods,
			Map<String, Integer> sourceFunctions, List<Finding> findings) {
		Set<MethodDeclaration> reported = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int leftIndex = 0; leftIndex < methods.size(); leftIndex++) {
			MethodDeclaration left = methods.get(leftIndex);
			for (int rightIndex = leftIndex + 1; rightIndex < methods.size(); rightIndex++) {
				MethodDeclaration right = methods.get(rightIndex);
				if (!left.getNameAsString().equals(right.getNameAsString())
						|| left.getParameters().size() != right.getParameters().size()
						|| !lambdaConflict(context, left, right, sourceFunctions)) {
					continue;
				}
				reported.add(left);
				reported.add(right);
			}
		}
		reported.forEach(method -> findings
			.add(Finding.at(method, "Overload set accepts conflicting functional-interface signatures")));
	}

	private static boolean lambdaConflict(InspectionContext context, MethodDeclaration left, MethodDeclaration right,
			Map<String, Integer> sourceFunctions) {
		for (int index = 0; index < left.getParameters().size(); index++) {
			String leftType = left.getParameter(index).getType().asString();
			String rightType = right.getParameter(index).getType().asString();
			if (normalizeType(leftType).equals(normalizeType(rightType))) {
				continue;
			}
			OptionalInt leftArity = functionalArity(context, leftType, sourceFunctions);
			OptionalInt rightArity = functionalArity(context, rightType, sourceFunctions);
			if (leftArity.isPresent() && rightArity.isPresent() && leftArity.getAsInt() == rightArity.getAsInt()
					&& otherParameterTypesMatch(left, right, index)) {
				return true;
			}
		}
		return false;
	}

	private static boolean otherParameterTypesMatch(MethodDeclaration left, MethodDeclaration right,
			int functionalIndex) {
		for (int index = 0; index < left.getParameters().size(); index++) {
			if (index != functionalIndex && !normalizeType(left.getParameter(index).getType().asString())
				.equals(normalizeType(right.getParameter(index).getType().asString()))) {
				return false;
			}
		}
		return true;
	}

	private static void overloadedVarargs(TypeDeclaration<?> owner, List<MethodDeclaration> methods,
			Map<String, ClassOrInterfaceDeclaration> types, List<Finding> findings) {
		Set<String> inheritedNames = owner instanceof ClassOrInterfaceDeclaration declaration
				? parentClosure(declaration, types, new HashSet<>()).stream()
					.flatMap(parent -> directMethods(parent).stream())
					.map(MethodDeclaration::getNameAsString)
					.collect(java.util.stream.Collectors.toSet())
				: Set.of();
		methods.stream()
			.filter(method -> method.getParameters().stream().anyMatch(Parameter::isVarArgs))
			.filter(method -> methods.stream()
				.anyMatch(other -> other != method && other.getNameAsString().equals(method.getNameAsString()))
					|| inheritedNames.contains(method.getNameAsString()))
			.forEach(method -> findings
				.add(Finding.at(method, "Varargs method is overloaded by another method with the same name")));
	}

	private static Map<String, Integer> sourceFunctionalInterfaces(InspectionContext context) {
		HashMap<String, Integer> result = new HashMap<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (!type.isInterface()) {
				continue;
			}
			List<MethodDeclaration> abstractMethods = directMethods(type).stream()
				.filter(method -> !method.isStatic() && !method.isDefault() && !method.isPrivate())
				.filter(method -> method.getBody().isEmpty() || method.isAbstract())
				.toList();
			if (abstractMethods.size() == 1) {
				result.put(type.getNameAsString(), abstractMethods.getFirst().getParameters().size());
			}
		}
		return result;
	}

	private static OptionalInt functionalArity(InspectionContext context, String type,
			Map<String, Integer> sourceFunctions) {
		String simple = simpleType(type);
		Integer local = sourceFunctions.get(simple);
		if (local != null && unqualified(type)) {
			return OptionalInt.of(local);
		}
		if (TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("Runnable"))) {
			return OptionalInt.of(0);
		}
		if (TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.concurrent", Set.of("Callable"))) {
			return OptionalInt.of(0);
		}
		if (!TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.function", unionFunctions())) {
			return OptionalInt.empty();
		}
		if (ZERO_ARGUMENT_FUNCTIONS.contains(simple)) {
			return OptionalInt.of(0);
		}
		if (ONE_ARGUMENT_FUNCTIONS.contains(simple)) {
			return OptionalInt.of(1);
		}
		return OptionalInt.of(2);
	}

	private static Set<String> unionFunctions() {
		LinkedHashSet<String> result = new LinkedHashSet<>(ZERO_ARGUMENT_FUNCTIONS);
		result.addAll(ONE_ARGUMENT_FUNCTIONS);
		result.addAll(TWO_ARGUMENT_FUNCTIONS);
		return result;
	}

	private static Map<String, List<MethodDeclaration>> sameNameAndArity(List<MethodDeclaration> methods) {
		LinkedHashMap<String, List<MethodDeclaration>> result = new LinkedHashMap<>();
		methods.forEach(
				method -> result
					.computeIfAbsent(method.getNameAsString() + "#" + method.getParameters().size(),
							ignored -> new ArrayList<>())
					.add(method));
		return result;
	}

	private static List<MethodDeclaration> directMethods(TypeDeclaration<?> type) {
		return type.getMembers()
			.stream()
			.filter(MethodDeclaration.class::isInstance)
			.map(MethodDeclaration.class::cast)
			.toList();
	}

	private static Set<ClassOrInterfaceDeclaration> parentClosure(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited) {
		LinkedHashSet<ClassOrInterfaceDeclaration> result = new LinkedHashSet<>();
		ArrayList<ClassOrInterfaceType> references = new ArrayList<>(type.getExtendedTypes());
		references.addAll(type.getImplementedTypes());
		for (ClassOrInterfaceType reference : references) {
			if (!unqualified(reference.asString())) {
				continue;
			}
			String name = simpleType(reference.asString());
			if (!visited.add(name)) {
				continue;
			}
			ClassOrInterfaceDeclaration parent = types.get(name);
			if (parent != null) {
				result.add(parent);
				result.addAll(parentClosure(parent, types, visited));
			}
		}
		return result;
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

	private static boolean sameSignature(MethodDeclaration left, MethodDeclaration right) {
		if (!left.getNameAsString().equals(right.getNameAsString())
				|| left.getParameters().size() != right.getParameters().size()) {
			return false;
		}
		for (int index = 0; index < left.getParameters().size(); index++) {
			String leftType = normalizeType(left.getParameter(index).getType().asString())
					+ (left.getParameter(index).isVarArgs() ? "[]" : "");
			String rightType = normalizeType(right.getParameter(index).getType().asString())
					+ (right.getParameter(index).isVarArgs() ? "[]" : "");
			if (!leftType.equals(rightType)) {
				return false;
			}
		}
		return true;
	}

	private static String normalizeType(String type) {
		String value = eraseTypeArguments(type).replace(" ", "");
		int dimensions = 0;
		while (value.endsWith("[]")) {
			dimensions++;
			value = value.substring(0, value.length() - 2);
		}
		int dot = value.lastIndexOf('.');
		return (dot < 0 ? value : value.substring(dot + 1)) + "[]".repeat(dimensions);
	}

	private static String simpleType(String type) {
		String value = normalizeType(type);
		while (value.endsWith("[]")) {
			value = value.substring(0, value.length() - 2);
		}
		return value;
	}

	private static boolean unqualified(String type) {
		return !eraseTypeArguments(type).contains(".");
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

	private static boolean generatedSource(InspectionContext context) {
		String source = context.editor().source();
		String prefix = source.substring(0, Math.min(source.length(), 1_000)).toLowerCase(Locale.ROOT);
		return prefix.contains("generated by") || context.compilationUnit()
			.getTypes()
			.stream()
			.flatMap(type -> type.getAnnotations().stream())
			.anyMatch(annotation -> annotation.getName().getIdentifier().equals("Generated")
					|| annotation.getName().getIdentifier().equals("SuppressWarnings")
							&& annotation.toString().contains("\"all\""));
	}

}
