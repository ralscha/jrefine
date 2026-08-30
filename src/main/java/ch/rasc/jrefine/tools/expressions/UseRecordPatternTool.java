package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replaces an instanceof record binding followed by accessor locals with a record
 * pattern.
 */
public final class UseRecordPatternTool implements InspectionTool {

	@Override
	public String id() {
		return "use-record-pattern";
	}

	@Override
	public int minimumJavaVersion() {
		return 21;
	}

	@Override
	public String description() {
		return "Use Java 21 record patterns for immediate deconstruction";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<String, RecordDeclaration> records = uniqueRecords(context);
		List<Candidate> candidates = context.compilationUnit()
			.findAll(InstanceOfExpr.class)
			.stream()
			.map(expression -> candidate(context, expression, records))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.instanceOf(), "Replace binding pattern with record pattern"));
			if (applyFixes) {
				String replacement = context.editor().text(candidate.pattern().getType()) + "("
						+ candidate.names()
							.stream()
							.map(name -> "var " + name)
							.reduce((left, right) -> left + ", " + right)
							.orElse("")
						+ ")";
				context.editor().replace(candidate.pattern().getRange().orElseThrow(), replacement);
				for (ExpressionStmt declaration : candidate.declarations()) {
					context.editor().removeLine(declaration);
				}
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, InstanceOfExpr expression,
			Map<String, RecordDeclaration> records) {
		if (!(expression.getPattern().orElse(null) instanceof TypePatternExpr pattern)
				|| !pattern.getModifiers().isEmpty() || AstSupport.hasComment(context, pattern)
				|| !(expression.getParentNode().orElse(null) instanceof IfStmt ifStatement)
				|| ifStatement.getCondition() != expression
				|| !(ifStatement.getThenStmt() instanceof BlockStmt block)) {
			return Optional.empty();
		}
		RecordDeclaration record = records.get(simpleType(pattern.getType().asString()));
		if (record == null || block.getStatements().size() < record.getParameters().size()) {
			return Optional.empty();
		}
		ArrayList<ExpressionStmt> declarations = new ArrayList<>();
		ArrayList<String> names = new ArrayList<>();
		for (int index = 0; index < record.getParameters().size(); index++) {
			Statement statementNode = block.getStatement(index);
			if (!(statementNode instanceof ExpressionStmt declarationStatement)
					|| !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
					|| declaration.getVariables().size() != 1 || !declaration.getAnnotations().isEmpty()
					|| !declaration.getModifiers().isEmpty() || AstSupport.hasComment(context, declarationStatement)) {
				return Optional.empty();
			}
			VariableDeclarator variable = declaration.getVariable(0);
			Parameter component = record.getParameter(index);
			if (!variable.getType().asString().equals(component.getType().asString())
					|| !(variable.getInitializer().orElse(null) instanceof MethodCallExpr accessor)
					|| !accessor.getNameAsString().equals(component.getNameAsString())
					|| !accessor.getArguments().isEmpty() || accessor.getTypeArguments().isPresent()
					|| !(accessor.getScope().orElse(null) instanceof NameExpr receiver)
					|| !receiver.getNameAsString().equals(pattern.getNameAsString())) {
				return Optional.empty();
			}
			declarations.add(declarationStatement);
			names.add(variable.getNameAsString());
		}
		String boundName = pattern.getNameAsString();
		for (int index = record.getParameters().size(); index < block.getStatements().size(); index++) {
			if (block.getStatement(index)
				.findAll(NameExpr.class)
				.stream()
				.anyMatch(name -> name.getNameAsString().equals(boundName))) {
				return Optional.empty();
			}
		}
		return Optional.of(new Candidate(expression, pattern, List.copyOf(declarations), List.copyOf(names)));
	}

	private static Map<String, RecordDeclaration> uniqueRecords(InspectionContext context) {
		HashMap<String, List<RecordDeclaration>> grouped = new HashMap<>();
		for (RecordDeclaration record : context.compilationUnit().findAll(RecordDeclaration.class)) {
			grouped.computeIfAbsent(record.getNameAsString(), ignored -> new ArrayList<>()).add(record);
		}
		HashMap<String, RecordDeclaration> result = new HashMap<>();
		grouped.forEach((name, declarations) -> {
			if (declarations.size() == 1) {
				result.put(name, declarations.get(0));
			}
		});
		return result;
	}

	private static String simpleType(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

	private record Candidate(InstanceOfExpr instanceOf, TypePatternExpr pattern, List<ExpressionStmt> declarations,
			List<String> names) {
	}

}
