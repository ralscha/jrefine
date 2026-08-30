package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reports raw uses of source-local and lexically resolved JDK generic types. */
public final class ReportRawParameterizedTypesTool implements InspectionTool {

	@Override
	public String id() {
		return "report-raw-parameterized-types";
	}

	@Override
	public String description() {
		return "Report raw uses of known generic classes and interfaces";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		HashMap<String, Boolean> genericTypes = new HashMap<>();
		Set<String> typeParameters = context.compilationUnit()
			.findAll(TypeParameter.class)
			.stream()
			.map(TypeParameter::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		for (ClassOrInterfaceType type : context.compilationUnit().findAll(ClassOrInterfaceType.class)) {
			if (!rawTypeUse(type) || typeParameters.contains(type.getNameAsString())) {
				continue;
			}
			String spelling = type.asString();
			boolean generic = genericTypes.computeIfAbsent(spelling, ignored -> genericType(context, spelling));
			if (generic) {
				findings
					.add(Finding.at(type, "Raw use of parameterized type '" + TypeLookup.simpleName(spelling) + "'"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean rawTypeUse(ClassOrInterfaceType type) {
		if (type.getTypeArguments().isPresent() || type.findAncestor(ClassExpr.class).isPresent()
				|| type.findAncestor(ArrayCreationExpr.class).isPresent()
				|| type.findAncestor(MethodReferenceExpr.class).isPresent()) {
			return false;
		}
		return type.getParentNode()
			.filter(ClassOrInterfaceType.class::isInstance)
			.map(ClassOrInterfaceType.class::cast)
			.filter(parent -> parent.getScope().filter(scope -> scope == type).isPresent())
			.isEmpty();
	}

	private static boolean genericType(InspectionContext context, String spelling) {
		String simple = TypeLookup.simpleName(spelling);
		List<ClassOrInterfaceDeclaration> classes = context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> type.getNameAsString().equals(simple))
			.toList();
		List<RecordDeclaration> records = context.compilationUnit()
			.findAll(RecordDeclaration.class)
			.stream()
			.filter(type -> type.getNameAsString().equals(simple))
			.toList();
		int localDeclarations = classes.size() + records.size();
		if (localDeclarations > 0) {
			return localDeclarations == 1 && (classes.stream().anyMatch(type -> !type.getTypeParameters().isEmpty())
					|| records.stream().anyMatch(type -> !type.getTypeParameters().isEmpty()));
		}
		return resolvedJdkClass(context.compilationUnit(), spelling).filter(type -> type.getTypeParameters().length > 0)
			.isPresent();
	}

	private static Optional<Class<?>> resolvedJdkClass(CompilationUnit unit, String spelling) {
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		if (spelling.startsWith("java.")) {
			candidates.add(spelling);
		}
		else {
			String first = spelling.contains(".") ? spelling.substring(0, spelling.indexOf('.')) : spelling;
			String suffix = spelling.substring(first.length());
			unit.getImports()
				.stream()
				.filter(imported -> !imported.isStatic() && !imported.isAsterisk())
				.filter(imported -> imported.getName().getIdentifier().equals(first))
				.map(imported -> imported.getNameAsString() + suffix)
				.filter(name -> name.startsWith("java."))
				.forEach(candidates::add);
			if (!spelling.contains(".")) {
				unit.getImports()
					.stream()
					.filter(imported -> !imported.isStatic() && imported.isAsterisk())
					.map(imported -> imported.getNameAsString() + "." + spelling)
					.filter(name -> name.startsWith("java."))
					.forEach(candidates::add);
				candidates.add("java.lang." + spelling);
			}
		}
		List<Class<?>> resolved = candidates.stream()
			.map(ReportRawParameterizedTypesTool::loadJdkClass)
			.flatMap(Optional::stream)
			.distinct()
			.toList();
		return resolved.size() == 1 ? Optional.of(resolved.getFirst()) : Optional.empty();
	}

	private static Optional<Class<?>> loadJdkClass(String name) {
		String candidate = name;
		int dot = candidate.lastIndexOf('.');
		while (dot > "java".length()) {
			try {
				return Optional.of(Class.forName(candidate, false, ClassLoader.getPlatformClassLoader()));
			}
			catch (ClassNotFoundException | LinkageError ignored) {
				candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
				dot = candidate.lastIndexOf('.', dot - 1);
			}
		}
		return Optional.empty();
	}

}
