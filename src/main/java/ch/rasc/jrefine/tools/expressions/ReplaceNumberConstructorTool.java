package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Replaces primitive numeric wrapper constructors with valueOf factories. */
public final class ReplaceNumberConstructorTool implements InspectionTool {

	private static final Map<String, String> PRIMITIVES = Map.of("Byte", "byte", "Short", "short", "Integer", "int",
			"Long", "long");

	@Override
	public String id() {
		return "replace-number-constructor";
	}

	@Override
	public String description() {
		return "Replace numeric wrapper constructors that receive primitive arguments";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.map(creation -> candidate(context, creation))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.creation(), "Replace number constructor with valueOf()"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.creation().getRange().orElseThrow(),
							context.editor().text(candidate.creation().getType()) + ".valueOf("
									+ context.editor().text(candidate.creation().getArgument(0)) + ")");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, ObjectCreationExpr creation) {
		if (creation.getArguments().size() != 1 || creation.getAnonymousClassBody().isPresent()
				|| AstSupport.hasComment(context, creation)) {
			return Optional.empty();
		}
		String wrapper = creation.getType().getNameAsString();
		String primitive = PRIMITIVES.get(wrapper);
		if (primitive == null || !ExpressionToolSupport.knownType(context.compilationUnit(),
				creation.getType().asString(), "java.lang", Set.of(wrapper))) {
			return Optional.empty();
		}
		return NumericSupport.typeOf(context, creation.getArgument(0), creation)
			.filter(type -> type.equals(primitive))
			.map(ignored -> new Candidate(creation));
	}

	private record Candidate(ObjectCreationExpr creation) {
	}

}
