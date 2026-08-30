package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;

/**
 * Removes explicit wrapper creation where assignment conversion already performs boxing.
 */
public final class RemoveUnnecessaryBoxingTool implements InspectionTool {

	private static final Set<String> WRAPPERS = Set.of("Boolean", "Byte", "Character", "Short", "Integer", "Long",
			"Float", "Double");

	@Override
	public String id() {
		return "remove-unnecessary-boxing";
	}

	@Override
	public String description() {
		return "Remove explicit primitive boxing calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> methodCandidate(context, call))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.map(creation -> constructorCandidate(context, creation))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.expression(), "Remove unnecessary boxing"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.expression().getRange().orElseThrow(), context.editor().text(candidate.value()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> methodCandidate(InspectionContext context, MethodCallExpr call) {
		if (!"valueOf".equals(call.getNameAsString()) || call.getArguments().size() != 1 || call.getScope().isEmpty()
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		String wrapper = call.getScope().orElseThrow().toString();
		if (wrapper.startsWith("java.lang.")) {
			wrapper = wrapper.substring("java.lang.".length());
		}
		return boxingContext(context, call, wrapper) && primitiveArgument(context, call.getArgument(0), wrapper, call)
				? Optional.of(new Candidate(call, call.getArgument(0))) : Optional.empty();
	}

	private static Optional<Candidate> constructorCandidate(InspectionContext context, ObjectCreationExpr creation) {
		if (creation.getArguments().size() != 1 || creation.getAnonymousClassBody().isPresent()
				|| AstSupport.hasComment(context, creation)) {
			return Optional.empty();
		}
		String wrapper = creation.getType().getNameAsString();
		return boxingContext(context, creation, wrapper)
				&& primitiveArgument(context, creation.getArgument(0), wrapper, creation)
						? Optional.of(new Candidate(creation, creation.getArgument(0))) : Optional.empty();
	}

	private static boolean boxingContext(InspectionContext context, Expression expression, String wrapper) {
		if (!WRAPPERS.contains(wrapper)) {
			return false;
		}
		Optional<VariableDeclarator> variable = expression.getParentNode()
			.filter(VariableDeclarator.class::isInstance)
			.map(VariableDeclarator.class::cast);
		return variable.filter(value -> value.getInitializer().orElse(null) == expression)
			.filter(value -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), value.getType().asString(),
					Set.of(wrapper)))
			.isPresent();
	}

	private static boolean primitiveArgument(InspectionContext context, Expression argument, String wrapper, Node use) {
		String primitive = Map
			.of("Boolean", "boolean", "Byte", "byte", "Character", "char", "Short", "short", "Integer", "int", "Long",
					"long", "Float", "float", "Double", "double")
			.get(wrapper);
		if (argument instanceof BooleanLiteralExpr) {
			return "boolean".equals(primitive);
		}
		if (argument instanceof CharLiteralExpr) {
			return "char".equals(primitive);
		}
		if (argument instanceof IntegerLiteralExpr) {
			return "int".equals(primitive);
		}
		if (argument instanceof LongLiteralExpr) {
			return "long".equals(primitive);
		}
		if (argument instanceof DoubleLiteralExpr literal) {
			String value = literal.getValue().toLowerCase(java.util.Locale.ROOT);
			return value.endsWith("f") ? "float".equals(primitive) : "double".equals(primitive);
		}
		if (argument instanceof CastExpr cast && cast.getType().isPrimitiveType()) {
			return cast.getType().asString().equals(primitive);
		}
		return TypeLookup.visibleType(context.compilationUnit(), argument, use).filter(primitive::equals).isPresent();
	}

	private record Candidate(Expression expression, Expression value) {
	}

}
