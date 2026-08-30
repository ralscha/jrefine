package ch.rasc.jrefine.tools.types;

import java.util.List;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import ch.rasc.jrefine.analysis.TypeLookup;

import java.util.ArrayList;
import java.util.Set;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.Optional;

/** Uses the unsynchronized StringBuilder for local, non-escaping string accumulation. */
public final class UseStringBuilderTool implements InspectionTool {

	private static final Set<String> SUPPORTED_METHODS = Set.of("append", "appendCodePoint", "capacity", "charAt",
			"chars", "codePointAt", "codePointBefore", "codePointCount", "codePoints", "delete", "deleteCharAt",
			"ensureCapacity", "getChars", "indexOf", "insert", "lastIndexOf", "length", "offsetByCodePoints", "replace",
			"reverse", "setCharAt", "setLength", "subSequence", "substring", "toString", "trimToSize");

	private static final Set<String> VALUE_METHODS = Set.of("capacity", "charAt", "chars", "codePointAt",
			"codePointBefore", "codePointCount", "codePoints", "indexOf", "lastIndexOf", "length", "offsetByCodePoints",
			"subSequence", "substring", "toString");

	@Override
	public String id() {
		return "use-string-builder";
	}

	@Override
	public String description() {
		return "Replace non-escaping local StringBuffer variables with StringBuilder";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<VariableDeclarator> candidates = context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> isCandidate(context, variable))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (VariableDeclarator variable : candidates) {
			ObjectCreationExpr creation = variable.getInitializer().orElseThrow().asObjectCreationExpr();
			findings.add(Finding.at(variable,
					"Replace local StringBuffer '" + variable.getNameAsString() + "' with StringBuilder"));
			if (applyFixes) {
				context.editor().replace(variable.getType().getRange().orElseThrow(), "StringBuilder");
				context.editor().replace(creation.getType().getRange().orElseThrow(), "StringBuilder");
				variable.setType("StringBuilder");
				creation.setType("StringBuilder");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean isCandidate(InspectionContext context, VariableDeclarator variable) {
		if (!TypeLookup.isKnownJavaLangType(context.compilationUnit(), variable.getType().asString(),
				Set.of("StringBuffer")) || variable.getInitializer().isEmpty()
				|| !(variable.getInitializer().orElseThrow() instanceof ObjectCreationExpr creation)
				|| !TypeLookup.isKnownJavaLangType(context.compilationUnit(), creation.getType().asString(),
						Set.of("StringBuffer"))
				|| !(variable.getParentNode().orElse(null) instanceof VariableDeclarationExpr declaration)
				|| declaration.getVariables().size() != 1
				|| !(declaration.getParentNode().orElse(null) instanceof ExpressionStmt)
				|| ancestor(variable, BlockStmt.class).isEmpty()) {
			return false;
		}
		BlockStmt block = ancestor(variable, BlockStmt.class).orElseThrow();
		return block.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(variable.getNameAsString()))
			.allMatch(name -> safeUse(name, block));
	}

	private static boolean safeUse(NameExpr name, BlockStmt owningBlock) {
		if (captured(name, owningBlock)) {
			return false;
		}
		if (!(name.getParentNode().orElse(null) instanceof MethodCallExpr call) || call.getScope().orElse(null) != name
				|| !SUPPORTED_METHODS.contains(call.getNameAsString())) {
			return false;
		}
		MethodCallExpr outermost = call;
		while (outermost.getParentNode().orElse(null) instanceof MethodCallExpr parent
				&& parent.getScope().orElse(null) == outermost
				&& SUPPORTED_METHODS.contains(parent.getNameAsString())) {
			outermost = parent;
		}
		return VALUE_METHODS.contains(outermost.getNameAsString())
				|| outermost.getParentNode().orElse(null) instanceof ExpressionStmt;
	}

	private static boolean captured(Node use, BlockStmt owningBlock) {
		Optional<Node> parent = use.getParentNode();
		while (parent.isPresent()) {
			Node ancestor = parent.orElseThrow();
			if (ancestor == owningBlock) {
				return false;
			}
			if (ancestor instanceof LambdaExpr || ancestor instanceof ObjectCreationExpr creation
					&& creation.getAnonymousClassBody().isPresent()) {
				return true;
			}
			parent = ancestor.getParentNode();
		}
		return false;
	}

	private static <T extends Node> Optional<T> ancestor(Node node, Class<T> type) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (type.isInstance(value)) {
				return java.util.Optional.of(type.cast(value));
			}
			parent = value.getParentNode();
		}
		return java.util.Optional.empty();
	}

}
