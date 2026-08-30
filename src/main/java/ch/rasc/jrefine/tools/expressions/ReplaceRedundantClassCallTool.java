package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Replaces Class.isInstance/cast calls with their language-level equivalents. */
public final class ReplaceRedundantClassCallTool implements InspectionTool {

	@Override
	public String id() {
		return "replace-redundant-class-call";
	}

	@Override
	public String description() {
		return "Replace Class.isInstance() and Class.cast() with Java syntax";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.call(),
					"Replace redundant Class." + candidate.call().getNameAsString() + "() call"));
			if (applyFixes) {
				String value = context.editor().text(candidate.call().getArgument(0));
				String type = candidate.type().getType().asString();
				String replacement = "isInstance".equals(candidate.call().getNameAsString())
						? "(" + value + ") instanceof " + type : "(" + type + ") (" + value + ")";
				context.editor().replace(candidate.call().getRange().orElseThrow(), replacement);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		if (!Set.of("isInstance", "cast").contains(call.getNameAsString()) || call.getArguments().size() != 1
				|| call.getScope().isEmpty() || !(call.getScope().orElseThrow() instanceof ClassExpr type)
				|| type.getType().isPrimitiveType() || AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(call, type));
	}

	private record Candidate(MethodCallExpr call, ClassExpr type) {
	}

}
