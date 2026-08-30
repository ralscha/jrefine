package ch.rasc.jrefine.tools.expressions;

import java.util.List;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Map;

/** Removes explicit wrapper unboxing where assignment conversion already performs it. */
public final class RemoveUnnecessaryUnboxingTool implements InspectionTool {

	private static final Map<String, String> METHODS = Map.of("booleanValue", "boolean", "byteValue", "byte",
			"charValue", "char", "shortValue", "short", "intValue", "int", "longValue", "long", "floatValue", "float",
			"doubleValue", "double");

	@Override
	public String id() {
		return "remove-unnecessary-unboxing";
	}

	@Override
	public String description() {
		return "Remove explicit wrapper unboxing calls";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<MethodCallExpr> candidates = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> METHODS.containsKey(call.getNameAsString()))
			.filter(call -> call.getScope().isPresent() && call.getArguments().isEmpty())
			.filter(call -> !AstSupport.hasComment(context, call))
			.filter(call -> primitiveInitializer(call, METHODS.get(call.getNameAsString())))
			.filter(call -> wrapperReceiver(context, call))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : candidates) {
			findings.add(Finding.at(call, "Remove unnecessary unboxing"));
			if (applyFixes) {
				context.editor()
					.replace(call.getRange().orElseThrow(), context.editor().text(call.getScope().orElseThrow()));
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean primitiveInitializer(MethodCallExpr call, String primitive) {
		return call.getParentNode()
			.filter(VariableDeclarator.class::isInstance)
			.map(VariableDeclarator.class::cast)
			.filter(variable -> variable.getInitializer().orElse(null) == call)
			.filter(variable -> variable.getType().isPrimitiveType() && variable.getType().asString().equals(primitive))
			.isPresent();
	}

	private static boolean wrapperReceiver(InspectionContext context, MethodCallExpr call) {
		String wrapper = switch (call.getNameAsString()) {
			case "booleanValue" -> "Boolean";
			case "byteValue" -> "Byte";
			case "charValue" -> "Character";
			case "shortValue" -> "Short";
			case "intValue" -> "Integer";
			case "longValue" -> "Long";
			case "floatValue" -> "Float";
			case "doubleValue" -> "Double";
			default -> throw new IllegalStateException();
		};
		return TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
			.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type, java.util.Set.of(wrapper)))
			.isPresent();
	}

}
