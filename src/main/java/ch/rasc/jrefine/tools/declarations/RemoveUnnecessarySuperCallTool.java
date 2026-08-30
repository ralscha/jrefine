package ch.rasc.jrefine.tools.declarations;

import java.util.List;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Removes explicit no-argument super constructor calls that Java inserts implicitly. */
public final class RemoveUnnecessarySuperCallTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-unnecessary-super-call";
	}

	@Override
	public String description() {
		return "Remove explicit no-argument super() constructor calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ExplicitConstructorInvocationStmt> candidates = context.compilationUnit()
			.findAll(ExplicitConstructorInvocationStmt.class)
			.stream()
			.filter(call -> !call.isThis() && call.getArguments().isEmpty() && call.getExpression().isEmpty()
					&& call.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isEmpty())
			.filter(call -> !AstSupport.hasComment(context, call))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (ExplicitConstructorInvocationStmt call : candidates) {
			findings.add(Finding.at(call, "Remove unnecessary call to super()"));
			if (applyFixes) {
				context.editor().removeLine(call);
				call.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

}
