package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import java.util.List;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Removes final from local variables and parameters. */
public final class RemoveUnnecessaryFinalTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-final";
	}

	@Override
	public String description() {
		return "Remove final modifiers from local variables and parameters";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Modifier> candidates = context.compilationUnit()
			.findAll(Modifier.class)
			.stream()
			.filter(modifier -> modifier.getKeyword() == Modifier.Keyword.FINAL)
			.filter(RemoveUnnecessaryFinalTool::localOrParameterModifier)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Modifier modifier : candidates) {
			findings.add(Finding.at(modifier, "Remove unnecessary final modifier"));
			if (applyFixes) {
				context.editor().removeWithTrailingWhitespace(modifier.getRange().orElseThrow());
				modifier.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean localOrParameterModifier(Modifier modifier) {
		Node parent = modifier.getParentNode().orElse(null);
		return parent instanceof Parameter || parent instanceof VariableDeclarationExpr;
	}

}
