package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.github.javaparser.ast.body.AnnotationDeclaration;

/** Adds Override to methods whose inherited declaration is provable from local source. */
public final class AddOverrideAnnotationTool implements InspectionTool {

	private static final Set<String> OBJECT_METHODS = Set.of("toString()", "hashCode()", "equals(Object)", "clone()",
			"finalize()");

	@Override
	public String id() {
		return "add-override-annotation";
	}

	@Override
	public String description() {
		return "Add missing @Override annotations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<String, ClassOrInterfaceDeclaration> types = uniqueTypes(context);
		List<MethodDeclaration> candidates = context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(method -> missingOverride(method) && overrides(method, types, context))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String annotation = annotationName(context);
		String lineEnding = LineEndingSupport.detect(context.editor().source());
		for (MethodDeclaration method : candidates) {
			findings.add(Finding.at(method, "Add missing @Override annotation"));
			if (applyFixes) {
				String indent = " ".repeat(Math.max(0, method.getBegin().orElseThrow().column - 1));
				context.editor().insert(method.getBegin().orElseThrow(), "@" + annotation + lineEnding + indent);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean missingOverride(MethodDeclaration method) {
		return !method.isStatic() && !method.isPrivate() && method.getAnnotations().stream().noneMatch(annotation -> {
			String name = annotation.getNameAsString();
			return "Override".equals(name) || "java.lang.Override".equals(name);
		}) && method.getParentNode().filter(ClassOrInterfaceDeclaration.class::isInstance).isPresent();
	}

	private static boolean overrides(MethodDeclaration method, Map<String, ClassOrInterfaceDeclaration> types,
			InspectionContext context) {
		ClassOrInterfaceDeclaration owner = method.getParentNode()
			.map(ClassOrInterfaceDeclaration.class::cast)
			.orElseThrow();
		String signature = signature(method);
		if (!owner.isInterface() && !declaresObject(context, "Object") && OBJECT_METHODS.contains(signature)) {
			return true;
		}
		HashSet<String> visited = new HashSet<>();
		ArrayList<ClassOrInterfaceType> parents = new ArrayList<>(owner.getExtendedTypes());
		parents.addAll(owner.getImplementedTypes());
		return parents.stream()
			.anyMatch(parent -> inheritedSignature(simpleType(parent.asString()), signature, types, visited, owner));
	}

	private static boolean inheritedSignature(String typeName, String signature,
			Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited, ClassOrInterfaceDeclaration owner) {
		if (!visited.add(typeName)) {
			return false;
		}
		ClassOrInterfaceDeclaration declaration = types.get(typeName);
		if (declaration == null || declaration == owner) {
			return false;
		}
		if (declaration.getMethods()
			.stream()
			.filter(method -> !method.isPrivate() && !method.isStatic())
			.anyMatch(method -> signature(method).equals(signature))) {
			return true;
		}
		ArrayList<ClassOrInterfaceType> parents = new ArrayList<>(declaration.getExtendedTypes());
		parents.addAll(declaration.getImplementedTypes());
		return parents.stream()
			.anyMatch(parent -> inheritedSignature(simpleType(parent.asString()), signature, types, visited, owner));
	}

	private static String signature(MethodDeclaration method) {
		return method.getNameAsString() + "("
				+ method.getParameters()
					.stream()
					.map(parameter -> simpleType(parameter.getType().asString()) + (parameter.isVarArgs() ? "[]" : ""))
					.reduce((left, right) -> left + "," + right)
					.orElse("")
				+ ")";
	}

	private static Map<String, ClassOrInterfaceDeclaration> uniqueTypes(InspectionContext context) {
		HashMap<String, List<ClassOrInterfaceDeclaration>> grouped = new HashMap<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			grouped.computeIfAbsent(type.getNameAsString(), ignored -> new ArrayList<>()).add(type);
		}
		HashMap<String, ClassOrInterfaceDeclaration> result = new HashMap<>();
		grouped.forEach((name, declarations) -> {
			if (declarations.size() == 1) {
				result.put(name, declarations.get(0));
			}
		});
		return result;
	}

	private static boolean declaresObject(InspectionContext context, String name) {
		return context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(type -> type.getNameAsString().equals(name));
	}

	private static String annotationName(InspectionContext context) {
		boolean conflict = context.compilationUnit()
			.findAll(AnnotationDeclaration.class)
			.stream()
			.anyMatch(type -> "Override".equals(type.getNameAsString()))
				|| context.compilationUnit()
					.getImports()
					.stream()
					.anyMatch(
							imported -> !imported.isAsterisk() && "Override".equals(imported.getName().getIdentifier())
									&& !"java.lang.Override".equals(imported.getNameAsString()));
		return conflict ? "java.lang.Override" : "Override";
	}

	private static String simpleType(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		while (currentType.endsWith("[]")) {
			currentType = currentType.substring(0, currentType.length() - 2);
		}
		int dot = currentType.lastIndexOf('.');
		return dot >= 0 ? currentType.substring(dot + 1) : currentType;
	}

}
