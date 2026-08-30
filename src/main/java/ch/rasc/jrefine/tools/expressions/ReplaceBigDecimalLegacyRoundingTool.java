package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

/** Replaces BigDecimal integer rounding constants with RoundingMode values. */
public final class ReplaceBigDecimalLegacyRoundingTool implements InspectionTool {

	private static final Map<Integer, String> MODES = Map.of(0, "UP", 1, "DOWN", 2, "CEILING", 3, "FLOOR", 4, "HALF_UP",
			5, "HALF_DOWN", 6, "HALF_EVEN", 7, "UNNECESSARY");

	@Override
	public String id() {
		return "replace-bigdecimal-legacy-rounding";
	}

	@Override
	public String description() {
		return "Replace BigDecimal integer rounding modes with RoundingMode";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> candidate(context, call))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String roundingMode = candidates.isEmpty() ? "RoundingMode"
				: ImportSupport.useType(context, "java.math.RoundingMode", applyFixes);
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.argument(), "Replace legacy BigDecimal rounding mode"));
			if (applyFixes) {
				context.editor()
					.replace(candidate.argument().getRange().orElseThrow(), roundingMode + "." + candidate.mode());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, MethodCallExpr call) {
		boolean validArity = "setScale".equals(call.getNameAsString()) && call.getArguments().size() == 2
				|| "divide".equals(call.getNameAsString())
						&& (call.getArguments().size() == 2 || call.getArguments().size() == 3);
		if (!validArity || call.getScope().isEmpty() || !bigDecimalReceiver(context, call)
				|| AstSupport.hasComment(context, call)) {
			return Optional.empty();
		}
		Expression argument = call.getArgument(call.getArguments().size() - 1);
		return roundingMode(context, argument).map(mode -> new Candidate(argument, mode));
	}

	private static Optional<String> roundingMode(InspectionContext context, Expression expression) {
		if (expression instanceof IntegerLiteralExpr literal) {
			return Optional.ofNullable(MODES.get(literal.asNumber().intValue()));
		}
		String constant = null;
		if (expression instanceof FieldAccessExpr field && bigDecimalName(context, field.getScope().toString())) {
			constant = field.getNameAsString();
		}
		if (expression instanceof NameExpr name && context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> imported.isStatic()
					&& (imported.isAsterisk() && "java.math.BigDecimal".equals(imported.getNameAsString())
							|| imported.getNameAsString().equals("java.math.BigDecimal." + name.getNameAsString())))) {
			constant = name.getNameAsString();
		}
		if (constant == null || !constant.startsWith("ROUND_")) {
			return Optional.empty();
		}
		String mode = constant.substring("ROUND_".length());
		return MODES.containsValue(mode) ? Optional.of(mode) : Optional.empty();
	}

	private static boolean bigDecimalReceiver(InspectionContext context, MethodCallExpr call) {
		Expression scope = call.getScope().orElseThrow();
		if (scope instanceof ObjectCreationExpr creation) {
			return bigDecimalName(context, creation.getType().asString());
		}
		return TypeLookup.visibleType(context.compilationUnit(), scope, call)
			.filter(type -> bigDecimalName(context, type))
			.isPresent();
	}

	private static boolean bigDecimalName(InspectionContext context, String type) {
		if ("java.math.BigDecimal".equals(type)) {
			return true;
		}
		if (!"BigDecimal".equals(type) || context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(declaration -> "BigDecimal".equals(declaration.getNameAsString()))) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && ("java.math.BigDecimal".equals(imported.getNameAsString())
					|| imported.isAsterisk() && "java.math".equals(imported.getNameAsString())));
	}

	private record Candidate(Expression argument, String mode) {
	}

}
