package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;

/** Replaces return/assignment switch statements with switch expressions. */
public final class UseSwitchExpressionTool implements InspectionTool {

	@Override
	public String id() {
		return "use-switch-expression";
	}

	@Override
	public int minimumJavaVersion() {
		return 14;
	}

	@Override
	public String description() {
		return "Replace value-producing switch statements with switch expressions";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> all = context.compilationUnit()
			.findAll(SwitchStmt.class)
			.stream()
			.map(statement -> candidate(context, statement))
			.flatMap(Optional::stream)
			.toList();
		List<Candidate> candidates = all.stream()
			.filter(candidate -> all.stream()
				.noneMatch(other -> other != candidate && other.statement().isAncestorOf(candidate.statement())))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.statement(), "Replace statement with switch expression"));
			if (applyFixes) {
				NodeList<SwitchEntry> entries = new NodeList<>();
				for (int index = 0; index < candidate.entries().size(); index++) {
					SwitchEntry original = candidate.entries().get(index);
					SwitchEntry entry = original.clone();
					entry.setType(SwitchEntry.Type.EXPRESSION);
					entry.setStatements(NodeList.nodeList(new ExpressionStmt(candidate.values().get(index).clone())));
					entries.add(entry);
				}
				String expression = new SwitchExpr(candidate.statement().getSelector().clone(), entries).toString();
				String replacement = candidate.returning() ? "return " + expression + ";"
						: context.editor().text(candidate.target().orElseThrow()) + " = " + expression + ";";
				context.editor()
					.replace(candidate.statement().getRange().orElseThrow(),
							indentLikeOriginal(context, candidate.statement(), replacement));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, SwitchStmt statement) {
		NodeList<SwitchEntry> entries = normalizedEntries(statement);
		if (statement.getEntries().isEmpty() || AstSupport.hasComment(context, statement)
				|| entries.stream().noneMatch(SwitchEntry::isDefault)
				|| entries.stream().anyMatch(entry -> branchStatements(entry).isEmpty())) {
			return Optional.empty();
		}
		ArrayList<Expression> returns = new ArrayList<>();
		boolean returnMode = true;
		for (SwitchEntry entry : entries) {
			NodeList<Statement> statements = branchStatements(entry);
			if (statements.size() == 1 && statements.get(0) instanceof ReturnStmt returned
					&& returned.getExpression().isPresent()) {
				returns.add(returned.getExpression().orElseThrow());
			}
			else {
				returnMode = false;
				break;
			}
		}
		if (returnMode) {
			return Optional.of(new Candidate(statement, entries, true, Optional.empty(), returns));
		}

		ArrayList<Expression> values = new ArrayList<>();
		Expression target = null;
		for (SwitchEntry entry : entries) {
			NodeList<Statement> statements = branchStatements(entry);
			if (statements.isEmpty() || statements.size() > 2
					|| !(statements.get(0) instanceof ExpressionStmt expressionStatement)
					|| !(expressionStatement.getExpression() instanceof AssignExpr assignment)
					|| assignment.getOperator() != AssignExpr.Operator.ASSIGN) {
				return Optional.empty();
			}
			if (statements.size() == 2 && (!(statements.get(1) instanceof BreakStmt breakStatement)
					|| !breakStatement.getLabel().isEmpty())) {
				return Optional.empty();
			}
			if (target == null) {
				target = assignment.getTarget();
			}
			else if (!target.equals(assignment.getTarget())) {
				return Optional.empty();
			}
			values.add(assignment.getValue());
		}
		return target == null || !stableTarget(target) ? Optional.empty()
				: Optional.of(new Candidate(statement, entries, false, Optional.of(target), values));
	}

	private static NodeList<SwitchEntry> normalizedEntries(SwitchStmt statement) {
		NodeList<SwitchEntry> result = new NodeList<>();
		NodeList<Expression> pending = new NodeList<>();
		for (SwitchEntry original : statement.getEntries()) {
			SwitchEntry entry = original.clone();
			if (entry.getStatements().isEmpty() && !entry.isDefault()) {
				entry.getLabels().forEach(label -> pending.add(label.clone()));
				continue;
			}
			if (!pending.isEmpty()) {
				NodeList<Expression> labels = new NodeList<>();
				pending.forEach(label -> labels.add(label.clone()));
				entry.getLabels().forEach(label -> labels.add(label.clone()));
				entry.setLabels(labels);
				pending.clear();
			}
			result.add(entry);
		}
		if (!pending.isEmpty()) {
			result.add(new SwitchEntry(pending, SwitchEntry.Type.STATEMENT_GROUP, new NodeList<>()));
		}
		return result;
	}

	private static boolean stableTarget(Expression expression) {
		if (expression.isNameExpr() || expression.isThisExpr()) {
			return true;
		}
		return expression.isFieldAccessExpr() && stableTarget(expression.asFieldAccessExpr().getScope());
	}

	private static NodeList<Statement> branchStatements(SwitchEntry entry) {
		if (entry.getType() == SwitchEntry.Type.BLOCK && entry.getStatements().size() == 1
				&& entry.getStatement(0) instanceof BlockStmt block) {
			return block.getStatements();
		}
		return entry.getStatements();
	}

	private static String indentLikeOriginal(InspectionContext context, SwitchStmt node, String replacement) {
		String indent = " ".repeat(Math.max(0, node.getBegin().orElseThrow().column - 1));
		return LineEndingSupport.indentLikeSource(replacement, context.editor().source(), indent);
	}

	private record Candidate(SwitchStmt statement, NodeList<SwitchEntry> entries, boolean returning,
			Optional<Expression> target, List<Expression> values) {
	}

}
