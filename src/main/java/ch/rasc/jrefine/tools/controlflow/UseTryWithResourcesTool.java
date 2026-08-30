package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.Range;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/**
 * Replaces a resource declaration plus close-only finally block with try-with-resources.
 */
public final class UseTryWithResourcesTool implements InspectionTool {

	private static final Set<String> RESOURCE_TYPES = Set.of("AutoCloseable", "Closeable", "InputStream",
			"OutputStream", "Reader", "Writer", "BufferedInputStream", "BufferedOutputStream", "BufferedReader",
			"BufferedWriter", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "PrintStream",
			"PrintWriter", "Scanner", "Formatter", "ZipFile", "JarFile", "Connection", "Statement", "PreparedStatement",
			"CallableStatement", "ResultSet");

	@Override
	public String id() {
		return "use-try-with-resources";
	}

	@Override
	public String description() {
		return "Replace close-only try-finally blocks with try-with-resources";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		for (BlockStmt block : context.compilationUnit().findAll(BlockStmt.class)) {
			for (int index = 1; index < block.getStatements().size(); index++) {
				if (block.getStatement(index) instanceof TryStmt statement) {
					candidate(context, block, index, statement).ifPresent(candidates::add);
				}
			}
		}
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.statement(), "Replace try-finally with try-with-resources"));
			if (applyFixes) {
				TryStmt replacement = candidate.statement().clone();
				replacement.getResources().add(candidate.declaration().clone());
				replacement.removeFinallyBlock();
				context.editor()
					.replace(candidate.range(),
							indentLikeOriginal(context, candidate.statement(), replacement.toString()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, BlockStmt owner, int index,
			TryStmt statement) {
		if (!statement.getResources().isEmpty() || statement.getFinallyBlock().isEmpty()
				|| AstSupport.hasComment(context, statement)
				|| !(owner.getStatement(index - 1) instanceof ExpressionStmt previous)
				|| !(previous.getExpression() instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1 || AstSupport.hasComment(context, previous)) {
			return Optional.empty();
		}
		VariableDeclarator variable = declaration.getVariable(0);
		if (variable.getInitializer().isEmpty() || !knownResource(context, variable.getType().asString())) {
			return Optional.empty();
		}
		BlockStmt finallyBlock = statement.getFinallyBlock().orElseThrow();
		if (finallyBlock.getStatements().size() != 1
				|| !(finallyBlock.getStatement(0) instanceof ExpressionStmt closeStatement)
				|| !(closeStatement.getExpression() instanceof MethodCallExpr close)
				|| !"close".equals(close.getNameAsString()) || !close.getArguments().isEmpty()
				|| !(close.getScope().orElse(null) instanceof NameExpr resource)
				|| !resource.getNameAsString().equals(variable.getNameAsString())) {
			return Optional.empty();
		}
		boolean invalidInside = statement.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(variable.getNameAsString()))
			.filter(name -> !statement.getTryBlock().isAncestorOf(name) && !close.isAncestorOf(name))
			.findAny()
			.isPresent();
		boolean usedAfter = owner.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(variable.getNameAsString()))
			.anyMatch(name -> name.getBegin().orElseThrow().isAfter(statement.getEnd().orElseThrow()));
		if (invalidInside || usedAfter) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(statement, declaration,
				new Range(previous.getRange().orElseThrow().begin, statement.getRange().orElseThrow().end)));
	}

	private static boolean knownResource(InspectionContext context, String type) {
		String raw = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
		int dot = raw.lastIndexOf('.');
		String simple = dot >= 0 ? raw.substring(dot + 1) : raw;
		if (!RESOURCE_TYPES.contains(simple) || context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(declaration -> declaration.getNameAsString().equals(simple))) {
			return false;
		}
		if (raw.contains(".")) {
			return raw.startsWith("java.") || raw.startsWith("javax.sql.");
		}
		if ("AutoCloseable".equals(simple)) {
			return true;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && (imported.getName().getIdentifier().equals(simple)
					&& (imported.getNameAsString().startsWith("java.")
							|| imported.getNameAsString().startsWith("javax.sql."))
					|| imported.isAsterisk() && (imported.getNameAsString().startsWith("java.")
							|| imported.getNameAsString().startsWith("javax.sql."))));
	}

	private static String indentLikeOriginal(InspectionContext context, TryStmt node, String replacement) {
		String indent = " ".repeat(Math.max(0, node.getBegin().orElseThrow().column - 1));
		return LineEndingSupport.indentLikeSource(replacement, context.editor().source(), indent);
	}

	private record Candidate(TryStmt statement, VariableDeclarationExpr declaration, Range range) {
	}

}
