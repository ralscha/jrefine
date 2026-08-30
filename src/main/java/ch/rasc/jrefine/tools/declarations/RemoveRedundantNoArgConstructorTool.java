package ch.rasc.jrefine.tools.declarations;

import java.util.List;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/**
 * Removes a sole constructor that is equivalent to Java's implicit default constructor.
 */
public final class RemoveRedundantNoArgConstructorTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-no-arg-constructor";
	}

	@Override
	public String description() {
		return "Remove an empty sole constructor equivalent to the implicit default";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ConstructorDeclaration> candidates = context.compilationUnit()
			.findAll(ConstructorDeclaration.class)
			.stream()
			.filter(constructor -> redundant(context, constructor))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (ConstructorDeclaration constructor : candidates) {
			findings.add(Finding.at(constructor, "Remove redundant no-argument constructor"));
			if (applyFixes) {
				context.editor().removeLine(constructor);
				constructor.remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean redundant(InspectionContext context, ConstructorDeclaration constructor) {
		if (!(constructor.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration type)
				|| type.isInterface() || type.getConstructors().size() != 1 || !constructor.getParameters().isEmpty()
				|| !constructor.getTypeParameters().isEmpty() || !constructor.getThrownExceptions().isEmpty()
				|| !constructor.getAnnotations().isEmpty() || constructor.getReceiverParameter().isPresent()
				|| constructor.getAccessSpecifier() != type.getAccessSpecifier() || constructor.getComment().isPresent()
				|| !constructor.getAllContainedComments().isEmpty()) {
			return false;
		}
		if (constructor.getBody().getStatements().isEmpty()) {
			return true;
		}
		if (constructor.getBody().getStatements().size() != 1
				|| !(constructor.getBody().getStatement(0) instanceof ExplicitConstructorInvocationStmt call)) {
			return false;
		}
		return !call.isThis() && call.getArguments().isEmpty() && call.getExpression().isEmpty()
				&& call.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isEmpty()
				&& !AstSupport.hasComment(context, call);
	}

}
