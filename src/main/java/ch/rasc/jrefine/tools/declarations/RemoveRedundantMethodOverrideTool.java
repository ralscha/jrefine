package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Removes locally provable method overrides that add no behavior or API contract. */
public final class RemoveRedundantMethodOverrideTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-method-override";
	}

	@Override
	public String description() {
		return "Remove methods identical to a local super method";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		Map<String, ClassOrInterfaceDeclaration> types = uniqueTypes(context);
		List<MethodDeclaration> candidates = context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(method -> redundant(context, method, types))
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodDeclaration method : candidates) {
			findings.add(Finding.at(method, "Remove method identical to its super method"));
			if (applyFixes) {
				context.editor().removeLine(method);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean redundant(InspectionContext context, MethodDeclaration method,
			Map<String, ClassOrInterfaceDeclaration> types) {
		if (!(method.getParentNode().orElse(null) instanceof ClassOrInterfaceDeclaration owner) || owner.isInterface()
				|| method.getBody().isEmpty() || method.isStatic() || method.isPrivate() || method.isFinal()
				|| method.isAbstract() || method.isNative() || method.isSynchronized() || method.isStrictfp()
				|| method.getReceiverParameter().isPresent() || !method.getType().getAnnotations().isEmpty()
				|| method.getParameters()
					.stream()
					.anyMatch(parameter -> !parameter.getAnnotations().isEmpty() || !parameter.getModifiers().isEmpty()
							|| !parameter.getType().getAnnotations().isEmpty()
							|| !parameter.getVarArgsAnnotations().isEmpty())
				|| AstSupport.hasComment(context, method) || method.getAnnotations().stream().anyMatch(annotation -> {
					String name = annotation.getNameAsString();
					return !"Override".equals(name) && !"java.lang.Override".equals(name);
				})) {
			return false;
		}
		Optional<MethodDeclaration> inherited = inheritedMethod(owner, method, types, new HashSet<>());
		if (inherited.isEmpty() || !sameContract(method, inherited.orElseThrow())) {
			return false;
		}
		MethodDeclaration parent = inherited.orElseThrow();
		return directSuperDelegate(method) || method.getBody().equals(parent.getBody()) && selfContained(method);
	}

	private static Optional<MethodDeclaration> inheritedMethod(ClassOrInterfaceDeclaration owner,
			MethodDeclaration method, Map<String, ClassOrInterfaceDeclaration> types, Set<String> visited) {
		for (ClassOrInterfaceType parentType : owner.getExtendedTypes()) {
			String name = simpleType(parentType.asString());
			if (!visited.add(name)) {
				continue;
			}
			ClassOrInterfaceDeclaration parent = types.get(name);
			if (parent == null) {
				continue;
			}
			Optional<MethodDeclaration> declared = parent.getMethods()
				.stream()
				.filter(candidate -> !candidate.isPrivate() && !candidate.isStatic())
				.filter(candidate -> sameSignature(method, candidate))
				.findFirst();
			if (declared.isPresent()) {
				return declared;
			}
			Optional<MethodDeclaration> inherited = inheritedMethod(parent, method, types, visited);
			if (inherited.isPresent()) {
				return inherited;
			}
		}
		return Optional.empty();
	}

	private static boolean sameContract(MethodDeclaration method, MethodDeclaration parent) {
		return parent.getBody().isPresent() && method.getAccessSpecifier() == parent.getAccessSpecifier()
				&& method.getType().asString().equals(parent.getType().asString())
				&& method.getTypeParameters().toString().equals(parent.getTypeParameters().toString())
				&& method.getThrownExceptions().toString().equals(parent.getThrownExceptions().toString())
				&& sameSignature(method, parent);
	}

	private static boolean sameSignature(MethodDeclaration left, MethodDeclaration right) {
		if (!left.getNameAsString().equals(right.getNameAsString())
				|| left.getParameters().size() != right.getParameters().size()) {
			return false;
		}
		for (int index = 0; index < left.getParameters().size(); index++) {
			Parameter leftParameter = left.getParameter(index);
			Parameter rightParameter = right.getParameter(index);
			if (!leftParameter.getType().asString().equals(rightParameter.getType().asString())
					|| leftParameter.isVarArgs() != rightParameter.isVarArgs()) {
				return false;
			}
		}
		return true;
	}

	private static boolean directSuperDelegate(MethodDeclaration method) {
		NodeList<Statement> statements = method.getBody().orElseThrow().getStatements();
		if (statements.size() != 1) {
			return false;
		}
		MethodCallExpr call = null;
		if (statements.get(0) instanceof ReturnStmt returned
				&& returned.getExpression().orElse(null) instanceof MethodCallExpr returnedCall) {
			call = returnedCall;
		}
		else if (statements.get(0) instanceof ExpressionStmt expression
				&& expression.getExpression() instanceof MethodCallExpr expressionCall) {
			call = expressionCall;
		}
		if (call == null || !(call.getScope().orElse(null) instanceof SuperExpr parent)
				|| parent.getTypeName().isPresent() || !call.getNameAsString().equals(method.getNameAsString())
				|| call.getTypeArguments().filter(arguments -> !arguments.isEmpty()).isPresent()
				|| call.getArguments().size() != method.getParameters().size()) {
			return false;
		}
		for (int index = 0; index < call.getArguments().size(); index++) {
			if (!(call.getArgument(index) instanceof NameExpr argument)
					|| !argument.getNameAsString().equals(method.getParameter(index).getNameAsString())) {
				return false;
			}
		}
		return true;
	}

	private static boolean selfContained(MethodDeclaration method) {
		if (!method.findAll(ThisExpr.class).isEmpty() || !method.findAll(SuperExpr.class).isEmpty()
				|| method.findAll(MethodCallExpr.class).stream().anyMatch(call -> call.getScope().isEmpty())) {
			return false;
		}
		HashSet<String> localNames = new HashSet<>();
		method.getParameters().forEach(parameter -> localNames.add(parameter.getNameAsString()));
		method.findAll(VariableDeclarator.class).forEach(variable -> localNames.add(variable.getNameAsString()));
		return method.findAll(NameExpr.class).stream().allMatch(name -> localNames.contains(name.getNameAsString()));
	}

	private static Map<String, ClassOrInterfaceDeclaration> uniqueTypes(InspectionContext context) {
		HashMap<String, List<ClassOrInterfaceDeclaration>> grouped = new HashMap<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			grouped.computeIfAbsent(type.getNameAsString(), ignored -> new ArrayList<>()).add(type);
		}
		HashMap<String, ClassOrInterfaceDeclaration> result = new HashMap<>();
		grouped.forEach((name, declarations) -> {
			if (declarations.size() == 1) {
				result.put(name, declarations.get(0));
			}
		});
		return result;
	}

	private static String simpleType(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

}
