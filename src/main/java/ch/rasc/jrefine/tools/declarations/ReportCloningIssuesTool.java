package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** Reports source-local violations and policy concerns around Java cloning. */
public final class ReportCloningIssuesTool implements InspectionTool {

	@Override
	public String id() {
		return "report-cloning-issues";
	}

	@Override
	public String description() {
		return "Report Cloneable contracts, clone signatures, allocations, and clone usage";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			inspectType(type, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void inspectType(ClassOrInterfaceDeclaration type, List<Finding> findings) {
		boolean cloneable = type.getImplementedTypes()
			.stream()
			.anyMatch(parent -> "Cloneable".equals(parent.getNameAsString()));
		if (cloneable) {
			findings.add(Finding.at(type, "Use of Cloneable couples the class to Java's cloning mechanism"));
		}
		List<MethodDeclaration> clones = type.getMethods()
			.stream()
			.filter(ReportCloningIssuesTool::cloneMethod)
			.toList();
		if (cloneable && clones.isEmpty()) {
			findings.add(Finding.at(type, "Cloneable class does not declare a clone() method"));
		}
		for (MethodDeclaration method : clones) {
			findings.add(Finding.at(method, "Use or implementation of clone() should be reviewed"));
			if (method.getThrownExceptions()
				.stream()
				.noneMatch(exception -> "CloneNotSupportedException".equals(simple(exception.asString())))) {
				findings.add(Finding.at(method, "clone() does not declare CloneNotSupportedException"));
			}
			if (method.getBody()
				.stream()
				.flatMap(body -> body.findAll(ObjectCreationExpr.class).stream())
				.findAny()
				.isPresent()) {
				findings.add(Finding.at(method, "clone() instantiates objects with a constructor"));
			}
			if (!cloneable) {
				findings.add(Finding.at(method, "clone() method is declared in a non-Cloneable class"));
			}
			if (!method.isPublic()) {
				findings.add(Finding.at(method, "clone() method is not public"));
			}
			if (!simple(method.getType().asString()).equals(type.getNameAsString())) {
				findings.add(Finding.at(method, "clone() return type is not the containing class"));
			}
		}
	}

	private static boolean cloneMethod(MethodDeclaration method) {
		return "clone".equals(method.getNameAsString()) && method.getParameters().isEmpty();
	}

	private static String simple(String type) {
		int dot = type.lastIndexOf('.');
		return dot < 0 ? type : type.substring(dot + 1);
	}

}
