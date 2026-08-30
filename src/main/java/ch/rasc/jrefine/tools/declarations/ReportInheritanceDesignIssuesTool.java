package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports source-local inheritance relationships that obscure or weaken declaration
 * intent.
 */
public final class ReportInheritanceDesignIssuesTool implements PolicyInspectionTool {

	private static final Set<String> COLLECTION_IMPLEMENTATIONS = Set.of("ArrayDeque", "ArrayList", "HashSet",
			"LinkedHashSet", "LinkedList", "PriorityQueue", "Stack", "TreeSet", "Vector");

	private static final Set<String> FINAL_JAVA_LANG_TYPES = Set.of("Boolean", "Byte", "Character", "Class", "Double",
			"Float", "Integer", "Long", "Module", "Short", "StackTraceElement", "String", "StringBuffer",
			"StringBuilder", "System", "Void");

	@Override
	public String id() {
		return "report-inheritance-design-issues";
	}

	@Override
	public String description() {
		return "Report source-local hierarchy, overriding, overload, and type-bound design issues";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<String, ClassOrInterfaceDeclaration> types = uniqueTypes(context);
		Set<String> annotations = uniqueAnnotationNames(context);
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (types.get(type.getNameAsString()) != type) {
				continue;
			}
			classDesign(context, type, types, annotations, findings);
			closedPrivateHierarchy(context, type, types, findings);
			methodRelationships(type, types, findings);
		}
		finalTypeBounds(context, types, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void classDesign(InspectionContext context, ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> annotations, List<Finding> findings) {
		if (!type.isInterface() && type.isAbstract()) {
			if (type.getMethods().stream().noneMatch(method -> abstractMethod(type, method))) {
				findings.add(Finding.at(type, "Abstract class declares no abstract methods"));
			}
			type.getConstructors()
				.stream()
				.filter(ConstructorDeclaration::isPublic)
				.forEach(constructor -> findings.add(Finding.at(constructor,
						"Public constructor in abstract class exposes direct construction API")));
		}

		if (type.isInterface()) {
			return;
		}
		for (ClassOrInterfaceType parentReference : type.getExtendedTypes()) {
			String parentName = simpleType(parentReference.asString());
			if (unqualified(parentReference.asString()) && annotations.contains(parentName)) {
				findings.add(Finding.at(parentReference, "Class extends an annotation interface"));
				continue;
			}
			ClassOrInterfaceDeclaration parent = sourceType(parentReference, types);
			if (type.isAbstract() && parent != null && !parent.isInterface() && !parent.isAbstract()) {
				findings.add(Finding.at(parentReference, "Abstract class extends a concrete source class"));
			}
			if (parent != null && utilityClass(parent)) {
				findings
					.add(Finding.at(parentReference, "Class extends a utility class containing only static behavior"));
			}
			if (TypeLookup.isKnownJavaUtilType(context.compilationUnit(), parentReference.asString(),
					COLLECTION_IMPLEMENTATIONS)) {
				findings
					.add(Finding.at(parentReference, "Class explicitly extends a concrete Collection implementation"));
			}
		}
		for (ClassOrInterfaceType interfaceReference : type.getImplementedTypes()) {
			ClassOrInterfaceDeclaration implemented = sourceType(interfaceReference, types);
			if (implemented != null && staticOnlyInterface(implemented, types, new HashSet<>())) {
				findings.add(Finding.at(interfaceReference,
						"Interface is implemented only to inherit static constants or types"));
			}
		}
	}

	private static boolean staticOnlyInterface(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited) {
		if (!type.isInterface() || !visited.add(type.getNameAsString())) {
			return false;
		}
		if (type.getMethods().stream().anyMatch(method -> !method.isStatic() && !method.isPrivate())) {
			return false;
		}
		boolean ownStaticApi = !type.getFields().isEmpty()
				|| type.getMethods().stream().anyMatch(MethodDeclaration::isStatic)
				|| type.getMembers()
					.stream()
					.anyMatch(member -> member instanceof ClassOrInterfaceDeclaration
							|| member instanceof EnumDeclaration || member instanceof RecordDeclaration);
		boolean inheritedStaticApi = false;
		for (ClassOrInterfaceType parentReference : type.getExtendedTypes()) {
			ClassOrInterfaceDeclaration parent = sourceType(parentReference, types);
			if (parent == null || !staticOnlyInterface(parent, types, visited)) {
				return false;
			}
			inheritedStaticApi = true;
		}
		return ownStaticApi || inheritedStaticApi;
	}

	private static void closedPrivateHierarchy(InspectionContext context, ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types, List<Finding> findings) {
		if (!type.isPrivate() || type.findAncestor(ClassOrInterfaceDeclaration.class).isEmpty()) {
			return;
		}
		if (type.isInterface()) {
			if (!type.getAnnotations().isEmpty() || !requiresExplicitImplementation(type)
					|| hasConcreteDescendant(context, type, types)) {
				return;
			}
			findings.add(Finding.at(type, "Private interface has no concrete source-local implementation"));
			return;
		}
		if (type.isAbstract() && type.getAnnotationByName("Deprecated").isEmpty()
				&& type.getAnnotationByName("java.lang.Deprecated").isEmpty()
				&& !hasConcreteDescendant(context, type, types)) {
			findings.add(Finding.at(type, "Private abstract class has no concrete source-local subclass"));
		}
	}

	private static boolean requiresExplicitImplementation(ClassOrInterfaceDeclaration type) {
		return type.getMethods().stream().filter(method -> abstractMethod(type, method)).count() > 1;
	}

	private static boolean hasConcreteDescendant(InspectionContext context, ClassOrInterfaceDeclaration root,
			Map<String, ClassOrInterfaceDeclaration> types) {
		String rootName = root.getNameAsString();
		if (types.values()
			.stream()
			.filter(candidate -> candidate != root)
			.filter(candidate -> !candidate.isInterface() && !candidate.isAbstract())
			.anyMatch(candidate -> inheritsFrom(candidate, rootName, types, new HashSet<>()))) {
			return true;
		}
		if (context.compilationUnit()
			.findAll(EnumDeclaration.class)
			.stream()
			.anyMatch(candidate -> referencesHierarchy(candidate.getImplementedTypes(), rootName, types))) {
			return true;
		}
		if (context.compilationUnit()
			.findAll(RecordDeclaration.class)
			.stream()
			.anyMatch(candidate -> referencesHierarchy(candidate.getImplementedTypes(), rootName, types))) {
			return true;
		}
		return context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.filter(creation -> creation.getAnonymousClassBody().isPresent())
			.anyMatch(creation -> referenceInHierarchy(creation.getType(), rootName, types, new HashSet<>()));
	}

	private static boolean inheritsFrom(ClassOrInterfaceDeclaration candidate, String rootName,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited) {
		if (!visited.add(candidate.getNameAsString())) {
			return false;
		}
		return referencesHierarchy(directParents(candidate), rootName, types, visited);
	}

	private static boolean referencesHierarchy(List<ClassOrInterfaceType> references, String rootName,
			Map<String, ClassOrInterfaceDeclaration> types) {
		return referencesHierarchy(references, rootName, types, new HashSet<>());
	}

	private static boolean referencesHierarchy(List<ClassOrInterfaceType> references, String rootName,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited) {
		return references.stream().anyMatch(reference -> referenceInHierarchy(reference, rootName, types, visited));
	}

	private static boolean referenceInHierarchy(ClassOrInterfaceType reference, String rootName,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited) {
		String name = simpleType(reference.asString());
		if (name.equals(rootName)) {
			return true;
		}
		ClassOrInterfaceDeclaration parent = types.get(name);
		return parent != null && inheritsFrom(parent, rootName, types, visited);
	}

	private static void methodRelationships(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types, List<Finding> findings) {
		List<ParentMethod> inherited = inheritedMethods(type, types);
		for (MethodDeclaration method : type.getMethods()) {
			List<ParentMethod> sameName = inherited.stream()
				.filter(candidate -> candidate.method().getNameAsString().equals(method.getNameAsString()))
				.toList();
			if (sameName.isEmpty()) {
				continue;
			}
			boolean privateMatch = false;
			boolean staticMismatch = false;
			boolean abstractAbstract = false;
			boolean abstractConcrete = false;
			boolean lostVarargs = false;
			for (ParentMethod candidate : sameName) {
				MethodDeclaration parent = candidate.method();
				if (!sameSignature(method, parent)) {
					continue;
				}
				if (parent.isPrivate()) {
					privateMatch = true;
					continue;
				}
				if (method.isStatic() != parent.isStatic()) {
					staticMismatch = true;
				}
				if (abstractMethod(type, method)) {
					if (abstractMethod(candidate.owner(), parent)) {
						abstractAbstract = true;
					}
					else if (!parent.isStatic()) {
						abstractConcrete = true;
					}
				}
				if (!method.getParameters().isEmpty()
						&& !method.getParameter(method.getParameters().size() - 1).isVarArgs()
						&& !parent.getParameters().isEmpty()
						&& parent.getParameter(parent.getParameters().size() - 1).isVarArgs()) {
					lostVarargs = true;
				}
			}
			if (privateMatch) {
				findings
					.add(Finding.at(method, "Method has the signature of an inaccessible private superclass method"));
			}
			if (staticMismatch) {
				findings.add(Finding.at(method, "Method conflicts with a static/instance superclass declaration"));
			}
			if (abstractAbstract) {
				findings.add(Finding.at(method, "Abstract method redundantly overrides an abstract ancestor method"));
			}
			if (abstractConcrete) {
				findings.add(Finding.at(method, "Abstract method overrides a concrete ancestor implementation"));
			}
			if (lostVarargs) {
				findings.add(Finding.at(method, "Non-varargs method overrides a varargs ancestor method"));
			}
			if (!sameName.stream().anyMatch(candidate -> sameSignature(method, candidate.method())) && sameName.stream()
				.anyMatch(candidate -> oneParameterDiffers(type, method, candidate.owner(), candidate.method()))) {
				findings.add(
						Finding.at(method, "Parameter type prevents overriding and may create an unintended overload"));
			}
		}
	}

	private static boolean sameSignature(MethodDeclaration left, MethodDeclaration right) {
		if (!left.getNameAsString().equals(right.getNameAsString())
				|| left.getParameters().size() != right.getParameters().size()) {
			return false;
		}
		for (int index = 0; index < left.getParameters().size(); index++) {
			if (!effectiveParameterType(left, index).equals(effectiveParameterType(right, index))) {
				return false;
			}
		}
		return true;
	}

	private static boolean oneParameterDiffers(ClassOrInterfaceDeclaration leftOwner, MethodDeclaration left,
			ClassOrInterfaceDeclaration rightOwner, MethodDeclaration right) {
		if (left.getParameters().size() != right.getParameters().size()) {
			return false;
		}
		Set<String> typeVariables = new HashSet<>();
		leftOwner.getTypeParameters().stream().map(TypeParameter::getNameAsString).forEach(typeVariables::add);
		rightOwner.getTypeParameters().stream().map(TypeParameter::getNameAsString).forEach(typeVariables::add);
		left.getTypeParameters().stream().map(TypeParameter::getNameAsString).forEach(typeVariables::add);
		right.getTypeParameters().stream().map(TypeParameter::getNameAsString).forEach(typeVariables::add);
		int differences = 0;
		for (int index = 0; index < left.getParameters().size(); index++) {
			String leftType = effectiveParameterType(left, index);
			String rightType = effectiveParameterType(right, index);
			if (typeVariables.contains(stripArrays(leftType)) || typeVariables.contains(stripArrays(rightType))) {
				return false;
			}
			if (!leftType.equals(rightType)) {
				differences++;
			}
		}
		return differences == 1;
	}

	private static String effectiveParameterType(MethodDeclaration method, int index) {
		String type = normalizeType(method.getParameter(index).getType().asString());
		return method.getParameter(index).isVarArgs() ? type + "[]" : type;
	}

	private static List<ParentMethod> inheritedMethods(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types) {
		LinkedHashMap<String, ParentMethod> result = new LinkedHashMap<>();
		collectInheritedMethods(type, types, new HashSet<>(), result);
		return List.copyOf(result.values());
	}

	private static void collectInheritedMethods(ClassOrInterfaceDeclaration type,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited, Map<String, ParentMethod> result) {
		directParents(type).forEach(reference -> {
			String name = simpleType(reference.asString());
			if (!visited.add(name)) {
				return;
			}
			ClassOrInterfaceDeclaration parent = sourceType(reference, types);
			if (parent == null) {
				return;
			}
			for (MethodDeclaration method : parent.getMethods()) {
				String key = method.getNameAsString() + "#"
						+ method.getParameters()
							.stream()
							.map(parameter -> normalizeType(parameter.getType().asString())
									+ (parameter.isVarArgs() ? "[]" : ""))
							.collect(java.util.stream.Collectors.joining(","));
				result.putIfAbsent(key, new ParentMethod(parent, method));
			}
			collectInheritedMethods(parent, types, visited, result);
		});
	}

	private static List<ClassOrInterfaceType> directParents(ClassOrInterfaceDeclaration type) {
		ArrayList<ClassOrInterfaceType> result = new ArrayList<>(type.getExtendedTypes());
		result.addAll(type.getImplementedTypes());
		return result;
	}

	private static ClassOrInterfaceDeclaration sourceType(ClassOrInterfaceType reference,
			Map<String, ClassOrInterfaceDeclaration> types) {
		return unqualified(reference.asString()) ? types.get(simpleType(reference.asString())) : null;
	}

	private static boolean unqualified(String type) {
		String erased = eraseTypeArguments(type);
		return !erased.contains(".");
	}

	private static boolean abstractMethod(ClassOrInterfaceDeclaration owner, MethodDeclaration method) {
		return method.isAbstract() || owner.isInterface() && !method.isDefault() && !method.isStatic()
				&& !method.isPrivate() && method.getBody().isEmpty();
	}

	private static boolean utilityClass(ClassOrInterfaceDeclaration type) {
		if (type.isInterface()) {
			return false;
		}
		List<BodyDeclaration<?>> substantive = type.getMembers()
			.stream()
			.filter(member -> !(member instanceof ConstructorDeclaration))
			.toList();
		if (substantive.isEmpty() || !substantive.stream()
			.allMatch(member -> member instanceof MethodDeclaration method && method.isStatic()
					|| member instanceof FieldDeclaration field && field.isStatic()
					|| member instanceof InitializerDeclaration initializer && initializer.isStatic()
					|| member instanceof ClassOrInterfaceDeclaration nested && nested.isStatic()
					|| member instanceof EnumDeclaration || member instanceof RecordDeclaration)) {
			return false;
		}
		return !type.getConstructors().isEmpty()
				&& type.getConstructors().stream().allMatch(ConstructorDeclaration::isPrivate);
	}

	private static void finalTypeBounds(InspectionContext context, Map<String, ClassOrInterfaceDeclaration> types,
			List<Finding> findings) {
		Set<String> finalSourceTypes = new HashSet<>();
		types.values()
			.stream()
			.filter(ClassOrInterfaceDeclaration::isFinal)
			.map(ClassOrInterfaceDeclaration::getNameAsString)
			.forEach(finalSourceTypes::add);
		context.compilationUnit()
			.findAll(EnumDeclaration.class)
			.stream()
			.map(EnumDeclaration::getNameAsString)
			.forEach(finalSourceTypes::add);
		context.compilationUnit()
			.findAll(RecordDeclaration.class)
			.stream()
			.map(RecordDeclaration::getNameAsString)
			.forEach(finalSourceTypes::add);
		for (TypeParameter parameter : context.compilationUnit().findAll(TypeParameter.class)) {
			for (ClassOrInterfaceType bound : parameter.getTypeBound()) {
				String name = simpleType(bound.asString());
				if (unqualified(bound.asString()) && finalSourceTypes.contains(name) || TypeLookup
					.isKnownJavaLangType(context.compilationUnit(), bound.asString(), FINAL_JAVA_LANG_TYPES)) {
					findings.add(Finding.at(bound, "Type parameter is bounded by final class " + name));
				}
			}
		}
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

	private static Set<String> uniqueAnnotationNames(InspectionContext context) {
		HashMap<String, Integer> counts = new HashMap<>();
		context.compilationUnit()
			.findAll(AnnotationDeclaration.class)
			.forEach(annotation -> counts.merge(annotation.getNameAsString(), 1, Integer::sum));
		return counts.entrySet()
			.stream()
			.filter(entry -> entry.getValue() == 1)
			.map(Map.Entry::getKey)
			.collect(java.util.stream.Collectors.toSet());
	}

	private static String normalizeType(String type) {
		String erased = eraseTypeArguments(type).replace(" ", "");
		int dimensions = 0;
		while (erased.endsWith("[]")) {
			dimensions++;
			erased = erased.substring(0, erased.length() - 2);
		}
		int dot = erased.lastIndexOf('.');
		String simple = dot < 0 ? erased : erased.substring(dot + 1);
		return simple + "[]".repeat(dimensions);
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

	private static String simpleType(String type) {
		return stripArrays(normalizeType(type));
	}

	private static String stripArrays(String type) {
		String result = type;
		while (result.endsWith("[]")) {
			result = result.substring(0, result.length() - 2);
		}
		return result;
	}

	private record ParentMethod(ClassOrInterfaceDeclaration owner, MethodDeclaration method) {
	}

}
