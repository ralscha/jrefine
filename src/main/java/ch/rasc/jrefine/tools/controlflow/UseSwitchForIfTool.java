package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.NumericSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Converts equality-based if/else-if chains to arrow switch statements. */
public final class UseSwitchForIfTool implements InspectionTool {

	@Override
	public String id() {
		return "use-switch-for-if";
	}

	@Override
	public String description() {
		return "Replace equality-based if chains with switch statements";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(IfStmt.class)
			.stream()
			.filter(statement -> statement.getParentNode()
				.filter(IfStmt.class::isInstance)
				.map(IfStmt.class::cast)
				.flatMap(IfStmt::getElseStmt)
				.filter(statement::equals)
				.isEmpty())
			.map(statement -> candidate(context, statement))
			.flatMap(Optional::stream)
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && other.statement().isAncestorOf(candidate.statement())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.statement(), "Replace if chain with switch"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.statement().getRange().orElseThrow(),
							indentLikeOriginal(context, candidate.statement(), replacement(context, candidate)));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, IfStmt root) {
		if (AstSupport.hasComment(context, root)) {
			return Optional.empty();
		}
		ArrayList<Branch> branches = new ArrayList<>();
		IfStmt current = root;
		NameExpr selector = null;
		Statement fallback = null;
		while (true) {
			Optional<Equality> equality = equality(current.getCondition(), selector);
			if (equality.isEmpty() || current.getThenStmt().findAll(BreakStmt.class).size() > 0) {
				return Optional.empty();
			}
			Equality value = equality.orElseThrow();
			if (selector == null) {
				selector = value.selector();
			}
			branches.add(new Branch(value.label(), current.getThenStmt()));
			if (current.getElseStmt().isEmpty()) {
				break;
			}
			Statement alternative = current.getElseStmt().orElseThrow();
			if (alternative instanceof IfStmt next) {
				current = next;
			}
			else {
				if (!alternative.findAll(BreakStmt.class).isEmpty()) {
					return Optional.empty();
				}
				fallback = alternative;
				break;
			}
		}
		if (selector == null || branches.size() < 2
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), selector.getNameAsString(), root)) {
			return Optional.empty();
		}
		String type = TypeLookup.visibleType(context.compilationUnit(), selector, root)
			.map(NumericSupport::simpleName)
			.orElse("");
		if (!Set.of("byte", "short", "char", "int").contains(type)) {
			return Optional.empty();
		}
		HashSet<String> labels = new HashSet<>();
		if (branches.stream().map(branch -> normalizedLabel(branch.label())).anyMatch(label -> !labels.add(label))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(root, selector, List.copyOf(branches), fallback));
	}

	private static Optional<Equality> equality(Expression expression, NameExpr requiredSelector) {
		if (!(expression instanceof BinaryExpr binary) || binary.getOperator() != BinaryExpr.Operator.EQUALS) {
			return Optional.empty();
		}
		if (binary.getLeft() instanceof NameExpr selector && caseLabel(binary.getRight())
				&& (requiredSelector == null || requiredSelector.equals(selector))) {
			return Optional.of(new Equality(selector, binary.getRight()));
		}
		if (binary.getRight() instanceof NameExpr selector && caseLabel(binary.getLeft())
				&& (requiredSelector == null || requiredSelector.equals(selector))) {
			return Optional.of(new Equality(selector, binary.getLeft()));
		}
		return Optional.empty();
	}

	private static boolean caseLabel(Expression expression) {
		return expression instanceof IntegerLiteralExpr || expression instanceof CharLiteralExpr
				|| expression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS
						&& unary.getExpression() instanceof IntegerLiteralExpr;
	}

	private static String normalizedLabel(Expression expression) {
		return expression.toString().replace("_", "").toLowerCase(java.util.Locale.ROOT);
	}

	private static String replacement(InspectionContext context, Candidate candidate) {
		String lineEnding = LineEndingSupport.detect(context.editor().source());
		StringBuilder output = new StringBuilder("switch (").append(context.editor().text(candidate.selector()))
			.append(") {");
		for (Branch branch : candidate.branches()) {
			output.append(lineEnding)
				.append("    case ")
				.append(context.editor().text(branch.label()))
				.append(" -> ")
				.append(asBlock(context, branch.body()));
		}
		if (candidate.fallback() != null) {
			output.append(lineEnding).append("    default -> ").append(asBlock(context, candidate.fallback()));
		}
		return output.append(lineEnding).append('}').toString();
	}

	private static String asBlock(InspectionContext context, Statement statement) {
		String source = context.editor().text(statement);
		return statement.isBlockStmt() ? source : "{ " + source + " }";
	}

	private static String indentLikeOriginal(InspectionContext context, IfStmt node, String replacement) {
		String indent = " ".repeat(Math.max(0, node.getBegin().orElseThrow().column - 1));
		return LineEndingSupport.indentLikeSource(replacement, context.editor().source(), indent);
	}

	private record Equality(NameExpr selector, Expression label) {
	}

	private record Branch(Expression label, Statement body) {
	}

	private record Candidate(IfStmt statement, NameExpr selector, List<Branch> branches, Statement fallback) {
	}

}
