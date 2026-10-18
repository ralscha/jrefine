package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.AssignExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.SourceVersion;
import com.github.javaparser.ast.expr.Expression;

/**
 * Introduces an instanceof pattern for casts used by the right side of a short-circuit
 * condition.
 */
public final class UseInstanceofPatternsTool implements InspectionTool {

	@Override
	public String id() {
		return "use-instanceof-patterns";
	}

	@Override
	public int minimumJavaVersion() {
		return 16;
	}

	@Override
	public String description() {
		return "Replace repeated casts after instanceof with a pattern";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Set<String> usedNames = new HashSet<>();
		context.compilationUnit().findAll(SimpleName.class).forEach(name -> usedNames.add(name.asString()));
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(binary -> candidate(context, binary, usedNames))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.instanceOf(), "Use instanceof pattern"));
			if (applyFixes) {
				context.editor()
					.insertAfter(candidate.instanceOf().getType().getRange().orElseThrow().end,
							" " + candidate.variableName());
				candidate.casts()
					.forEach(cast -> context.editor().replace(cast.getRange().orElseThrow(), candidate.variableName()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr binary, Set<String> usedNames) {
		if (binary.getOperator() != BinaryExpr.Operator.AND || !(binary.getLeft() instanceof InstanceOfExpr instanceOf)
				|| instanceOf.getPattern().isPresent() || !instanceOf.getType().isClassOrInterfaceType()
				|| !stable(context, instanceOf.getExpression(), binary) || AstSupport.hasComment(context, binary)) {
			return Optional.empty();
		}
		List<CastExpr> casts = binary.getRight()
			.findAll(CastExpr.class)
			.stream()
			.filter(cast -> cast.getType().equals(instanceOf.getType())
					&& cast.getExpression().equals(instanceOf.getExpression()))
			.toList();
		if (casts.isEmpty()) {
			return Optional.empty();
		}
		String simpleType = instanceOf.getType().asClassOrInterfaceType().getNameAsString();
		String base = Character.toLowerCase(simpleType.charAt(0)) + simpleType.substring(1);
		if (SourceVersion.isKeyword(base) || usedNames.contains(base)) {
			base += "Value";
		}
		String name = base;
		for (int suffix = 2; usedNames.contains(name); suffix++) {
			name = base + suffix;
		}
		usedNames.add(name);
		return Optional.of(new Candidate(instanceOf, casts, name));
	}

	private static boolean stable(InspectionContext context, Expression expression, BinaryExpr binary) {
		if (expression.isThisExpr()) {
			return true;
		}
		if (!(expression instanceof NameExpr name) || !TypeLookup
			.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name.getNameAsString(), binary)) {
			return false;
		}
		return binary.getRight()
			.findAll(AssignExpr.class)
			.stream()
			.noneMatch(assignment -> assignment.getTarget().equals(name));
	}

	private record Candidate(InstanceOfExpr instanceOf, List<CastExpr> casts, String variableName) {
	}

}
