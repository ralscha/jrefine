package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;

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
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.map(binary -> candidate(context, binary))
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

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr binary) {
		if (binary.getOperator() != BinaryExpr.Operator.AND || !(binary.getLeft() instanceof InstanceOfExpr instanceOf)
				|| instanceOf.getPattern().isPresent() || !instanceOf.getType().isClassOrInterfaceType()
				|| !stable(instanceOf.getExpression()) || AstSupport.hasComment(context, binary)) {
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
		String simpleType = instanceOf.getType().asString();
		simpleType = simpleType.substring(simpleType.lastIndexOf('.') + 1);
		String base = Character.toLowerCase(simpleType.charAt(0)) + simpleType.substring(1);
		Node boundary = AstSupport.ancestor(binary, BlockStmt.class).map(Node.class::cast).orElse(binary);
		String name = nameTaken(boundary, base) ? base + "Value" : base;
		return Optional.of(new Candidate(instanceOf, casts, name));
	}

	private static boolean stable(Expression expression) {
		if (expression.isNameExpr() || expression.isThisExpr()) {
			return true;
		}
		return expression.isFieldAccessExpr() && stable(expression.asFieldAccessExpr().getScope());
	}

	private static boolean nameTaken(Node boundary, String name) {
		return boundary.findAll(NameExpr.class).stream().anyMatch(value -> value.getNameAsString().equals(name))
				|| boundary.findAll(VariableDeclarator.class)
					.stream()
					.anyMatch(variable -> variable.getNameAsString().equals(name))
				|| boundary.findAll(Parameter.class)
					.stream()
					.anyMatch(parameter -> parameter.getNameAsString().equals(name));
	}

	private record Candidate(InstanceOfExpr instanceOf, List<CastExpr> casts, String variableName) {
	}

}
