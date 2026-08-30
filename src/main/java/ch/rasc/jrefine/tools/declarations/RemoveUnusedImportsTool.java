package ch.rasc.jrefine.tools.declarations;

import java.util.regex.Matcher;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes explicit imports whose imported simple name is not referenced by the
 * compilation unit.
 */
public final class RemoveUnusedImportsTool implements InspectionTool {

	private static final Pattern JAVA_IDENTIFIER = Pattern
		.compile("\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*");

	@Override
	public String id() {
		return "remove-unused-imports";
	}

	@Override
	public String description() {
		return "Remove unused, duplicate, and redundant explicit imports";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		CompilationUnit compilationUnit = context.compilationUnit();
		Set<String> referencedNames = collectReferencedNames(compilationUnit);
		HashSet<ImportKey> seenImports = new HashSet<>();
		ArrayList<ImportDeclaration> toRemove = new ArrayList<>();
		ArrayList<Finding> findings = new ArrayList<>();

		for (ImportDeclaration importDeclaration : compilationUnit.getImports()) {
			ImportKey key = new ImportKey(importDeclaration.getNameAsString(), importDeclaration.isStatic(),
					importDeclaration.isAsterisk());
			if (!seenImports.add(key)) {
				toRemove.add(importDeclaration);
				findings.add(Finding.at(importDeclaration,
						"Remove duplicate import '" + displayName(importDeclaration) + "'"));
				continue;
			}

			// Without a complete classpath, attributing a wildcard import is unsafe. Keep
			// it.
			if (importDeclaration.isAsterisk()) {
				continue;
			}

			if (isRedundantImplicitImport(compilationUnit, importDeclaration)) {
				toRemove.add(importDeclaration);
				findings.add(Finding.at(importDeclaration,
						"Remove redundant import '" + displayName(importDeclaration) + "'"));
				continue;
			}

			String importedName = importDeclaration.getName().getIdentifier();
			if (!referencedNames.contains(importedName)) {
				toRemove.add(importDeclaration);
				findings.add(
						Finding.at(importDeclaration, "Remove unused import '" + displayName(importDeclaration) + "'"));
			}
		}

		if (applyFixes) {
			toRemove.forEach(node -> {
				context.editor().removeLine(node);
				node.remove();
			});
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Set<String> collectReferencedNames(CompilationUnit compilationUnit) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		compilationUnit.findAll(ClassOrInterfaceType.class).forEach(type -> names.add(type.getNameAsString()));
		compilationUnit.findAll(AnnotationExpr.class).forEach(annotation -> {
			String annotationName = annotation.getNameAsString();
			int qualifierSeparator = annotationName.indexOf('.');
			names.add(qualifierSeparator < 0 ? annotationName : annotationName.substring(0, qualifierSeparator));
		});
		compilationUnit.findAll(NameExpr.class).forEach(expression -> names.add(expression.getNameAsString()));
		compilationUnit.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> call.getScope().isEmpty())
			.forEach(call -> names.add(call.getNameAsString()));
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
		return Set.copyOf(names);
	}

	private static boolean isRedundantImplicitImport(CompilationUnit compilationUnit,
			ImportDeclaration importDeclaration) {
		if (importDeclaration.isStatic()) {
			return false;
		}

		String qualifiedName = importDeclaration.getNameAsString();
		if (qualifiedName.startsWith("java.lang.") && qualifiedName.indexOf('.', "java.lang.".length()) < 0) {
			return true;
		}

		return compilationUnit.getPackageDeclaration()
			.map(packageDeclaration -> packageDeclaration.getNameAsString() + ".")
			.filter(qualifiedName::startsWith)
			.map(prefix -> qualifiedName.indexOf('.', prefix.length()) < 0)
			.orElse(false);
	}

	private static String displayName(ImportDeclaration importDeclaration) {
		return (importDeclaration.isStatic() ? "static " : "") + importDeclaration.getNameAsString()
				+ (importDeclaration.isAsterisk() ? ".*" : "");
	}

	private record ImportKey(String name, boolean isStatic, boolean isAsterisk) {
	}

}
