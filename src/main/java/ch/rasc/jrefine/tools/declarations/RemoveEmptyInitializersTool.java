package ch.rasc.jrefine.tools.declarations;

import java.util.List;
import com.github.javaparser.ast.body.InitializerDeclaration;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Removes empty instance and static class initializer blocks. */
public final class RemoveEmptyInitializersTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-empty-initializers";
	}

	@Override
	public String description() {
		return "Remove empty static and instance class initializer blocks";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<InitializerDeclaration> candidates = context.compilationUnit()
			.findAll(InitializerDeclaration.class)
			.stream()
			.filter(initializer -> initializer.getBody().getStatements().isEmpty())
			.filter(initializer -> !AstSupport.hasComment(context, initializer))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (InitializerDeclaration initializer : candidates) {
			findings.add(Finding.at(initializer, "Remove empty class initializer"));
			if (applyFixes) {
				context.editor().removeLine(initializer);
				initializer.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

}
