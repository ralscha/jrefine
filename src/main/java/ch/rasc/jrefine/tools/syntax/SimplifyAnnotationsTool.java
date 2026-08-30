package ch.rasc.jrefine.tools.syntax;

import java.util.List;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Uses marker and single-member shorthand for eligible annotations. */
public final class SimplifyAnnotationsTool implements InspectionTool {

	@Override
	public String id() {
		return "simplify-annotations";
	}

	@Override
	public String description() {
		return "Use marker and value shorthand forms for annotations";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(NormalAnnotationExpr.class)
			.stream()
			.filter(annotation -> annotation.getPairs().isEmpty() || annotation.getPairs().size() == 1
					&& "value".equals(annotation.getPairs().get(0).getNameAsString()))
			.filter(annotation -> !AstSupport.hasComment(context, annotation))
			.map(annotation -> new Candidate(annotation, replacement(context, annotation)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.annotation(), "Use simplified annotation syntax"));
			if (applyFixes) {
				context.editor().replace(candidate.annotation().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static String replacement(InspectionContext context, NormalAnnotationExpr annotation) {
		String name = context.editor().text(annotation.getName());
		if (annotation.getPairs().isEmpty()) {
			return "@" + name;
		}
		return "@" + name + "(" + context.editor().text(annotation.getPairs().get(0).getValue()) + ")";
	}

	private record Candidate(NormalAnnotationExpr annotation, String replacement) {
	}

}
