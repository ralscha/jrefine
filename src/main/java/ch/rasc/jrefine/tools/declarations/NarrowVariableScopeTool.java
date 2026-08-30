package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.SemanticEvidence;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Moves inert local declarations immediately before their first use. */
public final class NarrowVariableScopeTool implements InspectionTool {

	@Override
	public String id() {
		return "narrow-variable-scope";
	}

	@Override
	public String description() {
		return "Move safe local declarations closer to their first use";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BlockStmt.class)
			.stream()
			.map(block -> candidate(context, block))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String lineEnding = LineEndingSupport.detect(context.editor().source());
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.declaration(),
					"Local variable scope can start immediately before its first use"));
			if (applyFixes) {
				String indentation = " ".repeat(candidate.firstUse().getBegin().orElseThrow().column - 1);
				context.editor()
					.insert(candidate.firstUse().getBegin().orElseThrow(),
							context.editor().text(candidate.declaration()) + lineEnding + indentation);
				context.editor().removeLine(candidate.declaration());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BlockStmt block) {
		List<Statement> statements = block.getStatements();
		for (int declarationIndex = 0; declarationIndex < statements.size(); declarationIndex++) {
			Statement statement = statements.get(declarationIndex);
			if (!(statement instanceof ExpressionStmt declarationStatement)
					|| !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
					|| declaration.getVariables().size() != 1 || AstSupport.hasComment(context, declarationStatement)) {
				continue;
			}
			VariableDeclarator variable = declaration.getVariable(0);
			Expression initializer = variable.getInitializer().orElse(null);
			String name = variable.getNameAsString();
			if (initializer == null || !inertInitializer(context, initializer, variable)
					|| SemanticEvidence.isReassigned(context.compilationUnit(), name)
					|| shadowedInside(block, variable, name)) {
				continue;
			}
			List<NameExpr> references = block.findAll(NameExpr.class)
				.stream()
				.filter(reference -> reference.getNameAsString().equals(name))
				.filter(reference -> !declarationStatement.isAncestorOf(reference))
				.toList();
			Node boundary = assignmentBoundary(variable).orElse(null);
			if (references.isEmpty() || boundary == null || references.stream()
				.anyMatch(reference -> assignmentBoundary(reference).orElse(null) != boundary)) {
				continue;
			}
			int firstUseIndex = references.stream()
				.map(reference -> directStatement(reference, block))
				.flatMap(Optional::stream)
				.mapToInt(statements::indexOf)
				.min()
				.orElse(-1);
			if (firstUseIndex <= declarationIndex + 1) {
				continue;
			}
			Statement firstUse = statements.get(firstUseIndex);
			if (declarationStatement.getBegin().orElseThrow().line == firstUse.getBegin().orElseThrow().line) {
				continue;
			}
			return Optional.of(new Candidate(declarationStatement, firstUse));
		}
		return Optional.empty();
	}

	private static boolean inertInitializer(InspectionContext context, Expression initializer, Node use) {
		if (initializer.isLiteralExpr() || initializer instanceof ClassExpr || initializer.isThisExpr()) {
			return true;
		}
		return initializer instanceof NameExpr name
				&& SemanticEvidence.isEffectivelyFinalLocalOrParameter(context, name.getNameAsString(), use);
	}

	private static boolean shadowedInside(BlockStmt block, VariableDeclarator variable, String name) {
		return block.findAll(VariableDeclarator.class)
			.stream()
			.anyMatch(other -> other != variable && other.getNameAsString().equals(name))
				|| block.findAll(Parameter.class)
					.stream()
					.anyMatch(parameter -> parameter.getNameAsString().equals(name))
				|| block.findAll(TypePatternExpr.class)
					.stream()
					.anyMatch(pattern -> pattern.getNameAsString().equals(name));
	}

	private static Optional<Statement> directStatement(Node node, BlockStmt block) {
		Optional<Node> current = Optional.of(node);
		while (current.isPresent()) {
			Node value = current.orElseThrow();
			if (value.getParentNode().orElse(null) == block && value instanceof Statement statement) {
				return Optional.of(statement);
			}
			if (value == block) {
				return Optional.empty();
			}
			current = value.getParentNode();
		}
		return Optional.empty();
	}

	private static Optional<Node> assignmentBoundary(Node node) {
		Optional<Node> current = Optional.of(node);
		while (current.isPresent()) {
			Node value = current.orElseThrow();
			if (value instanceof CallableDeclaration<?> || value instanceof LambdaExpr
					|| value instanceof InitializerDeclaration) {
				return Optional.of(value);
			}
			current = value.getParentNode();
		}
		return Optional.empty();
	}

	private record Candidate(ExpressionStmt declaration, Statement firstUse) {
	}

}
