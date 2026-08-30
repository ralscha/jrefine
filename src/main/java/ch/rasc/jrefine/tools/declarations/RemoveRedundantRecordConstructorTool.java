package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.JavaToken;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.Range;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Removes empty record constructors and compacts canonical constructors with boilerplate
 * assignments.
 */
public final class RemoveRedundantRecordConstructorTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-record-constructor";
	}

	@Override
	public String description() {
		return "Remove or compact redundant record constructors";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<CompactConstructorDeclaration> compact = context.compilationUnit()
			.findAll(CompactConstructorDeclaration.class)
			.stream()
			.filter(constructor -> redundantCompact(context, constructor))
			.toList();
		List<Candidate> canonical = context.compilationUnit()
			.findAll(ConstructorDeclaration.class)
			.stream()
			.map(constructor -> canonicalCandidate(context, constructor))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (CompactConstructorDeclaration constructor : compact) {
			findings.add(Finding.at(constructor, "Remove redundant record constructor"));
			if (applyFixes) {
				context.editor().removeLine(constructor);
			}
		}
		for (Candidate candidate : canonical) {
			boolean remove = candidate.prefixStatements() == 0;
			findings.add(Finding.at(candidate.constructor(), remove ? "Remove redundant record constructor"
					: "Convert canonical record constructor to compact form"));
			if (!applyFixes) {
				continue;
			}
			if (remove) {
				context.editor().removeLine(candidate.constructor());
			}
			else {
				removeParameterList(context, candidate.constructor());
				for (ExpressionStmt assignment : candidate.assignments()) {
					context.editor().removeLine(assignment);
				}
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean redundantCompact(InspectionContext context, CompactConstructorDeclaration constructor) {
		if (!(constructor.getParentNode().orElse(null) instanceof RecordDeclaration record)
				|| constructor.getAccessSpecifier() != record.getAccessSpecifier()
				|| !constructor.getBody().getStatements().isEmpty() || !constructor.getAnnotations().isEmpty()
				|| !constructor.getTypeParameters().isEmpty() || !constructor.getThrownExceptions().isEmpty()) {
			return false;
		}
		return !AstSupport.hasComment(context, constructor);
	}

	private static Optional<Candidate> canonicalCandidate(InspectionContext context,
			ConstructorDeclaration constructor) {
		if (!(constructor.getParentNode().orElse(null) instanceof RecordDeclaration record)
				|| constructor.getAccessSpecifier() != record.getAccessSpecifier()
				|| constructor.getParameters().size() != record.getParameters().size()
				|| !constructor.getAnnotations().isEmpty() || !constructor.getTypeParameters().isEmpty()
				|| !constructor.getThrownExceptions().isEmpty() || constructor.getReceiverParameter().isPresent()
				|| AstSupport.hasComment(context, constructor)) {
			return Optional.empty();
		}
		for (int index = 0; index < record.getParameters().size(); index++) {
			if (!sameParameter(constructor.getParameter(index), record.getParameter(index))) {
				return Optional.empty();
			}
		}
		NodeList<Statement> statements = constructor.getBody().getStatements();
		int componentCount = record.getParameters().size();
		if (statements.size() < componentCount) {
			return Optional.empty();
		}
		ArrayList<ExpressionStmt> assignments = new ArrayList<>();
		int offset = statements.size() - componentCount;
		for (int index = 0; index < componentCount; index++) {
			Statement statement = statements.get(offset + index);
			String component = record.getParameter(index).getNameAsString();
			if (!(statement instanceof ExpressionStmt expressionStatement)
					|| !canonicalAssignment(expressionStatement, component)) {
				return Optional.empty();
			}
			assignments.add(expressionStatement);
		}
		return Optional.of(new Candidate(constructor, offset, List.copyOf(assignments)));
	}

	private static boolean sameParameter(Parameter constructor, Parameter component) {
		return constructor.getNameAsString().equals(component.getNameAsString())
				&& constructor.getType().asString().equals(component.getType().asString())
				&& constructor.isVarArgs() == component.isVarArgs() && constructor.getAnnotations().isEmpty()
				&& constructor.getModifiers().isEmpty() && constructor.getVarArgsAnnotations().isEmpty();
	}

	private static boolean canonicalAssignment(ExpressionStmt statement, String component) {
		if (!(statement.getExpression() instanceof AssignExpr assignment)
				|| assignment.getOperator() != AssignExpr.Operator.ASSIGN
				|| !(assignment.getTarget() instanceof FieldAccessExpr field)
				|| !(field.getScope() instanceof ThisExpr self) || self.getTypeName().isPresent()
				|| !field.getNameAsString().equals(component) || !(assignment.getValue() instanceof NameExpr value)) {
			return false;
		}
		return value.getNameAsString().equals(component);
	}

	private static void removeParameterList(InspectionContext context, ConstructorDeclaration constructor) {
		JavaToken nameEnd = constructor.getName().getTokenRange().orElseThrow().getEnd();
		JavaToken open = AstSupport.nextSignificant(nameEnd);
		JavaToken bodyStart = constructor.getBody().getTokenRange().orElseThrow().getBegin();
		JavaToken close = AstSupport.previousSignificant(bodyStart);
		if (!"(".equals(open.getText()) || !")".equals(close.getText())) {
			throw new IllegalStateException("Expected canonical constructor parameter list");
		}
		context.editor()
			.replace(new Range(open.getRange().orElseThrow().begin, close.getRange().orElseThrow().end), "");
	}

	private record Candidate(ConstructorDeclaration constructor, int prefixStatements,
			List<ExpressionStmt> assignments) {
	}

}
