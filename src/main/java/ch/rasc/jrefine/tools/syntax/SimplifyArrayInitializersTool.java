package ch.rasc.jrefine.tools.syntax;

import java.util.List;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Replaces redundant new array expressions in matching variable declarations. */
public final class SimplifyArrayInitializersTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-array-initializers";
	}

	@Override
	public String description() {
		return "Use array initializer shorthand in matching variable declarations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ArrayCreationExpr> candidates = context.compilationUnit()
			.findAll(ArrayCreationExpr.class)
			.stream()
			.filter(creation -> candidate(context, creation))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (ArrayCreationExpr creation : candidates) {
			findings.add(Finding.at(creation, "Remove redundant new expression from array initializer"));
			if (applyFixes) {
				context.editor()
					.replace(creation.getRange().orElseThrow(),
							context.editor().text(creation.getInitializer().orElseThrow()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean candidate(InspectionContext context, ArrayCreationExpr creation) {
		if (creation.getInitializer().isEmpty() || AstSupport.hasComment(context, creation)
				|| creation.getLevels().stream().anyMatch(level -> level.getDimension().isPresent())
				|| !creation.getElementType().getAnnotations().isEmpty()
				|| creation.getLevels().stream().anyMatch(level -> !level.getAnnotations().isEmpty())
				|| !(creation.getParentNode().orElse(null) instanceof VariableDeclarator variable)
				|| variable.getInitializer().orElse(null) != creation || variable.getType().isVarType()) {
			return false;
		}
		return variable.getType().asString().equals(creation.createdType().asString());
	}

}
