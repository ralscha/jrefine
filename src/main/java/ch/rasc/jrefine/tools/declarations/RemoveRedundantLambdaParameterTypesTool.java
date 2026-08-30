package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.Parameter;
import java.util.List;
import com.github.javaparser.ast.expr.LambdaExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.SemanticEvidence;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Removes lambda parameter types when contextual type inference can supply them. */
public final class RemoveRedundantLambdaParameterTypesTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-lambda-parameter-types";
	}

	@Override
	public String description() {
		return "Remove lambda parameter types inferable from context";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<LambdaExpr> candidates = context.compilationUnit()
			.findAll(LambdaExpr.class)
			.stream()
			.filter(LambdaExpr::isExplicitlyTyped)
			.filter(lambda -> !lambda.getParameters().isEmpty())
			.filter(lambda -> lambda.getParameters()
				.stream()
				.allMatch(parameter -> parameter.getAnnotations().isEmpty() && parameter.getModifiers().isEmpty()
						&& parameter.getType().getAnnotations().isEmpty() && parameter.getVarArgsAnnotations().isEmpty()
						&& !parameter.isVarArgs() && !AstSupport.hasComment(context, parameter)))
			.filter(SemanticEvidence::hasDirectLambdaTargetType)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (LambdaExpr lambda : candidates) {
			findings.add(Finding.at(lambda, "Remove redundant lambda parameter types"));
			if (applyFixes) {
				for (Parameter parameter : lambda.getParameters()) {
					context.editor().replace(parameter.getRange().orElseThrow(), parameter.getNameAsString());
				}
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

}
