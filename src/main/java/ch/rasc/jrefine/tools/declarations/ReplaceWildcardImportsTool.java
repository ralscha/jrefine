package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ParseResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Replaces resolvable on-demand imports with the explicit imports used by the source
 * file.
 */
public final class ReplaceWildcardImportsTool implements InspectionTool {

	private static final Pattern JAVA_IDENTIFIER = Pattern
		.compile("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*");

	@Override
	public String id() {
		return "replace-wildcard-imports";
	}

	@Override
	public String description() {
		return "Replace resolvable wildcard imports with explicit imports";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		CompilationUnit compilationUnit = context.compilationUnit();
		List<ImportDeclaration> wildcardImports = compilationUnit.getImports()
			.stream()
			.filter(ImportDeclaration::isAsterisk)
			.filter(importDeclaration -> !containsComment(context, importDeclaration))
			.toList();
		if (wildcardImports.isEmpty()) {
			return ToolResult.of(List.of(), applyFixes);
		}

		LinkedHashMap<ImportDeclaration, List<String>> replacements = new LinkedHashMap<>(
				resolveTypeImports(context, wildcardImports));
		replacements.putAll(resolveStaticImports(context, wildcardImports));

		ArrayList<Finding> findings = new ArrayList<>();
		String lineSeparator = LineEndingSupport.detect(context.editor().source());
		replacements.forEach((importDeclaration, explicitImports) -> {
			findings.add(Finding.at(importDeclaration,
					"Replace wildcard import '" + displayName(importDeclaration) + "' with explicit imports"));
			if (applyFixes) {
				context.editor()
					.replace(importDeclaration.getRange().orElseThrow(), String.join(lineSeparator, explicitImports));
			}
		});
		return ToolResult.of(findings, applyFixes);
	}

	private static Map<ImportDeclaration, List<String>> resolveTypeImports(InspectionContext context,
			List<ImportDeclaration> wildcardImports) {
		CompilationUnit compilationUnit = context.compilationUnit();
		List<ImportDeclaration> imports = wildcardImports.stream()
			.filter(importDeclaration -> !importDeclaration.isStatic())
			.toList();
		if (imports.isEmpty()) {
			return Map.of();
		}

		Set<String> shadowedNames = declaredTypeNames(compilationUnit);
		compilationUnit.getImports()
			.stream()
			.filter(importDeclaration -> !importDeclaration.isAsterisk() && !importDeclaration.isStatic())
			.forEach(importDeclaration -> shadowedNames.add(importDeclaration.getName().getIdentifier()));

		LinkedHashMap<ImportDeclaration, Set<String>> matches = new LinkedHashMap<>();
		imports.forEach(importDeclaration -> matches.put(importDeclaration, new LinkedHashSet<>()));
		HashSet<ImportDeclaration> blocked = new HashSet<>();
		for (String name : referencedTypeNames(compilationUnit)) {
			if (shadowedNames.contains(name) || isDeclaredInCurrentPackage(context, name)) {
				continue;
			}
			List<ImportDeclaration> matchingImports = imports.stream()
				.filter(importDeclaration -> typeExists(context, importDeclaration.getNameAsString(), name))
				.toList();
			if (matchingImports.size() == 1) {
				matches.get(matchingImports.getFirst()).add(name);
			}
			else if (matchingImports.size() > 1) {
				blocked.addAll(matchingImports);
			}
		}

		LinkedHashMap<ImportDeclaration, List<String>> replacements = new LinkedHashMap<>();
		matches.forEach((importDeclaration, names) -> {
			if (names.isEmpty() || blocked.contains(importDeclaration)) {
				return;
			}
			String packageName = importDeclaration.getNameAsString();
			replacements.put(importDeclaration,
					names.stream().sorted().map(name -> "import " + packageName + "." + name + ";").toList());
		});
		return replacements;
	}

	private static Map<ImportDeclaration, List<String>> resolveStaticImports(InspectionContext context,
			List<ImportDeclaration> wildcardImports) {
		CompilationUnit compilationUnit = context.compilationUnit();
		List<ImportDeclaration> imports = wildcardImports.stream().filter(ImportDeclaration::isStatic).toList();
		if (imports.isEmpty()) {
			return Map.of();
		}

		Set<String> shadowedNames = declaredTypeNames(compilationUnit);
		compilationUnit.getImports()
			.stream()
			.filter(importDeclaration -> !importDeclaration.isAsterisk())
			.forEach(importDeclaration -> shadowedNames.add(importDeclaration.getName().getIdentifier()));

		HashMap<ImportDeclaration, Set<String>> memberNames = new HashMap<>();
		for (ImportDeclaration importDeclaration : imports) {
			loadClass(importDeclaration.getNameAsString()).map(ReplaceWildcardImportsTool::publicStaticMembers)
				.ifPresent(names -> memberNames.put(importDeclaration, names));
		}

		LinkedHashMap<ImportDeclaration, Set<String>> matches = new LinkedHashMap<>();
		imports.forEach(importDeclaration -> matches.put(importDeclaration, new LinkedHashSet<>()));
		HashSet<ImportDeclaration> blocked = new HashSet<>();
		for (String name : referencedStaticNames(compilationUnit)) {
			if (shadowedNames.contains(name) || isDeclaredInCurrentPackage(context, name)) {
				continue;
			}
			List<ImportDeclaration> matchingImports = imports.stream()
				.filter(importDeclaration -> memberNames.getOrDefault(importDeclaration, Set.of()).contains(name))
				.toList();
			if (matchingImports.size() == 1) {
				matches.get(matchingImports.getFirst()).add(name);
			}
			else if (matchingImports.size() > 1) {
				blocked.addAll(matchingImports);
			}
		}

		LinkedHashMap<ImportDeclaration, List<String>> replacements = new LinkedHashMap<>();
		matches.forEach((importDeclaration, names) -> {
			if (names.isEmpty() || blocked.contains(importDeclaration)) {
				return;
			}
			String ownerName = importDeclaration.getNameAsString();
			replacements.put(importDeclaration,
					names.stream().sorted().map(name -> "import static " + ownerName + "." + name + ";").toList());
		});
		return replacements;
	}

	private static Set<String> referencedTypeNames(CompilationUnit compilationUnit) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		compilationUnit.findAll(ClassOrInterfaceType.class)
			.stream()
			.filter(type -> type.getScope().isEmpty())
			.forEach(type -> names.add(type.getNameAsString()));
		compilationUnit.findAll(AnnotationExpr.class)
			.stream()
			.filter(annotation -> annotation.getName().getQualifier().isEmpty())
			.forEach(annotation -> names.add(annotation.getName().getIdentifier()));
		compilationUnit.findAll(NameExpr.class).forEach(expression -> names.add(expression.getNameAsString()));
		addJavadocNames(compilationUnit, names);
		return names;
	}

	private static Set<String> referencedStaticNames(CompilationUnit compilationUnit) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		compilationUnit.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> call.getScope().isEmpty())
			.forEach(call -> names.add(call.getNameAsString()));
		compilationUnit.findAll(NameExpr.class).forEach(expression -> names.add(expression.getNameAsString()));
		compilationUnit.findAll(ClassOrInterfaceType.class)
			.stream()
			.filter(type -> type.getScope().isEmpty())
			.forEach(type -> names.add(type.getNameAsString()));
		compilationUnit.findAll(AnnotationExpr.class)
			.stream()
			.filter(annotation -> annotation.getName().getQualifier().isEmpty())
			.forEach(annotation -> names.add(annotation.getName().getIdentifier()));
		addJavadocNames(compilationUnit, names);
		return names;
	}

	private static void addJavadocNames(CompilationUnit compilationUnit, Set<String> names) {
		compilationUnit.getAllComments()
			.stream()
			.filter(JavadocComment.class::isInstance)
			.map(JavadocComment.class::cast)
			.forEach(comment -> {
				Matcher matcher = JAVA_IDENTIFIER.matcher(comment.getContent());
				while (matcher.find()) {
					names.add(matcher.group());
				}
			});
	}

	private static Set<String> declaredTypeNames(CompilationUnit compilationUnit) {
		HashSet<String> names = new HashSet<>();
		compilationUnit.findAll(TypeDeclaration.class).forEach(declaration -> names.add(declaration.getNameAsString()));
		compilationUnit.findAll(TypeParameter.class).forEach(parameter -> names.add(parameter.getNameAsString()));
		return names;
	}

	private static boolean typeExists(InspectionContext context, String packageName, String simpleName) {
		String qualifiedName = packageName + "." + simpleName;
		if (runtimeTypeExists(qualifiedName)) {
			return true;
		}
		return sourceTypeExists(context, packageName, simpleName);
	}

	private static boolean runtimeTypeExists(String qualifiedName) {
		String resourceName = qualifiedName.replace('.', '/') + ".class";
		ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
		if (contextLoader != null && contextLoader.getResource(resourceName) != null) {
			return true;
		}
		ClassLoader ownLoader = ReplaceWildcardImportsTool.class.getClassLoader();
		if (ownLoader != null && ownLoader.getResource(resourceName) != null) {
			return true;
		}
		return ClassLoader.getSystemResource(resourceName) != null;
	}

	private static boolean sourceTypeExists(InspectionContext context, String packageName, String simpleName) {
		return sourceTypeExists(context, packageName, simpleName, true);
	}

	private static boolean sourceTypeExists(InspectionContext context, String packageName, String simpleName,
			boolean requirePublic) {
		Optional<Path> sourceRoot = sourceRoot(context);
		if (sourceRoot.isEmpty()) {
			return false;
		}
		Path sourceFile = sourceRoot.get()
			.resolve(packageName.replace('.', java.io.File.separatorChar))
			.resolve(simpleName + ".java");
		if (!Files.isRegularFile(sourceFile)) {
			return false;
		}

		try {
			JavaParser parser = new JavaParser(
					new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
			ParseResult<CompilationUnit> result = parser.parse(Files.readString(sourceFile, StandardCharsets.UTF_8));
			return result.getResult()
				.stream()
				.flatMap(unit -> unit.getTypes().stream())
				.anyMatch(type -> type.getNameAsString().equals(simpleName) && (!requirePublic || type.isPublic()));
		}
		catch (IOException | RuntimeException exception) {
			return false;
		}
	}

	private static boolean isDeclaredInCurrentPackage(InspectionContext context, String simpleName) {
		return context.compilationUnit()
			.getPackageDeclaration()
			.map(declaration -> declaration.getNameAsString())
			.map(packageName -> runtimeTypeExists(packageName + "." + simpleName)
					|| sourceTypeExists(context, packageName, simpleName, false))
			.orElse(false);
	}

	private static Optional<Path> sourceRoot(InspectionContext context) {
		Path parent = context.path().toAbsolutePath().normalize().getParent();
		if (parent == null) {
			return Optional.empty();
		}
		String[] packageParts = context.compilationUnit()
			.getPackageDeclaration()
			.map(declaration -> declaration.getNameAsString().split("\\."))
			.orElseGet(() -> new String[0]);
		for (int index = packageParts.length - 1; index >= 0; index--) {
			if (parent.getFileName() == null || !parent.getFileName().toString().equals(packageParts[index])) {
				return Optional.empty();
			}
			parent = parent.getParent();
			if (parent == null) {
				return Optional.empty();
			}
		}
		return Optional.of(parent);
	}

	private static Optional<Class<?>> loadClass(String qualifiedName) {
		String candidate = qualifiedName;
		while (true) {
			try {
				ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
				return Optional.of(Class.forName(candidate, false, contextLoader));
			}
			catch (ClassNotFoundException | LinkageError exception) {
				int separator = candidate.lastIndexOf('.');
				if (separator < 0) {
					return Optional.empty();
				}
				candidate = candidate.substring(0, separator) + "$" + candidate.substring(separator + 1);
			}
		}
	}

	private static Set<String> publicStaticMembers(Class<?> type) {
		HashSet<String> names = new HashSet<>();
		for (Field field : type.getFields()) {
			if (Modifier.isStatic(field.getModifiers())) {
				names.add(field.getName());
			}
		}
		for (Method method : type.getMethods()) {
			if (Modifier.isStatic(method.getModifiers())) {
				names.add(method.getName());
			}
		}
		for (Class<?> nestedType : type.getClasses()) {
			if (Modifier.isStatic(nestedType.getModifiers())) {
				names.add(nestedType.getSimpleName());
			}
		}
		return Set.copyOf(names);
	}

	private static boolean containsComment(InspectionContext context, ImportDeclaration importDeclaration) {
		String source = context.editor().text(importDeclaration);
		return source.contains("//") || source.contains("/*");
	}

	private static String displayName(ImportDeclaration importDeclaration) {
		return (importDeclaration.isStatic() ? "static " : "") + importDeclaration.getNameAsString() + ".*";
	}

}
