package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.NodeList;
import java.util.List;
import java.util.stream.Stream;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.Node;

/** Inlines the sole queried element of an immediately created array or String literal. */
public final class InlineOnlyUsedElementTool implements InspectionTool {

	@Override
	public String id() {
		return "inline-only-used-element";
	}

	@Override
	public String description() {
		return "Inline an element queried immediately from a literal container";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Stream<Candidate> arrays = context.compilationUnit()
			.findAll(ArrayAccessExpr.class)
			.stream()
			.map(access -> arrayCandidate(context, access))
			.flatMap(Optional::stream);
		Stream<Candidate> strings = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> stringCandidate(context, call))
			.flatMap(Optional::stream);
		List<Candidate> candidates = java.util.stream.Stream.concat(arrays, strings).toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Inline the only queried element"));
			if (applyFixes) {
				context.editor().replace(candidate.expression().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> arrayCandidate(InspectionContext context, ArrayAccessExpr access) {
		if (!(access.getName() instanceof ArrayCreationExpr array) || array.getInitializer().isEmpty()
				|| array.getLevels().size() != 1 || array.getLevels().get(0).getDimension().isPresent()
				|| !(access.getIndex() instanceof IntegerLiteralExpr indexLiteral)
				|| AstSupport.hasComment(context, access)) {
			return Optional.empty();
		}
		NodeList<Expression> values = array.getInitializer().orElseThrow().getValues();
		int index = indexLiteral.asNumber().intValue();
		if (index < 0 || index >= values.size() || values.stream()
			.anyMatch(value -> !(value instanceof LiteralExpr) || value instanceof NullLiteralExpr)) {
			return Optional.empty();
		}
		Expression value = values.get(index);
		if (!sameExpressionType(context, array.getElementType().asString(), value)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(access, context.editor().text(value)));
	}

	private static Optional<Candidate> stringCandidate(InspectionContext context, MethodCallExpr call) {
		if (!"charAt".equals(call.getNameAsString()) || call.getScope().isEmpty()
				|| !(call.getScope().orElseThrow() instanceof StringLiteralExpr string)
				|| call.getArguments().size() != 1 || !(call.getArgument(0) instanceof IntegerLiteralExpr indexLiteral)
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		int index = indexLiteral.asNumber().intValue();
		if (index < 0 || index >= string.asString().length()) {
			return Optional.empty();
		}
		return Optional
			.of(new Candidate(call, new CharLiteralExpr(String.valueOf(string.asString().charAt(index))).toString()));
	}

	private static boolean sameExpressionType(InspectionContext context, String elementType, Expression value) {
		String type = ExpressionToolSupport.simpleName(elementType);
		return switch (type) {
			case "int" -> value instanceof IntegerLiteralExpr;
			case "long" -> value instanceof LongLiteralExpr;
			case "char" -> value instanceof CharLiteralExpr;
			case "boolean" -> value instanceof BooleanLiteralExpr;
			case "float" ->
				value instanceof DoubleLiteralExpr literal && literal.getValue().toLowerCase(Locale.ROOT).endsWith("f");
			case "double" -> value instanceof DoubleLiteralExpr literal
					&& !literal.getValue().toLowerCase(Locale.ROOT).endsWith("f");
			case "String" -> value instanceof StringLiteralExpr && ExpressionToolSupport
				.knownType(context.compilationUnit(), elementType, "java.lang", Set.of("String"));
			default -> false;
		};
	}

	private record Candidate(Node expression, String replacement) {
	}

}
