package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Modifier.Keyword;
import java.util.List;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import com.github.javaparser.ast.body.AnnotationDeclaration;

/**
 * Removes modifiers implied by the containing declaration or otherwise unable to have an
 * effect.
 */
public final class RemoveUnnecessaryModifiersTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-modifiers";
	}

	@Override
	public String description() {
		return "Remove declaration modifiers implied by their context";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Modifier> candidates = context.compilationUnit()
			.findAll(Modifier.class)
			.stream()
			.filter(RemoveUnnecessaryModifiersTool::unnecessary)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Modifier modifier : candidates) {
			findings
				.add(Finding.at(modifier, "Remove unnecessary '" + modifier.getKeyword().asString() + "' modifier"));
			if (applyFixes) {
				context.editor().removeWithTrailingWhitespace(modifier.getRange().orElseThrow());
				modifier.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean unnecessary(Modifier modifier) {
		Node owner = modifier.getParentNode().orElse(null);
		Keyword keyword = modifier.getKeyword();
		if (keyword == Modifier.Keyword.PROTECTED && insideFinalType(owner)) {
			return true;
		}
		if (owner instanceof MethodDeclaration method) {
			if (insideInterface(method)
					&& (keyword == Modifier.Keyword.PUBLIC || keyword == Modifier.Keyword.ABSTRACT)) {
				return true;
			}
			return keyword == Modifier.Keyword.FINAL && (method.isPrivate() || insideFinalClass(method));
		}
		if (owner instanceof FieldDeclaration field && insideInterface(field)) {
			return keyword == Modifier.Keyword.PUBLIC || keyword == Modifier.Keyword.STATIC
					|| keyword == Modifier.Keyword.FINAL;
		}
		if (owner instanceof AnnotationMemberDeclaration) {
			return keyword == Modifier.Keyword.PUBLIC || keyword == Modifier.Keyword.ABSTRACT;
		}
		if (owner instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()
				&& keyword == Modifier.Keyword.ABSTRACT) {
			return true;
		}
		if (owner instanceof TypeDeclaration<?> type && insideInterface(type)) {
			return keyword == Modifier.Keyword.PUBLIC || keyword == Modifier.Keyword.STATIC;
		}
		return false;
	}

	private static boolean insideInterface(Node node) {
		return node.getParentNode()
			.filter(ClassOrInterfaceDeclaration.class::isInstance)
			.map(ClassOrInterfaceDeclaration.class::cast)
			.map(ClassOrInterfaceDeclaration::isInterface)
			.orElse(false) || node.getParentNode().filter(AnnotationDeclaration.class::isInstance).isPresent();
	}

	private static boolean insideFinalClass(Node node) {
		return node.getParentNode()
			.filter(ClassOrInterfaceDeclaration.class::isInstance)
			.map(ClassOrInterfaceDeclaration.class::cast)
			.map(ClassOrInterfaceDeclaration::isFinal)
			.orElse(false);
	}

	private static boolean insideFinalType(Node node) {
		return insideFinalClass(node) || node.getParentNode().filter(RecordDeclaration.class::isInstance).isPresent();
	}

}
