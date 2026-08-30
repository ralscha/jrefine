package ch.rasc.jrefine.tools.types;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Replaces straight-line local StringBuilder assembly with String concatenation. */
public final class ReplaceStringBuilderWithStringTool implements InspectionTool {

	private static final Set<String> BUILDERS = Set.of("StringBuilder", "StringBuffer");

	private static final Set<String> SAFE_REFERENCE_TYPES = Set.of("Boolean", "Byte", "Character", "Double", "Float",
			"Integer", "Long", "Object", "Short", "String", "StringBuffer", "StringBuilder");

	private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "double", "float", "int", "long",
			"short");

	@Override
	public String id() {
		return "replace-string-builder-with-string";
	}

	@Override
	public String description() {
		return "Replace straight-line local StringBuilder assembly with String concatenation";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(BlockStmt.class)
			.stream()
			.flatMap(block -> candidates(context, block).stream())
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.variable(), "Replace '" + candidate.builderType() + "' with String"));
			if (!applyFixes) {
				continue;
			}
			context.editor().replace(candidate.variable().getType().getRange().orElseThrow(), "String");
			context.editor()
				.replace(candidate.creation().getRange().orElseThrow(), concatenation(context, candidate.parts()));
			candidate.appendStatements().forEach(context.editor()::removeLine);
			candidate.conversions()
				.forEach(call -> context.editor()
					.replace(call.getRange().orElseThrow(), candidate.variable().getNameAsString()));
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static List<Candidate> candidates(InspectionContext context, BlockStmt block) {
		ArrayList<Candidate> result = new ArrayList<>();
		NodeList<Statement> statements = block.getStatements();
		for (int index = 0; index < statements.size(); index++) {
			if (!(statements.get(index) instanceof ExpressionStmt declarationStatement)
					|| !(declarationStatement.getExpression() instanceof VariableDeclarationExpr declaration)
					|| declaration.getVariables().size() != 1) {
				continue;
			}
			VariableDeclarator variable = declaration.getVariable(0);
			Optional<InitialBuilder> initial = initialBuilder(context, variable);
			if (initial.isEmpty()) {
				continue;
			}

			ArrayList<ExpressionStmt> appendStatements = new ArrayList<>();
			ArrayList<Expression> parts = new ArrayList<>();
			int next = index + 1;
			while (next < statements.size() && statements.get(next) instanceof ExpressionStmt statement) {
				Optional<List<Expression>> appended = appendParts(context, statement, variable.getNameAsString());
				if (appended.isEmpty()) {
					break;
				}
				appendStatements.add(statement);
				parts.addAll(appended.orElseThrow());
				next++;
			}
			if (parts.isEmpty()) {
				continue;
			}
			Optional<Candidate> candidate = completeCandidate(context, block, variable, initial.orElseThrow(),
					appendStatements, parts);
			candidate.ifPresent(result::add);
		}
		return result;
	}

	private static Optional<InitialBuilder> initialBuilder(InspectionContext context, VariableDeclarator variable) {
		if (!TypeLookup.isKnownJavaLangType(context.compilationUnit(), "String", Set.of("String"))
				|| !TypeLookup.isKnownJavaLangType(context.compilationUnit(), variable.getType().asString(), BUILDERS)
				|| variable.getInitializer().isEmpty()
				|| !(variable.getInitializer().orElseThrow() instanceof ObjectCreationExpr creation)
				|| !TypeLookup.isKnownJavaLangType(context.compilationUnit(), creation.getType().asString(), BUILDERS)
				|| !sameSimpleType(variable.getType().asString(), creation.getType().asString())
				|| !creation.getArguments().isEmpty() || creation.getAnonymousClassBody().isPresent()
				|| AstSupport.hasComment(context, variable)) {
			return Optional.empty();
		}
		return Optional.of(new InitialBuilder(creation, simpleName(variable.getType().asString())));
	}

	private static Optional<List<Expression>> appendParts(InspectionContext context, ExpressionStmt statement,
			String variableName) {
		if (AstSupport.hasComment(context, statement)) {
			return Optional.empty();
		}
		ArrayList<MethodCallExpr> calls = new ArrayList<>();
		Expression scope = statement.getExpression();
		while (scope instanceof MethodCallExpr call && "append".equals(call.getNameAsString())
				&& call.getArguments().size() == 1 && call.getTypeArguments().isEmpty()
				&& call.getScope().isPresent()) {
			calls.add(call);
			scope = call.getScope().orElseThrow();
		}
		if (!(scope instanceof NameExpr name) || !name.getNameAsString().equals(variableName)) {
			return Optional.empty();
		}
		ArrayList<Expression> parts = new ArrayList<>();
		for (int index = calls.size() - 1; index >= 0; index--) {
			Expression argument = calls.get(index).getArgument(0);
			if (!safeAppendValue(context, argument, statement)) {
				return Optional.empty();
			}
			parts.add(argument);
		}
		return Optional.of(parts);
	}

	private static Optional<Candidate> completeCandidate(InspectionContext context, BlockStmt block,
			VariableDeclarator variable, InitialBuilder initial, List<ExpressionStmt> appendStatements,
			List<Expression> parts) {
		String name = variable.getNameAsString();
		Set<NameExpr> appendUses = Collections.newSetFromMap(new IdentityHashMap<>());
		appendStatements.forEach(statement -> statement.findAll(NameExpr.class)
			.stream()
			.filter(use -> use.getNameAsString().equals(name))
			.filter(ReplaceStringBuilderWithStringTool::isAppendReceiver)
			.forEach(appendUses::add));
		Position lastAppendEnd = appendStatements.getLast().getEnd().orElse(Position.HOME);
		List<MethodCallExpr> conversions = block.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> "toString".equals(call.getNameAsString()) && call.getArguments().isEmpty()
					&& call.getTypeArguments().isEmpty())
			.filter(call -> call.getScope().orElse(null) instanceof NameExpr use && use.getNameAsString().equals(name))
			.filter(call -> after(call.getBegin().orElse(Position.HOME), lastAppendEnd))
			.filter(call -> !AstSupport.hasComment(context, call))
			.toList();
		if (conversions.isEmpty()) {
			return Optional.empty();
		}
		Set<NameExpr> conversionUses = Collections.newSetFromMap(new IdentityHashMap<>());
		conversions.stream().map(call -> (NameExpr) call.getScope().orElseThrow()).forEach(conversionUses::add);
		List<NameExpr> allUses = block.findAll(NameExpr.class)
			.stream()
			.filter(use -> use.getNameAsString().equals(name))
			.toList();
		if (allUses.stream().anyMatch(use -> !appendUses.contains(use) && !conversionUses.contains(use))) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(variable, initial.creation(), initial.builderType(),
				List.copyOf(appendStatements), List.copyOf(parts), conversions));
	}

	private static boolean isAppendReceiver(NameExpr use) {
		Node current = use;
		while (current.getParentNode().orElse(null) instanceof MethodCallExpr call
				&& call.getScope().orElse(null) == current && "append".equals(call.getNameAsString())
				&& call.getArguments().size() == 1) {
			current = call;
		}
		return current != use;
	}

	private static boolean safeAppendValue(InspectionContext context, Expression expression, Node use) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (currentExpression instanceof LiteralExpr) {
			return true;
		}
		String type;
		if (currentExpression instanceof NameExpr) {
			type = TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), currentExpression, use)
				.orElse(null);
		}
		else if (currentExpression instanceof CastExpr cast) {
			type = cast.getType().asString();
		}
		else {
			return false;
		}
		if (type == null) {
			return false;
		}
		String raw = type.replace("...", "[]");
		while (raw.endsWith("[]")) {
			raw = raw.substring(0, raw.length() - 2);
		}
		if (PRIMITIVES.contains(raw)) {
			return true;
		}
		return TypeLookup.isKnownJavaLangType(context.compilationUnit(), raw, SAFE_REFERENCE_TYPES);
	}

	private static String concatenation(InspectionContext context, List<Expression> parts) {
		ArrayList<String> result = new ArrayList<>();
		for (int index = 0; index < parts.size(); index++) {
			Expression part = parts.get(index);
			String text = context.editor().text(part);
			if (index == 0 && !definitelyString(context, part) || isCharArray(context, part)) {
				text = "String.valueOf(" + text + ")";
			}
			result.add(text);
		}
		return String.join(" + ", result);
	}

	private static boolean definitelyString(InspectionContext context, Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		if (currentExpression instanceof StringLiteralExpr || currentExpression instanceof TextBlockLiteralExpr) {
			return true;
		}
		String type = null;
		if (currentExpression instanceof NameExpr) {
			type = TypeLookup
				.visibleTypePreservingArrays(context.compilationUnit(), currentExpression, currentExpression)
				.orElse(null);
		}
		else if (currentExpression instanceof CastExpr cast) {
			type = cast.getType().asString();
		}
		return type != null && TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, Set.of("String"));
	}

	private static boolean isCharArray(InspectionContext context, Expression expression) {
		Expression currentExpression = expression;
		currentExpression = unwrap(currentExpression);
		String type = null;
		if (currentExpression instanceof NameExpr) {
			type = TypeLookup
				.visibleTypePreservingArrays(context.compilationUnit(), currentExpression, currentExpression)
				.orElse(null);
		}
		else if (currentExpression instanceof CastExpr cast) {
			type = cast.getType().asString();
		}
		return type != null && "char[]".equals(type.replace("...", "[]"));
	}

	private static Expression unwrap(Expression expression) {
		Expression currentExpression = expression;
		while (currentExpression instanceof EnclosedExpr enclosed) {
			currentExpression = enclosed.getInner();
		}
		return currentExpression;
	}

	private static boolean sameSimpleType(String left, String right) {
		return simpleName(left).equals(simpleName(right));
	}

	private static String simpleName(String type) {
		int dot = type.lastIndexOf('.');
		return dot < 0 ? type : type.substring(dot + 1);
	}

	private static boolean after(Position value, Position threshold) {
		return value.line > threshold.line || value.line == threshold.line && value.column > threshold.column;
	}

	private record InitialBuilder(ObjectCreationExpr creation, String builderType) {
	}

	private record Candidate(VariableDeclarator variable, ObjectCreationExpr creation, String builderType,
			List<ExpressionStmt> appendStatements, List<Expression> parts, List<MethodCallExpr> conversions) {
	}

}
