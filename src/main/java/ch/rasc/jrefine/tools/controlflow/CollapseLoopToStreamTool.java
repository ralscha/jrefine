package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.Range;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.SemanticEvidence;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Collapses canonical enhanced-for search and filtering loops into stream pipelines. */
public final class CollapseLoopToStreamTool implements InspectionTool {

	private static final Set<String> COLLECTION_TYPES = Set.of("Collection", "List", "Set", "SortedSet", "NavigableSet",
			"Queue", "Deque", "ArrayList", "LinkedList", "Vector", "HashSet", "LinkedHashSet", "TreeSet");

	@Override
	public String id() {
		return "collapse-loop-to-stream";
	}

	@Override
	public String description() {
		return "Collapse eligible enhanced-for loops into Stream API calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		for (BlockStmt block : context.compilationUnit().findAll(BlockStmt.class)) {
			for (int index = 0; index < block.getStatements().size(); index++) {
				if (!(block.getStatement(index) instanceof ForEachStmt loop)) {
					continue;
				}
				Optional<Candidate> terminal = index + 1 < block.getStatements().size()
						? allMatchCandidate(context, loop, block.getStatement(index + 1)) : Optional.<Candidate>empty();
				if (terminal.isPresent()) {
					candidates.add(terminal.orElseThrow());
				}
				else {
					filterCandidate(context, loop).ifPresent(candidates::add);
				}
			}
		}
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.loop(), "Collapse loop into Stream API"));
			if (applyFixes) {
				context.editor().replace(candidate.range(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> allMatchCandidate(InspectionContext context, ForEachStmt loop, Statement next) {
		if (!(next instanceof ReturnStmt fallback) || fallback.getExpression().isEmpty()
				|| !(fallback.getExpression().orElseThrow() instanceof BooleanLiteralExpr fallbackValue)
				|| !(loop.getBody() instanceof BlockStmt body) || body.getStatements().isEmpty()
				|| body.getStatements().size() > 2 || !knownCollection(context, loop)
				|| AstSupport.hasComment(context, loop) || AstSupport.hasComment(context, next)) {
			return Optional.empty();
		}
		String item = loop.getVariable().getVariable(0).getNameAsString();
		String stream = context.editor().text(loop.getIterable()) + ".stream()";
		String terminalName = item;
		IfStmt conditional;
		if (body.getStatements().size() == 2) {
			if (!(body.getStatement(0) instanceof ExpressionStmt declarationStatement)
					|| !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
					|| declaration.getVariables().size() != 1 || declaration.getVariable(0).getInitializer().isEmpty()
					|| !(body.getStatement(1) instanceof IfStmt value)) {
				return Optional.empty();
			}
			VariableDeclarator mapped = declaration.getVariable(0);
			stream += ".map(" + item + " -> " + context.editor().text(mapped.getInitializer().orElseThrow()) + ")";
			terminalName = mapped.getNameAsString();
			conditional = value;
		}
		else if (body.getStatement(0) instanceof IfStmt value) {
			conditional = value;
		}
		else {
			return Optional.empty();
		}
		if (conditional.getElseStmt().isPresent()) {
			return Optional.empty();
		}
		ReturnStmt returned = singleReturn(conditional.getThenStmt()).orElse(null);
		if (returned == null || returned.getExpression().isEmpty()
				|| !(returned.getExpression().orElseThrow() instanceof BooleanLiteralExpr early)
				|| early.getValue() == fallbackValue.getValue()) {
			return Optional.empty();
		}
		String condition = context.editor().text(conditional.getCondition());
		String operation;
		String predicate;
		if (early.getValue()) {
			operation = "anyMatch";
			predicate = condition;
		}
		else {
			operation = "allMatch";
			if (conditional.getCondition() instanceof UnaryExpr unary
					&& unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
				predicate = context.editor().text(unary.getExpression());
			}
			else {
				predicate = "!(" + condition + ")";
			}
		}
		String replacement = "return " + stream + "." + operation + "(" + terminalName + " -> " + predicate + ");";
		return Optional.of(new Candidate(loop,
				new Range(loop.getRange().orElseThrow().begin, next.getRange().orElseThrow().end), replacement));
	}

	private static Optional<Candidate> filterCandidate(InspectionContext context, ForEachStmt loop) {
		if (!knownCollection(context, loop) || AstSupport.hasComment(context, loop)
				|| !(singleStatement(loop.getBody()).orElse(null) instanceof IfStmt conditional)
				|| conditional.getElseStmt().isPresent()
				|| !(singleStatement(conditional.getThenStmt()).orElse(null) instanceof ExpressionStmt action)) {
			return Optional.empty();
		}
		String item = loop.getVariable().getVariable(0).getNameAsString();
		String replacement = context.editor().text(loop.getIterable()) + ".stream().filter(" + item + " -> "
				+ context.editor().text(conditional.getCondition()) + ").forEach(" + item + " -> "
				+ context.editor().text(action.getExpression()) + ");";
		return Optional.of(new Candidate(loop, loop.getRange().orElseThrow(), replacement));
	}

	private static boolean knownCollection(InspectionContext context, ForEachStmt loop) {
		return loop.getVariable().getVariables().size() == 1 && !capturesReassignedLocal(context, loop)
				&& TypeLookup.visibleType(context.compilationUnit(), loop.getIterable(), loop)
					.filter(type -> TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type, COLLECTION_TYPES))
					.isPresent();
	}

	private static boolean capturesReassignedLocal(InspectionContext context, ForEachStmt loop) {
		Set<String> declaredInside = loop.findAll(VariableDeclarator.class)
			.stream()
			.map(VariableDeclarator::getNameAsString)
			.collect(java.util.stream.Collectors.toSet());
		return loop.getBody()
			.findAll(NameExpr.class)
			.stream()
			.map(NameExpr::getNameAsString)
			.filter(name -> !declaredInside.contains(name))
			.filter(name -> TypeLookup.isVisibleLocalOrParameterIncludingCaptured(context.compilationUnit(), name,
					loop))
			.distinct()
			.anyMatch(name -> !SemanticEvidence.isEffectivelyFinalLocalOrParameter(context, name, loop));
	}

	private static Optional<ReturnStmt> singleReturn(Statement statement) {
		return singleStatement(statement).filter(ReturnStmt.class::isInstance).map(ReturnStmt.class::cast);
	}

	private static Optional<Statement> singleStatement(Statement statement) {
		if (statement instanceof BlockStmt block) {
			return block.getStatements().size() == 1 ? Optional.of(block.getStatement(0)) : Optional.empty();
		}
		return Optional.of(statement);
	}

	private record Candidate(ForEachStmt loop, Range range, String replacement) {
	}

}
