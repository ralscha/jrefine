package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.Node;
import java.util.Optional;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** Reports Java ME resources opened without an enclosing structured close path. */
public final class ReportEmbeddedResourcePerformanceTool implements InspectionTool {

	@Override
	public String id() {
		return "report-embedded-resource-performance";
	}

	@Override
	public String description() {
		return "Report Java ME RecordStore and Connection resources not protected by try/finally";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (!(variable.getInitializer().orElse(null) instanceof MethodCallExpr call)
					|| protectedByFinally(variable)) {
				continue;
			}
			String type = variable.getType().asString();
			int dot = type.lastIndexOf('.');
			if (dot >= 0) {
				type = type.substring(dot + 1);
			}
			if ("RecordStore".equals(type) && "openRecordStore".equals(call.getNameAsString())) {
				findings.add(Finding.at(variable, "RecordStore is opened without a corresponding try/finally close"));
			}
			if (("Connection".equals(type) || type.endsWith("Connection"))
					&& call.getNameAsString().startsWith("open")) {
				findings.add(
						Finding.at(variable, "Java ME Connection is opened without a corresponding try/finally close"));
			}
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean protectedByFinally(VariableDeclarator variable) {
		Optional<Node> parent = variable.getParentNode();
		while (parent.isPresent()) {
			Node node = parent.orElseThrow();
			if (node instanceof TryStmt statement) {
				return statement.getFinallyBlock().isPresent()
						|| statement.getResources().stream().anyMatch(resource -> resource.isAncestorOf(variable));
			}
			parent = node.getParentNode();
		}
		return false;
	}

}
