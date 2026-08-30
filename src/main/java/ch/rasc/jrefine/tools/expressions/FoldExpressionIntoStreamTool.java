package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Folds repeated boolean calls and delimited concatenations into standard APIs. */
public final class FoldExpressionIntoStreamTool implements InspectionTool {

	@Override
	public String id() {
		return "fold-expression-into-stream";
	}

	@Override
	public String description() {
		return "Fold repeated expressions into Stream or String.join calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.filter(FoldExpressionIntoStreamTool::topLevelChain)
			.map(binary -> candidate(context, binary))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String stream = candidates.stream().anyMatch(candidate -> candidate.kind() == Kind.STREAM)
				? ImportSupport.useType(context, "java.util.stream.Stream", applyFixes) : "Stream";
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Fold repeated expression into standard API"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(), candidate.kind() == Kind.JOIN
							? joinReplacement(context, candidate) : streamReplacement(context, candidate, stream));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BinaryExpr expression) {
		if (AstSupport.hasComment(context, expression)) {
			return Optional.empty();
		}
		Optional<Candidate> join = joinCandidate(context, expression);
		return join.isPresent() ? join : streamCandidate(context, expression);
	}

	private static Optional<Candidate> joinCandidate(InspectionContext context, BinaryExpr expression) {
		if (expression.getOperator() != BinaryExpr.Operator.PLUS) {
			return Optional.empty();
		}
		ArrayList<Expression> terms = new ArrayList<>();
		flatten(expression, BinaryExpr.Operator.PLUS, terms);
		if (terms.size() < 5 || terms.size() % 2 == 0 || !(terms.get(1) instanceof StringLiteralExpr delimiter)) {
			return Optional.empty();
		}
		for (int index = 1; index < terms.size(); index += 2) {
			if (!(terms.get(index) instanceof StringLiteralExpr literal)
					|| !literal.asString().equals(delimiter.asString())) {
				return Optional.empty();
			}
		}
		for (int index = 0; index < terms.size(); index += 2) {
			if (!knownString(context, terms.get(index), expression)) {
				return Optional.empty();
			}
		}
		return Optional.of(new Candidate(expression, Kind.JOIN, List.copyOf(terms), null));
	}

	private static Optional<Candidate> streamCandidate(InspectionContext context, BinaryExpr expression) {
		Operator operator = expression.getOperator();
		if (operator != BinaryExpr.Operator.AND && operator != BinaryExpr.Operator.OR) {
			return Optional.empty();
		}
		ArrayList<Expression> terms = new ArrayList<>();
		flatten(expression, operator, terms);
		if (terms.size() < 3 || terms.stream().anyMatch(term -> !(term instanceof MethodCallExpr))) {
			return Optional.empty();
		}
		List<MethodCallExpr> calls = terms.stream().map(Expression::asMethodCallExpr).toList();
		MethodCallExpr first = calls.getFirst();
		if (!(first.getScope().orElse(null) instanceof NameExpr)
				|| first.getArguments().stream().anyMatch(argument -> !stable(context, argument, expression))) {
			return Optional.empty();
		}
		String firstType = TypeLookup.visibleType(context.compilationUnit(), first.getScope().orElseThrow(), expression)
			.orElse(null);
		for (MethodCallExpr call : calls) {
			if (!(call.getScope().orElse(null) instanceof NameExpr name)
					|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), name.getNameAsString(),
							expression)
					|| !call.getNameAsString().equals(first.getNameAsString())
					|| !call.getArguments().equals(first.getArguments()) || !java.util.Objects.equals(firstType,
							TypeLookup.visibleType(context.compilationUnit(), name, expression).orElse(null))) {
				return Optional.empty();
			}
		}
		return Optional.of(new Candidate(expression, Kind.STREAM, List.copyOf(terms), operator));
	}

	private static String joinReplacement(InspectionContext context, Candidate candidate) {
		ArrayList<String> values = new ArrayList<>();
		for (int index = 0; index < candidate.terms().size(); index += 2) {
			values.add(context.editor().text(candidate.terms().get(index)));
		}
		return "String.join(" + context.editor().text(candidate.terms().get(1)) + ", " + String.join(", ", values)
				+ ")";
	}

	private static String streamReplacement(InspectionContext context, Candidate candidate, String stream) {
		List<MethodCallExpr> calls = candidate.terms().stream().map(Expression::asMethodCallExpr).toList();
		List<String> scopes = calls.stream().map(call -> context.editor().text(call.getScope().orElseThrow())).toList();
		String parameter = calls.stream()
			.flatMap(call -> call.getArguments().stream())
			.flatMap(argument -> argument.findAll(NameExpr.class).stream())
			.anyMatch(name -> "item".equals(name.getNameAsString())) ? "element" : "item";
		List<String> arguments = calls.getFirst().getArguments().stream().map(context.editor()::text).toList();
		return stream + ".of(" + String.join(", ", scopes) + ")."
				+ (candidate.operator() == BinaryExpr.Operator.AND ? "allMatch" : "anyMatch") + "(" + parameter + " -> "
				+ parameter + "." + calls.getFirst().getNameAsString() + "(" + String.join(", ", arguments) + "))";
	}

	private static boolean topLevelChain(BinaryExpr expression) {
		return expression.getParentNode()
			.filter(BinaryExpr.class::isInstance)
			.map(BinaryExpr.class::cast)
			.filter(parent -> parent.getOperator() == expression.getOperator())
			.isEmpty();
	}

	private static void flatten(Expression expression, BinaryExpr.Operator operator, List<Expression> result) {
		if (expression instanceof BinaryExpr binary && binary.getOperator() == operator) {
			flatten(binary.getLeft(), operator, result);
			flatten(binary.getRight(), operator, result);
		}
		else {
			result.add(expression);
		}
	}

	private static boolean knownString(InspectionContext context, Expression expression, BinaryExpr use) {
		if (expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr) {
			return true;
		}
		return TypeLookup.visibleType(context.compilationUnit(), expression, use)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String")))
			.isPresent();
	}

	private static boolean stable(InspectionContext context, Expression expression, BinaryExpr use) {
		return expression.isLiteralExpr() || expression instanceof NameExpr name
				&& TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), name.getNameAsString(), use);
	}

	private enum Kind {

		JOIN, STREAM

	}

	private record Candidate(BinaryExpr expression, Kind kind, List<Expression> terms, BinaryExpr.Operator operator) {
	}

}
