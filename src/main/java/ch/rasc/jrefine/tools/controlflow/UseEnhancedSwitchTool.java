package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Set;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.body.VariableDeclarator;

/** Converts non-fall-through colon switch statements to arrow-label form. */
public final class UseEnhancedSwitchTool implements InspectionTool {

	@Override
	public String id() {
		return "use-enhanced-switch";
	}

	@Override
	public int minimumJavaVersion() {
		return 14;
	}

	@Override
	public String description() {
		return "Replace non-fall-through switch groups with arrow labels";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<SwitchStmt> all = context.compilationUnit()
			.findAll(SwitchStmt.class)
			.stream()
			.filter(statement -> candidate(context, statement))
			.toList();
		List<SwitchStmt> candidates = all.stream()
			.filter(statement -> all.stream().noneMatch(other -> other != statement && other.isAncestorOf(statement)))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (SwitchStmt statement : candidates) {
			findings.add(Finding.at(statement, "Replace switch statement with enhanced switch"));
			if (applyFixes) {
				SwitchStmt replacement = statement.clone();
				mergeFallThroughLabels(replacement);
				for (SwitchEntry entry : replacement.getEntries()) {
					convert(entry);
				}
				context.editor()
					.replace(statement.getRange().orElseThrow(),
							indentLikeOriginal(context, statement, replacement.toString()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean candidate(InspectionContext context, SwitchStmt statement) {
		if (statement.getEntries().isEmpty() || AstSupport.hasComment(context, statement)
				|| statement.getEntries()
					.stream()
					.anyMatch(entry -> entry.getType() != SwitchEntry.Type.STATEMENT_GROUP)
				|| statement.getEntries().get(statement.getEntries().size() - 1).getStatements().isEmpty()
				|| statement.getEntries()
					.stream()
					.anyMatch(entry -> entry.getStatements().isEmpty() && entry.isDefault())) {
			return false;
		}
		List<SwitchEntry> groups = statement.getEntries()
			.stream()
			.filter(entry -> !entry.getStatements().isEmpty())
			.toList();
		if (hasCrossGroupLocalReferences(groups)) {
			return false;
		}
		for (int index = 0; index < groups.size() - 1; index++) {
			NodeList<Statement> statements = groups.get(index).getStatements();
			Statement last = statements.get(statements.size() - 1);
			if (last instanceof BreakStmt breakStatement && breakStatement.getLabel().isEmpty()
					|| last instanceof ReturnStmt || last instanceof ThrowStmt || last instanceof ContinueStmt) {
				continue;
			}
			return false;
		}
		return true;
	}

	private static boolean hasCrossGroupLocalReferences(List<SwitchEntry> groups) {
		for (int declarationGroup = 0; declarationGroup < groups.size(); declarationGroup++) {
			SwitchEntry declaringEntry = groups.get(declarationGroup);
			Set<String> declaredNames = declaringEntry.findAll(VariableDeclarator.class)
				.stream()
				.map(VariableDeclarator::getNameAsString)
				.collect(java.util.stream.Collectors.toSet());
			if (declaredNames.isEmpty()) {
				continue;
			}
			for (int referenceGroup = 0; referenceGroup < groups.size(); referenceGroup++) {
				if (referenceGroup == declarationGroup) {
					continue;
				}
				boolean referenced = groups.get(referenceGroup)
					.findAll(NameExpr.class)
					.stream()
					.map(NameExpr::getNameAsString)
					.anyMatch(declaredNames::contains);
				if (referenced) {
					return true;
				}
			}
		}
		return false;
	}

	private static void mergeFallThroughLabels(SwitchStmt statement) {
		NodeList<Expression> pending = new NodeList<>();
		ArrayList<SwitchEntry> emptyEntries = new ArrayList<>();
		for (SwitchEntry entry : statement.getEntries()) {
			if (entry.getStatements().isEmpty()) {
				entry.getLabels().forEach(label -> pending.add(label.clone()));
				emptyEntries.add(entry);
			}
			else if (!pending.isEmpty()) {
				NodeList<Expression> labels = new NodeList<>();
				pending.forEach(label -> labels.add(label.clone()));
				entry.getLabels().forEach(label -> labels.add(label.clone()));
				entry.setLabels(labels);
				pending.clear();
			}
		}
		emptyEntries.forEach(statement.getEntries()::remove);
	}

	private static void convert(SwitchEntry entry) {
		NodeList<Statement> statements = entry.getStatements();
		if (!statements.isEmpty() && statements.get(statements.size() - 1) instanceof BreakStmt breakStatement
				&& breakStatement.getLabel().isEmpty()) {
			statements.remove(statements.size() - 1);
		}
		if (statements.size() == 1 && statements.get(0) instanceof ExpressionStmt) {
			entry.setType(SwitchEntry.Type.EXPRESSION);
		}
		else if (statements.size() == 1 && statements.get(0) instanceof ThrowStmt) {
			entry.setType(SwitchEntry.Type.THROWS_STATEMENT);
		}
		else {
			BlockStmt block = new BlockStmt(
					new NodeList<>(statements.stream().map(statement -> statement.clone()).toList()));
			entry.setStatements(NodeList.nodeList(block));
			entry.setType(SwitchEntry.Type.BLOCK);
		}
	}

	private static String indentLikeOriginal(InspectionContext context, SwitchStmt node, String replacement) {
		String indent = " ".repeat(Math.max(0, node.getBegin().orElseThrow().column - 1));
		return LineEndingSupport.indentLikeSource(replacement, context.editor().source(), indent);
	}

}
