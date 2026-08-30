package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Uses Math.clamp() for canonical nested min/max expressions. */
public final class UseClampTool implements InspectionTool {

	@Override
	public String id() {
		return "use-clamp";
	}

	@Override
	public int minimumJavaVersion() {
		return 21;
	}

	@Override
	public String description() {
		return "Replace nested Math.min()/max() bounds with Math.clamp()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(UseClampTool::candidate)
			.flatMap(Optional::stream)
			.filter(candidate -> !AstSupport.hasComment(context, candidate.outer()))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.outer(), "Replace nested bounds with Math.clamp()"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.outer().getRange().orElseThrow(),
							"Math.clamp(" + context.editor().text(candidate.value()) + ", "
									+ context.editor().text(candidate.minimum()) + ", "
									+ context.editor().text(candidate.maximum()) + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(MethodCallExpr outer) {
		if (mathCall(outer, "min") && outer.getArgument(0) instanceof MethodCallExpr inner && mathCall(inner, "max")) {
			return Optional.of(new Candidate(outer, inner.getArgument(0), inner.getArgument(1), outer.getArgument(1)));
		}
		if (mathCall(outer, "max") && outer.getArgument(0) instanceof MethodCallExpr inner && mathCall(inner, "min")) {
			return Optional.of(new Candidate(outer, inner.getArgument(0), outer.getArgument(1), inner.getArgument(1)));
		}
		return Optional.empty();
	}

	private static boolean mathCall(MethodCallExpr call, String name) {
		return call.getNameAsString().equals(name) && call.getArguments().size() == 2
				&& call.getScope()
					.map(Object::toString)
					.filter(scope -> "Math".equals(scope) || "java.lang.Math".equals(scope))
					.isPresent();
	}

	private record Candidate(MethodCallExpr outer, Expression value, Expression minimum, Expression maximum) {
	}

}
