package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.stmt.Statement;

/** Replaces common contains/get/put Map idioms with Java 8 Map methods. */
public final class SimplifyMapOperationsTool implements InspectionTool {

	private static final Set<String> MAP_TYPES = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap",
			"NavigableMap", "ConcurrentMap", "ConcurrentHashMap", "WeakHashMap", "IdentityHashMap", "Hashtable");

	@Override
	public String id() {
		return "simplify-map-operations";
	}

	@Override
	public String description() {
		return "Replace common Map idioms with Java 8 Map methods";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(ConditionalExpr.class)
			.stream()
			.map(expression -> getOrDefault(context, expression))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(IfStmt.class)
			.stream()
			.map(statement -> putIfAbsent(context, statement))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.node(), "Simplify Map operation"));
			if (applyFixes) {
				context.editor().replace(candidate.node().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> getOrDefault(InspectionContext context, ConditionalExpr expression) {
		if (AstSupport.hasComment(context, expression)) {
			return Optional.empty();
		}
		Contains condition = containsCall(expression.getCondition()).orElse(null);
		boolean containsWhenTrue = true;
		if (condition == null && expression.getCondition() instanceof UnaryExpr unary
				&& unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
			condition = containsCall(unary.getExpression()).orElse(null);
			containsWhenTrue = false;
		}
		if (condition == null) {
			return Optional.empty();
		}
		Expression present = containsWhenTrue ? expression.getThenExpr() : expression.getElseExpr();
		Expression fallback = containsWhenTrue ? expression.getElseExpr() : expression.getThenExpr();
		if (!(present instanceof MethodCallExpr get) || !"get".equals(get.getNameAsString())
				|| get.getArguments().size() != 1 || get.getScope().isEmpty()
				|| !get.getScope().orElseThrow().equals(condition.map()) || !get.getArgument(0).equals(condition.key())
				|| !knownMap(context, condition.map(), expression)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(expression, context.editor().text(condition.map()) + ".getOrDefault("
				+ context.editor().text(condition.key()) + ", " + context.editor().text(fallback) + ")"));
	}

	private static Optional<Candidate> putIfAbsent(InspectionContext context, IfStmt statement) {
		if (statement.getElseStmt().isPresent() || AstSupport.hasComment(context, statement)
				|| !(statement.getCondition() instanceof UnaryExpr unary)
				|| unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
			return Optional.empty();
		}
		Contains condition = containsCall(unary.getExpression()).orElse(null);
		Expression expression = singleExpression(statement.getThenStmt());
		if (condition == null || !(expression instanceof MethodCallExpr put) || !"put".equals(put.getNameAsString())
				|| put.getArguments().size() != 2 || put.getScope().isEmpty()
				|| !put.getScope().orElseThrow().equals(condition.map()) || !put.getArgument(0).equals(condition.key())
				|| !knownMap(context, condition.map(), statement)) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(statement, context.editor().text(condition.map()) + ".putIfAbsent("
				+ context.editor().text(condition.key()) + ", " + context.editor().text(put.getArgument(1)) + ");"));
	}

	private static Optional<Contains> containsCall(Expression expression) {
		if (!(expression instanceof MethodCallExpr call) || !"containsKey".equals(call.getNameAsString())
				|| call.getArguments().size() != 1 || call.getScope().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new Contains(call.getScope().orElseThrow(), call.getArgument(0)));
	}

	private static Expression singleExpression(Statement statement) {
		if (statement instanceof ExpressionStmt expression) {
			return expression.getExpression();
		}
		if (statement instanceof BlockStmt block && block.getStatements().size() == 1
				&& block.getStatement(0) instanceof ExpressionStmt expression) {
			return expression.getExpression();
		}
		return null;
	}

	private static boolean knownMap(InspectionContext context, Expression map, Node use) {
		return map instanceof NameExpr && TypeLookup.visibleType(context.compilationUnit(), map, use)
			.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, MAP_TYPES))
			.isPresent();
	}

	private record Contains(Expression map, Expression key) {
	}

	private record Candidate(Node node, String replacement) {
	}

}
