package ch.rasc.jrefine.tools.types;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Optional;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import com.github.javaparser.Range;

/** Replaces redundant constructor type arguments with the Java 7 diamond operator. */
public final class UseDiamondOperatorTool implements InspectionTool {

	@Override
	public String id() {
		return "use-diamond-operator";
	}

	@Override
	public String description() {
		return "Replace explicit constructor type arguments with <> where target typing is safe";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ObjectCreationExpr> candidates = context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.filter(UseDiamondOperatorTool::canUseDiamond)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>(candidates.size());

		for (ObjectCreationExpr creation : candidates) {
			findings
				.add(Finding.at(creation, "Replace explicit type arguments in '" + creation.getType() + "' with '<>'"));
			if (applyFixes) {
				NodeList<Type> arguments = creation.getType().getTypeArguments().orElseThrow();
				Range argumentRange = new Range(arguments.get(0).getRange().orElseThrow().begin,
						arguments.get(arguments.size() - 1).getRange().orElseThrow().end);
				context.editor().replace(argumentRange, "");
				creation.getType().setDiamondOperator();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean canUseDiamond(ObjectCreationExpr creation) {
		if (creation.getAnonymousClassBody().isPresent()) {
			return false;
		}
		if (creation.getType().getTypeArguments().filter(arguments -> !arguments.isEmpty()).isEmpty()) {
			return false;
		}
		if (isInsideVarInitializer(creation)) {
			return false;
		}
		return !isReceiverOfMemberAccess(creation);
	}

	private static boolean isInsideVarInitializer(ObjectCreationExpr creation) {
		Optional<Node> parent = creation.getParentNode();
		while (parent.isPresent()) {
			Node ancestor = parent.orElseThrow();
			if (ancestor instanceof VariableDeclarator variable) {
				return variable.getType().isVarType() && variable.getInitializer()
					.map(initializer -> initializer.isAncestorOf(creation) || initializer == creation)
					.orElse(false);
			}
			parent = ancestor.getParentNode();
		}
		return false;
	}

	private static boolean isReceiverOfMemberAccess(ObjectCreationExpr creation) {
		Optional<Node> ancestor = creation.getParentNode();
		while (ancestor.isPresent()) {
			Node node = ancestor.orElseThrow();
			if (node instanceof MethodCallExpr call
					&& call.getScope().filter(scope -> contains(scope, creation)).isPresent()) {
				return true;
			}
			if (node instanceof FieldAccessExpr access && contains(access.getScope(), creation)) {
				return true;
			}
			if (node instanceof MethodReferenceExpr reference && contains(reference.getScope(), creation)) {
				return true;
			}
			ancestor = node.getParentNode();
		}
		return false;
	}

	private static boolean contains(Node container, Node candidate) {
		return container == candidate || container.isAncestorOf(candidate);
	}

}
