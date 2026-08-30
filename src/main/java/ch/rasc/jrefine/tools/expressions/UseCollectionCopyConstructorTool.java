package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.stmt.Statement;

/** Folds an immediate addAll()/putAll() into a known JDK collection copy constructor. */
public final class UseCollectionCopyConstructorTool implements InspectionTool {

	private static final Set<String> COLLECTIONS = Set.of("ArrayList", "LinkedList", "HashSet", "LinkedHashSet",
			"ArrayDeque", "Vector");

	private static final Set<String> MAPS = Set.of("HashMap", "LinkedHashMap", "WeakHashMap", "IdentityHashMap",
			"Hashtable");

	@Override
	public String id() {
		return "use-collection-copy-constructor";
	}

	@Override
	public String description() {
		return "Fold immediate addAll() and putAll() calls into constructors";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		for (BlockStmt block : context.compilationUnit().findAll(BlockStmt.class)) {
			for (int index = 0; index + 1 < block.getStatements().size(); index++) {
				candidate(context, block.getStatement(index), block.getStatement(index + 1)).ifPresent(candidates::add);
			}
		}
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.call(), "Use collection copy constructor"));
			if (applyFixes) {
				JavaToken close = candidate.creation().getTokenRange().orElseThrow().getEnd();
				if (!")".equals(close.getText())) {
					throw new IllegalStateException("Expected collection constructor closing parenthesis");
				}
				context.editor()
					.insert(close.getRange().orElseThrow().begin,
							context.editor().text(candidate.call().getArgument(0)));
				context.editor().removeLine(candidate.callStatement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, Statement declarationNode,
			Statement callNode) {
		if (!(declarationNode instanceof ExpressionStmt declarationStatement)
				|| !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || !(callNode instanceof ExpressionStmt callStatement)
				|| !(callStatement.getExpression() instanceof MethodCallExpr call) || call.getArguments().size() != 1
				|| !(call.getScope().orElse(null) instanceof NameExpr receiver)
				|| AstSupport.hasComment(context, declarationStatement)
				|| AstSupport.hasComment(context, callStatement)) {
			return Optional.empty();
		}
		VariableDeclarator variable = declaration.getVariable(0);
		if (!(variable.getInitializer().orElse(null) instanceof ObjectCreationExpr creation)
				|| !creation.getArguments().isEmpty() || creation.getAnonymousClassBody().isPresent()
				|| !receiver.getNameAsString().equals(variable.getNameAsString())
				|| call.getArgument(0)
					.findAll(NameExpr.class)
					.stream()
					.anyMatch(name -> name.getNameAsString().equals(variable.getNameAsString()))) {
			return Optional.empty();
		}
		String type = ExpressionToolSupport.simpleName(creation.getType().asString());
		boolean collection = "addAll".equals(call.getNameAsString()) && COLLECTIONS.contains(type);
		boolean map = "putAll".equals(call.getNameAsString()) && MAPS.contains(type);
		if (!collection && !map || !ExpressionToolSupport.knownType(context.compilationUnit(),
				creation.getType().asString(), "java.util", Set.of(type))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(creation, call, callStatement));
	}

	private record Candidate(ObjectCreationExpr creation, MethodCallExpr call, ExpressionStmt callStatement) {
	}

}
