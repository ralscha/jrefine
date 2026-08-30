package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Set;

/** Passes a String path directly instead of wrapping it in a temporary File. */
public final class RemoveRedundantFileCreationTool implements InspectionTool {

	private static final Set<String> IO_TARGETS = Set.of("FileInputStream", "FileOutputStream", "FileReader",
			"FileWriter", "PrintStream", "PrintWriter");

	@Override
	public String id() {
		return "remove-redundant-file-creation";
	}

	@Override
	public String description() {
		return "Pass String paths directly instead of creating temporary File objects";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ObjectCreationExpr> candidates = context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.filter(outer -> candidate(context, outer))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (ObjectCreationExpr outer : candidates) {
			ObjectCreationExpr file = (ObjectCreationExpr) outer.getArgument(0);
			findings.add(Finding.at(file, "Remove redundant File creation"));
			if (applyFixes) {
				context.editor().replace(file.getRange().orElseThrow(), context.editor().text(file.getArgument(0)));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean candidate(InspectionContext context, ObjectCreationExpr outer) {
		if (outer.getArguments().isEmpty() || !(outer.getArgument(0) instanceof ObjectCreationExpr file)
				|| file.getArguments().size() != 1 || file.getAnonymousClassBody().isPresent()
				|| AstSupport.hasComment(context, file) || !ExpressionToolSupport.knownType(context.compilationUnit(),
						file.getType().asString(), "java.io", Set.of("File"))) {
			return false;
		}
		String pathType = ExpressionToolSupport.visibleSimpleType(context, file.getArgument(0), file).orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), pathType, "java.lang", Set.of("String"))) {
			return false;
		}
		String target = ExpressionToolSupport.simpleName(outer.getType().asString());
		return ExpressionToolSupport.knownType(context.compilationUnit(), outer.getType().asString(), "java.io",
				IO_TARGETS)
				|| "Formatter".equals(target) && ExpressionToolSupport.knownType(context.compilationUnit(),
						outer.getType().asString(), "java.util", Set.of("Formatter"));
	}

}
