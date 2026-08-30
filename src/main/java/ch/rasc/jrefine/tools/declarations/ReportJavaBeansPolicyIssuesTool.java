package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reports optional source-local JavaBeans construction and accessor conventions. */
public final class ReportJavaBeansPolicyIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-javabeans-policy-issues";
	}

	@Override
	public String description() {
		return "Report JavaBeans classes without no-arg construction or balanced accessors";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		if (generatedSource(context)) {
			return new ToolResult(List.of(), false);
		}
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			if (!type.isInterface()) {
				inspectType(context, type, findings);
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void inspectType(InspectionContext context, ClassOrInterfaceDeclaration type,
			List<Finding> findings) {
		if (!type.getConstructors().isEmpty()
				&& type.getConstructors().stream().noneMatch(constructor -> constructor.getParameters().isEmpty())
				&& !lombokAnnotation(context.compilationUnit(), type, Set.of("NoArgsConstructor"))) {
			findings.add(Finding.at(type, "JavaBeans class has no explicit no-arg constructor"));
		}

		Set<String> getters = type.getMethods()
			.stream()
			.map(ReportJavaBeansPolicyIssuesTool::getterProperty)
			.filter(java.util.Objects::nonNull)
			.collect(java.util.stream.Collectors.toSet());
		Set<String> setters = type.getMethods()
			.stream()
			.map(ReportJavaBeansPolicyIssuesTool::setterProperty)
			.filter(java.util.Objects::nonNull)
			.collect(java.util.stream.Collectors.toSet());
		for (FieldDeclaration field : type.getFields()) {
			if (field.isStatic()) {
				continue;
			}
			for (VariableDeclarator variable : field.getVariables()) {
				String property = variable.getNameAsString();
				if (setters.contains(property) && !getters.contains(property)
						&& !lombokGetter(context.compilationUnit(), type, field)) {
					findings
						.add(Finding.at(variable, "JavaBeans property '" + property + "' has a setter but no getter"));
				}
			}
		}
	}

	private static String getterProperty(MethodDeclaration method) {
		if (!method.isPublic() || method.isStatic() || !method.getParameters().isEmpty()
				|| method.getType().isVoidType()) {
			return null;
		}
		String name = method.getNameAsString();
		if (name.startsWith("get") && name.length() > 3) {
			return decapitalize(name.substring(3));
		}
		if (name.startsWith("is") && name.length() > 2
				&& Set.of("boolean", "Boolean", "java.lang.Boolean").contains(method.getType().asString())) {
			return decapitalize(name.substring(2));
		}
		return null;
	}

	private static String setterProperty(MethodDeclaration method) {
		String name = method.getNameAsString();
		if (!method.isPublic() || method.isStatic() || method.getParameters().size() != 1
				|| !method.getType().isVoidType() || !name.startsWith("set") || name.length() <= 3) {
			return null;
		}
		return decapitalize(name.substring(3));
	}

	private static boolean lombokGetter(CompilationUnit unit, ClassOrInterfaceDeclaration type,
			FieldDeclaration field) {
		return lombokAnnotation(unit, field, Set.of("Getter"))
				|| lombokAnnotation(unit, type, Set.of("Data", "Getter", "Value"));
	}

	private static boolean lombokAnnotation(CompilationUnit unit,
			com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node, Set<String> names) {
		Set<String> localAnnotations = unit.findAll(AnnotationDeclaration.class)
			.stream()
			.map(AnnotationDeclaration::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		return node.getAnnotations().stream().anyMatch(annotation -> {
			String simple = annotation.getName().getIdentifier();
			if (!names.contains(simple) || localAnnotations.contains(simple)) {
				return false;
			}
			String spelling = annotation.getNameAsString();
			if (spelling.startsWith("lombok.")) {
				return true;
			}
			return unit.getImports()
				.stream()
				.anyMatch(imported -> !imported.isStatic()
						&& (imported.isAsterisk() && "lombok".equals(imported.getNameAsString())
								|| !imported.isAsterisk() && imported.getNameAsString().equals("lombok." + simple)));
		});
	}

	private static String decapitalize(String value) {
		if (value.length() > 1 && Character.isUpperCase(value.charAt(0)) && Character.isUpperCase(value.charAt(1))) {
			return value;
		}
		return Character.toLowerCase(value.charAt(0)) + value.substring(1);
	}

	private static boolean generatedSource(InspectionContext context) {
		String source = context.editor().source();
		String prefix = source.substring(0, Math.min(source.length(), 1_000)).toLowerCase(java.util.Locale.ROOT);
		return prefix.contains("generated by") || context.compilationUnit()
			.getTypes()
			.stream()
			.flatMap(type -> type.getAnnotations().stream())
			.anyMatch(annotation -> annotation.getName().getIdentifier().equals("Generated")
					|| annotation.getName().getIdentifier().equals("SuppressWarnings")
							&& annotation.toString().contains("\"all\""));
	}

}
