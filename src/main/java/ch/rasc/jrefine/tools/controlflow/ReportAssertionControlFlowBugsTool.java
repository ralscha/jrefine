package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reports assertion, reachability, loop, and short-circuit mistakes. */
public final class ReportAssertionControlFlowBugsTool implements InspectionTool {

	private static final Set<String> NON_SHORT_TERMINALS = Set.of("collect", "count", "forEach", "forEachOrdered",
			"min", "max", "reduce", "sum", "average", "summaryStatistics", "toArray", "toList");

	@Override
	public String id() {
		return "report-assertion-control-flow-bugs";
	}

	@Override
	public String description() {
		return "Report assertion side effects, constant flow, unreachable code, and suspicious loops";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		assertions(context, findings);
		constantConditions(context, findings);
		nonShortCircuit(context, findings);
		emptyBodies(context, findings);
		unreachable(context, findings);
		recursion(context, findings);
		loops(context, findings);
		labels(context, findings);
		indentation(context, findings);
		infiniteStreams(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void assertions(InspectionContext context, List<Finding> findings) {
		for (AssertStmt statement : context.compilationUnit().findAll(AssertStmt.class)) {
			Expression check = statement.getCheck();
			if (sideEffect(check)) {
				findings.add(Finding.at(statement, "'assert' statement condition has side effects"));
			}
			if (check instanceof BooleanLiteralExpr) {
				findings.add(Finding.at(statement, "Constant condition in 'assert' statement"));
			}
		}
	}

	private static boolean sideEffect(Node node) {
		return !node.findAll(AssignExpr.class).isEmpty() || node.findAll(UnaryExpr.class)
			.stream()
			.anyMatch(unary -> Set
				.of(UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_INCREMENT,
						UnaryExpr.Operator.PREFIX_DECREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT)
				.contains(unary.getOperator()));
	}

	private static void constantConditions(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(IfStmt.class)
			.stream()
			.filter(statement -> constant(statement.getCondition()))
			.forEach(statement -> findings
				.add(Finding.at(statement.getCondition(), "Condition always has the same value")));
		context.compilationUnit()
			.findAll(WhileStmt.class)
			.stream()
			.filter(statement -> constant(statement.getCondition()) && !trueLiteral(statement.getCondition()))
			.forEach(statement -> findings
				.add(Finding.at(statement.getCondition(), "Loop condition always has the same value")));
		context.compilationUnit()
			.findAll(DoStmt.class)
			.stream()
			.filter(statement -> constant(statement.getCondition()) && !trueLiteral(statement.getCondition()))
			.forEach(statement -> findings
				.add(Finding.at(statement.getCondition(), "Loop condition always has the same value")));
		context.compilationUnit()
			.findAll(ConditionalExpr.class)
			.stream()
			.filter(expression -> constant(expression.getCondition()))
			.forEach(expression -> findings
				.add(Finding.at(expression.getCondition(), "Conditional expression always selects the same branch")));
	}

	private static boolean constant(Expression expression) {
		if (expression instanceof BooleanLiteralExpr) {
			return true;
		}
		if (expression instanceof BinaryExpr binary && binary.getLeft().equals(binary.getRight())) {
			return Set.of(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.LESS,
					BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS)
				.contains(binary.getOperator()) && !sideEffect(binary.getLeft());
		}
		return false;
	}

	private static boolean trueLiteral(Expression expression) {
		return expression instanceof BooleanLiteralExpr literal && literal.getValue();
	}

	private static void nonShortCircuit(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(BinaryExpr.class)
			.stream()
			.filter(binary -> binary.getOperator() == BinaryExpr.Operator.BINARY_AND
					|| binary.getOperator() == BinaryExpr.Operator.BINARY_OR)
			.filter(binary -> booleanLike(binary.getLeft()) || booleanLike(binary.getRight()))
			.forEach(binary -> findings
				.add(Finding.at(binary, "Non-short-circuit boolean expression may evaluate an unnecessary operand")));
		context.compilationUnit()
			.findAll(AssignExpr.class)
			.stream()
			.filter(assignment -> assignment.getOperator() == AssignExpr.Operator.BINARY_AND
					|| assignment.getOperator() == AssignExpr.Operator.BINARY_OR)
			.filter(assignment -> booleanLike(assignment.getValue()))
			.forEach(assignment -> findings.add(Finding.at(assignment, "Non-short-circuit boolean assignment")));
	}

	private static boolean booleanLike(Expression expression) {
		return expression instanceof BooleanLiteralExpr
				|| expression instanceof BinaryExpr binary && Set
					.of(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS, BinaryExpr.Operator.LESS,
							BinaryExpr.Operator.LESS_EQUALS, BinaryExpr.Operator.GREATER,
							BinaryExpr.Operator.GREATER_EQUALS, BinaryExpr.Operator.AND, BinaryExpr.Operator.OR)
					.contains(binary.getOperator())
				|| expression instanceof UnaryExpr unary
						&& unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT;
	}

	private static void emptyBodies(InspectionContext context, List<Finding> findings) {
		context.compilationUnit().findAll(EmptyStmt.class).forEach(statement -> {
			if (statement.getParentNode()
				.filter(parent -> parent instanceof IfStmt || parent instanceof WhileStmt || parent instanceof DoStmt
						|| parent instanceof ForStmt)
				.isPresent()) {
				findings.add(Finding.at(statement, "Control statement has an empty body"));
			}
		});
		context.compilationUnit()
			.findAll(IfStmt.class)
			.stream()
			.filter(statement -> empty(statement.getThenStmt()))
			.forEach(statement -> findings.add(Finding.at(statement, "'if' statement has an empty body")));
		context.compilationUnit()
			.findAll(WhileStmt.class)
			.stream()
			.filter(statement -> empty(statement.getBody()))
			.forEach(statement -> findings.add(Finding.at(statement, "'while' statement has an empty body")));
		context.compilationUnit()
			.findAll(ForStmt.class)
			.stream()
			.filter(statement -> empty(statement.getBody()))
			.forEach(statement -> findings.add(Finding.at(statement, "'for' statement has an empty body")));
	}

	private static boolean empty(Statement statement) {
		return statement instanceof EmptyStmt
				|| statement instanceof BlockStmt block && block.getStatements().isEmpty();
	}

	private static void unreachable(InspectionContext context, List<Finding> findings) {
		for (BlockStmt block : context.compilationUnit().findAll(BlockStmt.class)) {
			boolean stopped = false;
			for (Statement statement : block.getStatements()) {
				if (stopped) {
					findings.add(Finding.at(statement, "Unreachable code after unconditional control transfer"));
					break;
				}
				stopped = statement instanceof ReturnStmt || statement instanceof ThrowStmt
						|| statement instanceof BreakStmt || statement instanceof ContinueStmt;
			}
		}
	}

	private static void recursion(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			if (method.getBody().isEmpty() || method.getBody().orElseThrow().getStatements().size() != 1) {
				continue;
			}
			Statement only = method.getBody().orElseThrow().getStatement(0);
			List<MethodCallExpr> calls = only.findAll(MethodCallExpr.class);
			if (calls.size() == 1 && calls.getFirst().getNameAsString().equals(method.getNameAsString())
					&& calls.getFirst().getArguments().size() == method.getParameters().size()
					&& (calls.getFirst().getScope().isEmpty() || calls.getFirst().getScope().orElseThrow().isThisExpr())
					&& noCompetingOverload(method, calls.getFirst())) {
				findings.add(Finding.at(method, "Method appears to recurse without a terminating path"));
			}
		}
	}

	private static boolean noCompetingOverload(MethodDeclaration method, MethodCallExpr call) {
		ClassOrInterfaceDeclaration owner = AstSupport.ancestor(method, ClassOrInterfaceDeclaration.class).orElse(null);
		return owner == null || owner.getMethodsByName(method.getNameAsString())
			.stream()
			.filter(candidate -> candidate != method)
			.noneMatch(candidate -> candidate.getParameters().size() == call.getArguments().size());
	}

	private static void loops(InspectionContext context, List<Finding> findings) {
		for (ForStmt loop : context.compilationUnit().findAll(ForStmt.class)) {
			if (loop.getCompare().filter(ReportAssertionControlFlowBugsTool::constant).isPresent()) {
				findings.add(Finding.at(loop, "Loop executes zero times or cannot terminate normally"));
			}
			String source = context.editor().text(loop).replace("_", "");
			if ((source.contains("<= Integer.MAX_VALUE") || source.contains("<= 2147483647"))
					&& (source.contains("++") || source.contains("+= 1"))) {
				findings.add(Finding.at(loop, "Loop requires index overflow and may execute billions of times"));
			}
		}
	}

	private static void labels(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(LabeledStmt.class)
			.stream()
			.filter(label -> AstSupport.ancestor(label, SwitchStmt.class).isPresent())
			.forEach(label -> findings.add(Finding.at(label, "Text label appears inside a switch statement")));
	}

	private static void indentation(InspectionContext context, List<Finding> findings) {
		for (BlockStmt block : context.compilationUnit().findAll(BlockStmt.class)) {
			for (int index = 0; index + 1 < block.getStatements().size(); index++) {
				Statement control = block.getStatement(index);
				Statement body = unbracedBody(control).orElse(null);
				Statement next = block.getStatement(index + 1);
				if (body == null || body.getBegin().isEmpty() || next.getBegin().isEmpty()
						|| control.getBegin().isEmpty()) {
					continue;
				}
				if (next.getBegin().orElseThrow().column == body.getBegin().orElseThrow().column
						&& next.getBegin().orElseThrow().column > control.getBegin().orElseThrow().column) {
					findings.add(Finding.at(next, "Suspicious indentation after control statement without braces"));
				}
			}
		}
	}

	private static Optional<Statement> unbracedBody(Statement statement) {
		if (statement instanceof IfStmt value && !(value.getThenStmt() instanceof BlockStmt)) {
			return Optional.of(value.getThenStmt());
		}
		if (statement instanceof WhileStmt value && !(value.getBody() instanceof BlockStmt)) {
			return Optional.of(value.getBody());
		}
		if (statement instanceof ForStmt value && !(value.getBody() instanceof BlockStmt)) {
			return Optional.of(value.getBody());
		}
		return Optional.empty();
	}

	private static void infiniteStreams(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> NON_SHORT_TERMINALS.contains(call.getNameAsString()))
			.filter(call -> call.getScope().filter(ReportAssertionControlFlowBugsTool::infinitePipeline).isPresent())
			.forEach(
					call -> findings.add(Finding.at(call, "Non-short-circuit operation consumes an unbounded Stream")));
	}

	private static boolean infinitePipeline(Expression expression) {
		if (!(expression instanceof MethodCallExpr call)) {
			return false;
		}
		if (Set.of("limit", "takeWhile").contains(call.getNameAsString())) {
			return false;
		}
		if (Set.of("generate", "iterate").contains(call.getNameAsString()) && call.getScope()
			.filter(scope -> "Stream".equals(scope.toString()) || "java.util.stream.Stream".equals(scope.toString()))
			.isPresent()) {
			return true;
		}
		return call.getScope().filter(ReportAssertionControlFlowBugsTool::infinitePipeline).isPresent();
	}

}
