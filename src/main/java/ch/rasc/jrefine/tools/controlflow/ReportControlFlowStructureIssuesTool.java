package ch.rasc.jrefine.tools.controlflow;

import java.util.Set;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;

/** Reports structural switch, branch, jump, label, and loop-style issues. */
public final class ReportControlFlowStructureIssuesTool implements PolicyInspectionTool {

	@Override
	public String id() {
		return "report-control-flow-structure-issues";
	}

	@Override
	public String description() {
		return "Report suspicious jumps, labels, switches, branch counts, and incomplete for loops";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		jumps(context, findings);
		labels(context, findings);
		forLoops(context, findings);
		branches(context, findings);
		switches(context, findings);
		assertions(context, findings);
		whileCandidates(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void jumps(InspectionContext context, List<Finding> findings) {
		for (BreakStmt statement : context.compilationUnit().findAll(BreakStmt.class)) {
			if (statement.getLabel().isPresent()) {
				findings.add(Finding.at(statement, "'break' statement with label complicates control flow"));
			}
		}
		for (ContinueStmt statement : context.compilationUnit().findAll(ContinueStmt.class)) {
			if (statement.getLabel().isPresent()) {
				findings.add(Finding.at(statement, "'continue' statement with label complicates control flow"));
			}
		}
	}

	private static void labels(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(LabeledStmt.class)
			.forEach(label -> findings.add(Finding.at(label, "Labeled statement complicates control flow")));
	}

	private static void forLoops(InspectionContext context, List<Finding> findings) {
		for (ForStmt loop : context.compilationUnit().findAll(ForStmt.class)) {
			if (loop.getInitialization().isEmpty() && loop.getUpdate().isEmpty()) {
				findings.add(Finding.at(loop, "'for' loop may be replaced by a 'while' loop"));
			}
			if (loop.getInitialization().isEmpty() || loop.getCompare().isEmpty() || loop.getUpdate().isEmpty()) {
				findings.add(Finding.at(loop, "'for' loop has one or more missing components"));
			}
		}
	}

	private static void branches(InspectionContext context, List<Finding> findings) {
		for (IfStmt statement : context.compilationUnit().findAll(IfStmt.class)) {
			int count = 1;
			IfStmt current = statement;
			while (current.getElseStmt().orElse(null) instanceof IfStmt next) {
				count++;
				current = next;
			}
			if (count > 5) {
				findings.add(Finding.at(statement, "'if' statement has too many branches"));
			}
		}
	}

	private static void switches(InspectionContext context, List<Finding> findings) {
		for (SwitchStmt statement : context.compilationUnit().findAll(SwitchStmt.class)) {
			inspectEntries(context, statement, statement.getEntries(), statement.getSelector(), findings);
			if (AstSupport.ancestor(statement, SwitchStmt.class).isPresent()
					|| AstSupport.ancestor(statement, SwitchExpr.class).isPresent()) {
				findings.add(Finding.at(statement, "Nested switch statement"));
			}
		}
		for (SwitchExpr expression : context.compilationUnit().findAll(SwitchExpr.class)) {
			inspectEntries(context, expression, expression.getEntries(), expression.getSelector(), findings);
			if (AstSupport.ancestor(expression, SwitchStmt.class).isPresent()
					|| AstSupport.ancestor(expression, SwitchExpr.class).isPresent()) {
				findings.add(Finding.at(expression, "Nested switch expression"));
			}
		}
	}

	private static void inspectEntries(InspectionContext context, Node owner, NodeList<SwitchEntry> entries,
			Expression selector, List<Finding> findings) {
		int defaultIndex = -1;
		int labels = 0;
		int statements = 0;
		for (int index = 0; index < entries.size(); index++) {
			SwitchEntry entry = entries.get(index);
			if (entry.getLabels().isEmpty()) {
				defaultIndex = index;
			}
			labels += Math.max(1, entry.getLabels().size());
			statements += entry.getStatements().size();
			if (index + 1 < entries.size() && statementGroup(entry) && fallsThrough(entry)) {
				findings.add(Finding.at(entry, "Fallthrough in switch branch"));
			}
		}
		if (defaultIndex >= 0 && defaultIndex != entries.size() - 1) {
			findings.add(Finding.at(entries.get(defaultIndex), "'default' is not the last switch branch"));
		}
		if (statements > 0 && labels * 4 < statements) {
			findings.add(Finding.at(owner, "Switch has too low a branch density"));
		}
		enumCoverage(context, owner, entries, selector, findings);
		crossBranchVariables(entries, findings);
	}

	private static boolean statementGroup(SwitchEntry entry) {
		return "STATEMENT_GROUP".equals(entry.getType().name());
	}

	private static boolean fallsThrough(SwitchEntry entry) {
		if (entry.getStatements().isEmpty()) {
			return true;
		}
		Statement last = entry.getStatement(entry.getStatements().size() - 1);
		return !(last instanceof BreakStmt || last instanceof ReturnStmt || last instanceof ThrowStmt);
	}

	private static void enumCoverage(InspectionContext context, Node owner, NodeList<SwitchEntry> entries,
			Expression selector, List<Finding> findings) {
		String type = TypeLookup.visibleType(context.compilationUnit(), selector, owner).orElse("");
		String simple = simple(type);
		EnumDeclaration declaration = context.compilationUnit()
			.findAll(EnumDeclaration.class)
			.stream()
			.filter(value -> value.getNameAsString().equals(simple))
			.findFirst()
			.orElse(null);
		if (declaration == null) {
			return;
		}
		Set<String> covered = entries.stream()
			.flatMap(entry -> entry.getLabels().stream())
			.map(Object::toString)
			.map(ReportControlFlowStructureIssuesTool::simple)
			.collect(java.util.stream.Collectors.toSet());
		List<String> missing = declaration.getEntries()
			.stream()
			.map(value -> value.getNameAsString())
			.filter(value -> !covered.contains(value))
			.toList();
		if (!missing.isEmpty()) {
			findings.add(Finding.at(owner, "Enum switch misses cases: " + String.join(", ", missing)));
		}
	}

	private static void crossBranchVariables(NodeList<SwitchEntry> entries, List<Finding> findings) {
		for (int index = 0; index < entries.size(); index++) {
			Set<String> declared = entries.get(index)
				.findAll(VariableDeclarator.class)
				.stream()
				.map(VariableDeclarator::getNameAsString)
				.collect(java.util.stream.Collectors.toSet());
			if (declared.isEmpty()) {
				continue;
			}
			for (int other = 0; other < entries.size(); other++) {
				if (other == index) {
					continue;
				}
				entries.get(other)
					.findAll(NameExpr.class)
					.stream()
					.filter(name -> declared.contains(name.getNameAsString()))
					.forEach(name -> findings
						.add(Finding.at(name, "Local variable is used and declared in different switch branches")));
			}
		}
	}

	private static void assertions(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(AssertStmt.class)
			.forEach(statement -> findings
				.add(Finding.at(statement, "Assertion can be replaced with an if statement throwing AssertionError")));
	}

	private static void whileCandidates(InspectionContext context, List<Finding> findings) {
		for (BlockStmt block : context.compilationUnit().findAll(BlockStmt.class)) {
			for (int index = 1; index < block.getStatements().size(); index++) {
				if (!(block.getStatement(index) instanceof WhileStmt loop)) {
					continue;
				}
				Statement prior = block.getStatement(index - 1);
				if (prior instanceof IfStmt guard && guard.getElseStmt().isEmpty() && terminating(guard.getThenStmt())
						&& negates(guard.getCondition(), loop.getCondition())) {
					findings
						.add(Finding.at(loop, "'while' loop is proven to execute and can be replaced with 'do while'"));
				}
			}
		}
	}

	private static boolean terminating(Statement statement) {
		if (statement instanceof ReturnStmt || statement instanceof ThrowStmt) {
			return true;
		}
		return statement instanceof BlockStmt block && block.getStatements().size() == 1
				&& terminating(block.getStatement(0));
	}

	private static boolean negates(Expression guard, Expression condition) {
		return guard instanceof UnaryExpr unary
				&& unary.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.LOGICAL_COMPLEMENT
				&& unwrap(unary.getExpression()).equals(unwrap(condition));
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression.isEnclosedExpr()) {
			currentExpression = currentExpression.asEnclosedExpr().getInner();
		}
		return currentExpression;
	}

	private static String simple(String type) {
		int dot = type.lastIndexOf('.');
		return dot < 0 ? type : type.substring(dot + 1);
	}

}
