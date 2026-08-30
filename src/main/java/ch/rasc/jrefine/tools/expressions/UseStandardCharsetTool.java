package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * Uses constants from StandardCharsets instead of Charset.forName() for required
 * charsets.
 */
public final class UseStandardCharsetTool implements InspectionTool {

	private static final Map<String, String> CONSTANTS = Map.of("US-ASCII", "US_ASCII", "ISO-8859-1", "ISO_8859_1",
			"UTF-8", "UTF_8", "UTF-16", "UTF_16", "UTF-16BE", "UTF_16BE", "UTF-16LE", "UTF_16LE");

	@Override
	public String id() {
		return "use-standard-charset";
	}

	@Override
	public String description() {
		return "Replace Charset.forName() with StandardCharsets constants";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> "forName".equals(call.getNameAsString()) && call.getArguments().size() == 1
					&& call.getScope().isPresent() && call.getArgument(0) instanceof StringLiteralExpr)
			.filter(call -> isCharset(context, call.getScope().orElseThrow().toString()))
			.filter(call -> CONSTANTS
				.containsKey(call.getArgument(0).asStringLiteralExpr().asString().toUpperCase(Locale.ROOT)))
			.filter(call -> !AstSupport.hasComment(context, call))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String typeName = candidates.isEmpty() ? "StandardCharsets"
				: ImportSupport.useType(context, "java.nio.charset.StandardCharsets", applyFixes);
		for (MethodCallExpr call : candidates) {
			findings.add(Finding.at(call, "Use StandardCharsets constant"));
			if (applyFixes) {
				context.editor()
					.replace(call.getRange().orElseThrow(), typeName + "." + CONSTANTS
						.get(call.getArgument(0).asStringLiteralExpr().asString().toUpperCase(Locale.ROOT)));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean isCharset(InspectionContext context, String scope) {
		if ("java.nio.charset.Charset".equals(scope)) {
			return true;
		}
		if (!"Charset".equals(scope)) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(
					imported -> !imported.isStatic() && "java.nio.charset.Charset".equals(imported.getNameAsString()));
	}

}
