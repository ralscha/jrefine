package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.UnionType;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reports exception and finally control flow that can hide failures or discard
 * exceptions.
 */
public final class ReportExceptionFlowBugsTool implements InspectionTool {

	@Override
	public String id() {
		return "report-exception-flow-bugs";
	}

	@Override
	public String description() {
		return "Report swallowed fatal errors, null throws, and exception-discarding finally flow";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		nullThrows(context, findings);
		tryStatements(context, findings);
		catchClauses(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void nullThrows(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(ThrowStmt.class)
			.stream()
			.filter(statement -> statement.getExpression() instanceof NullLiteralExpr)
			.forEach(statement -> findings.add(Finding.at(statement, "Throwing null produces a NullPointerException")));
	}

	private static void tryStatements(InspectionContext context, List<Finding> findings) {
		for (TryStmt statement : context.compilationUnit().findAll(TryStmt.class)) {
			if (statement.getTryBlock().getStatements().isEmpty()
					&& !AstSupport.hasComment(context, statement.getTryBlock())) {
				findings.add(Finding.at(statement.getTryBlock(), "Empty try block"));
			}
			if (statement.getFinallyBlock().isEmpty()) {
				continue;
			}
			BlockStmt block = statement.getFinallyBlock().orElseThrow();
			if (block.getStatements().isEmpty() && !AstSupport.hasComment(context, block)) {
				findings.add(Finding.at(block, "Empty finally block"));
				continue;
			}
			finallyTransfers(block, findings);
			if (!canCompleteNormally(block)) {
				findings.add(Finding.at(block, "Finally block cannot complete normally"));
			}
		}
	}

	private static void finallyTransfers(BlockStmt block, List<Finding> findings) {
		block.findAll(ReturnStmt.class)
			.stream()
			.filter(statement -> directlyWithin(statement, block))
			.forEach(statement -> findings
				.add(Finding.at(statement, "Return inside finally discards a pending exception")));
		block.findAll(ThrowStmt.class)
			.stream()
			.filter(statement -> directlyWithin(statement, block))
			.filter(statement -> !caughtInsideFinally(statement, block))
			.forEach(statement -> findings
				.add(Finding.at(statement, "Throw inside finally can replace a pending exception")));
		block.findAll(BreakStmt.class)
			.stream()
			.filter(statement -> directlyWithin(statement, block))
			.filter(statement -> escapesFinally(statement, block))
			.forEach(statement -> findings
				.add(Finding.at(statement, "Break exits from inside finally and can discard a pending exception")));
		block.findAll(ContinueStmt.class)
			.stream()
			.filter(statement -> directlyWithin(statement, block))
			.filter(statement -> escapesFinally(statement, block))
			.forEach(statement -> findings
				.add(Finding.at(statement, "Continue exits from inside finally and can discard a pending exception")));
	}

	private static boolean caughtInsideFinally(ThrowStmt thrown, BlockStmt finallyBlock) {
		Node current = thrown;
		while (current != finallyBlock) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null) {
				return false;
			}
			if (parent instanceof TryStmt nested && nested.getTryBlock().isAncestorOf(thrown)
					&& !nested.getCatchClauses().isEmpty()) {
				return true;
			}
			current = parent;
		}
		return false;
	}

	private static void catchClauses(InspectionContext context, List<Finding> findings) {
		for (CatchClause clause : context.compilationUnit().findAll(CatchClause.class)) {
			String parameter = clause.getParameter().getNameAsString();
			if (immediatelyRethrows(clause, parameter)) {
				findings.add(Finding.at(clause, "Caught exception is immediately rethrown"));
			}
			if (rethrows(clause, parameter)) {
				continue;
			}
			for (ReferenceType type : caughtTypes(clause)) {
				String spelling = type.asString();
				if (TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.lang", Set.of("Error"))) {
					findings.add(Finding.at(clause, "Caught Error is not rethrown"));
					break;
				}
				if (TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.lang", Set.of("ThreadDeath"))) {
					findings.add(Finding.at(clause, "Caught ThreadDeath is not rethrown"));
					break;
				}
			}
		}
	}

	private static List<ReferenceType> caughtTypes(CatchClause clause) {
		if (clause.getParameter().getType() instanceof UnionType union) {
			return List.copyOf(union.getElements());
		}
		return List.of(clause.getParameter().getType().asReferenceType());
	}

	private static boolean immediatelyRethrows(CatchClause clause, String parameter) {
		if (clause.getBody().getStatements().size() != 1
				|| !(clause.getBody().getStatement(0) instanceof ThrowStmt thrown)
				|| !(thrown.getExpression() instanceof NameExpr name)) {
			return false;
		}
		return name.getNameAsString().equals(parameter);
	}

	private static boolean rethrows(CatchClause clause, String parameter) {
		return clause.getBody()
			.findAll(ThrowStmt.class)
			.stream()
			.filter(statement -> directlyWithin(statement, clause.getBody()))
			.map(ThrowStmt::getExpression)
			.filter(NameExpr.class::isInstance)
			.map(NameExpr.class::cast)
			.anyMatch(name -> name.getNameAsString().equals(parameter));
	}

	private static boolean directlyWithin(Node node, Node owner) {
		Node current = node;
		while (current != owner) {
			Node parent = current.getParentNode().orElse(null);
			if (parent == null || parent instanceof LambdaExpr || parent instanceof CallableDeclaration<?>
					|| parent instanceof TypeDeclaration<?>) {
				return false;
			}
			current = parent;
		}
		return true;
	}

	private static boolean escapesFinally(BreakStmt statement, BlockStmt block) {
		Node current = statement;
		String label = statement.getLabel().map(value -> value.asString()).orElse(null);
		while (current != block) {
			current = current.getParentNode().orElse(block);
			if (label != null && current instanceof LabeledStmt labeled
					&& labeled.getLabel().asString().equals(label)) {
				return false;
			}
			if (label == null && (current instanceof ForStmt || current instanceof ForEachStmt
					|| current instanceof WhileStmt || current instanceof DoStmt || current instanceof SwitchStmt)) {
				return false;
			}
		}
		return true;
	}

	private static boolean escapesFinally(ContinueStmt statement, BlockStmt block) {
		Node current = statement;
		String label = statement.getLabel().map(value -> value.asString()).orElse(null);
		while (current != block) {
			current = current.getParentNode().orElse(block);
			if (label != null && current instanceof LabeledStmt labeled && labeled.getLabel().asString().equals(label)
					&& (labeled.getStatement() instanceof ForStmt || labeled.getStatement() instanceof ForEachStmt
							|| labeled.getStatement() instanceof WhileStmt
							|| labeled.getStatement() instanceof DoStmt)) {
				return false;
			}
			if (label == null && (current instanceof ForStmt || current instanceof ForEachStmt
					|| current instanceof WhileStmt || current instanceof DoStmt)) {
				return false;
			}
		}
		return true;
	}

	private static boolean canCompleteNormally(BlockStmt block) {
		if (block.getStatements().isEmpty()) {
			return true;
		}
		return canCompleteNormally(block.getStatement(block.getStatements().size() - 1), block);
	}

	private static boolean canCompleteNormally(Statement statement, BlockStmt finallyBlock) {
		if (statement instanceof ReturnStmt || statement instanceof ThrowStmt) {
			return false;
		}
		if (statement instanceof BreakStmt broken) {
			return !escapesFinally(broken, finallyBlock);
		}
		if (statement instanceof ContinueStmt continued) {
			return !escapesFinally(continued, finallyBlock);
		}
		if (statement instanceof BlockStmt nested) {
			return canCompleteNormally(nested);
		}
		if (statement instanceof IfStmt conditional && conditional.getElseStmt().isPresent()) {
			return canCompleteNormally(conditional.getThenStmt(), finallyBlock)
					|| canCompleteNormally(conditional.getElseStmt().orElseThrow(), finallyBlock);
		}
		return true;
	}

}
