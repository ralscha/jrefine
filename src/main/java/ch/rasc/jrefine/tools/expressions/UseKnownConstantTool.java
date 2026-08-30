package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.SemanticEvidence;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Replaces local reads with a type-preserving literal when an equality guard proves the
 * value.
 */
public final class UseKnownConstantTool implements InspectionTool {

	@Override
	public String id() {
		return "use-known-constant";
	}

	@Override
	public String description() {
		return "Replace equality-guarded local reads with the proven constant";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<Range, Replacement> replacements = new LinkedHashMap<>();
		context.compilationUnit()
			.findAll(IfStmt.class)
			.forEach(statement -> ifReplacements(context, statement, replacements));
		context.compilationUnit()
			.findAll(WhileStmt.class)
			.forEach(statement -> equalityReplacements(context, statement.getCondition(), statement.getBody(),
					replacements));
		context.compilationUnit()
			.findAll(ForStmt.class)
			.forEach(statement -> statement.getCompare()
				.ifPresent(condition -> equalityReplacements(context, condition, statement.getBody(), replacements)));

		ArrayList<Finding> findings = new ArrayList<>();
		for (Replacement replacement : replacements.values()) {
			findings.add(Finding.at(replacement.reference(),
					"Variable has the constant value " + replacement.literalSource()));
			if (applyFixes) {
				context.editor().replace(replacement.reference().getRange().orElseThrow(), replacement.source());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static void ifReplacements(InspectionContext context, IfStmt statement,
			Map<Range, Replacement> replacements) {
		Guard guard = guard(context, statement.getCondition()).orElse(null);
		if (guard == null) {
			return;
		}
		if (guard.equality()) {
			replacements(context, guard, statement.getThenStmt(), replacements);
		}
		else {
			statement.getElseStmt().ifPresent(branch -> replacements(context, guard, branch, replacements));
		}
	}

	private static void equalityReplacements(InspectionContext context, Expression condition, Statement body,
			Map<Range, Replacement> replacements) {
		guard(context, condition).filter(Guard::equality)
			.ifPresent(guard -> replacements(context, guard, body, replacements));
	}

	private static Optional<Guard> guard(InspectionContext context, Expression condition) {
		Expression unwrapped = unwrap(condition);
		if (!(unwrapped instanceof BinaryExpr comparison) || comparison.getOperator() != BinaryExpr.Operator.EQUALS
				&& comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
			return Optional.empty();
		}
		NameExpr name;
		LiteralExpr literal;
		if (comparison.getLeft() instanceof NameExpr left && comparison.getRight() instanceof LiteralExpr right) {
			name = left;
			literal = right;
		}
		else if (comparison.getRight() instanceof NameExpr right && comparison.getLeft() instanceof LiteralExpr left) {
			name = right;
			literal = left;
		}
		else {
			return Optional.empty();
		}
		if (literal instanceof DoubleLiteralExpr || literal instanceof TextBlockLiteralExpr
				|| !TypeLookup.isVisibleLocalOrParameter(context.compilationUnit(), name.getNameAsString(), comparison)
				|| SemanticEvidence.isReassigned(context.compilationUnit(), name.getNameAsString())) {
			return Optional.empty();
		}
		String type = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), name, comparison).orElse(null);
		if (type == null || "var".equals(type) || type.contains("?") || type.contains("&") || type.contains("|")) {
			return Optional.empty();
		}
		return Optional.of(new Guard(name, literal, type, comparison.getOperator() == BinaryExpr.Operator.EQUALS));
	}

	private static void replacements(InspectionContext context, Guard guard, Statement branch,
			Map<Range, Replacement> replacements) {
		String name = guard.name().getNameAsString();
		if (shadowedInside(branch, name)) {
			return;
		}
		List<NameExpr> references = branch.findAll(NameExpr.class)
			.stream()
			.filter(reference -> reference.getNameAsString().equals(name))
			.toList();
		Node boundary = assignmentBoundary(guard.name()).orElse(null);
		if (references.isEmpty() || boundary == null
				|| references.stream().anyMatch(reference -> assignmentBoundary(reference).orElse(null) != boundary)) {
			return;
		}
		String literal = context.editor().text(guard.literal());
		String source = sameStaticType(guard) ? literal : "((" + guard.type() + ") (" + literal + "))";
		for (NameExpr reference : references) {
			replacements.putIfAbsent(reference.getRange().orElseThrow(), new Replacement(reference, literal, source));
		}
	}

	private static boolean sameStaticType(Guard guard) {
		String type = guard.type();
		LiteralExpr literal = guard.literal();
		return "boolean".equals(type) && literal instanceof BooleanLiteralExpr
				|| "char".equals(type) && literal instanceof CharLiteralExpr
				|| "int".equals(type) && literal instanceof IntegerLiteralExpr
				|| "long".equals(type) && literal instanceof LongLiteralExpr
				|| "String".equals(TypeLookup.simpleName(type)) && literal instanceof StringLiteralExpr;
	}

	private static boolean shadowedInside(Node branch, String name) {
		return branch.findAll(VariableDeclarator.class)
			.stream()
			.anyMatch(variable -> variable.getNameAsString().equals(name))
				|| branch.findAll(Parameter.class)
					.stream()
					.anyMatch(parameter -> parameter.getNameAsString().equals(name))
				|| branch.findAll(TypePatternExpr.class)
					.stream()
					.anyMatch(pattern -> pattern.getNameAsString().equals(name));
	}

	private static Expression unwrap(Expression expression) {
		Expression current = expression;
		while (current instanceof EnclosedExpr enclosed) {
			current = enclosed.getInner();
		}
		return current;
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

	private record Guard(NameExpr name, LiteralExpr literal, String type, boolean equality) {
	}

	private record Replacement(NameExpr reference, String literalSource, String source) {
	}

}
