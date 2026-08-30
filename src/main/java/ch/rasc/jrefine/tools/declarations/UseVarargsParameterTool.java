package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Converts a private method's final one-dimensional array parameter to varargs. */
public final class UseVarargsParameterTool implements InspectionTool {

	@Override
	public String id() {
		return "use-varargs-parameter";
	}

	@Override
	public String description() {
		return "Convert safe private array parameters to varargs";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Parameter> candidates = context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.map(method -> candidate(context, method))
			.flatMap(List::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Parameter parameter : candidates) {
			findings.add(Finding.at(parameter, "Last array parameter can use varargs syntax"));
			if (applyFixes) {
				ArrayType array = parameter.getType().asArrayType();
				JavaToken close = lastSignificantToken(array);
				JavaToken open = previousSignificantToken(close);
				if (!"]".equals(close.getText()) || !"[".equals(open.getText())) {
					throw new IllegalStateException("Could not locate array parameter brackets");
				}
				context.editor()
					.replace(new Range(open.getRange().orElseThrow().begin, close.getRange().orElseThrow().end), "...");
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static List<Parameter> candidate(InspectionContext context, MethodDeclaration method) {
		if (!method.isPrivate() || method.getParameters().isEmpty()
				|| method.getAnnotations()
					.stream()
					.anyMatch(annotation -> "Override".equals(annotation.getNameAsString()))) {
			return List.of();
		}
		ClassOrInterfaceDeclaration owner = method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		if (owner == null || owner.getMethodsByName(method.getNameAsString()).size() != 1) {
			return List.of();
		}
		Parameter parameter = method.getParameter(method.getParameters().size() - 1);
		if (parameter.isVarArgs() || !parameter.getType().isArrayType() || AstSupport.hasComment(context, parameter)) {
			return List.of();
		}
		ArrayType array = parameter.getType().asArrayType();
		if (array.getOrigin() != ArrayType.Origin.TYPE || array.getComponentType().isArrayType()
				|| !reifiableComponent(method, owner, array.getComponentType())
				|| !existingCallsRemainFixedArity(context, method, owner, parameter)) {
			return List.of();
		}
		return List.of(parameter);
	}

	private static boolean reifiableComponent(MethodDeclaration method, ClassOrInterfaceDeclaration owner,
			Type component) {
		if (component.isPrimitiveType()) {
			return true;
		}
		if (!(component instanceof ClassOrInterfaceType reference) || reference.getTypeArguments().isPresent()
				|| reference.asString().contains("<")) {
			return false;
		}
		Set<String> typeParameters = new HashSet<>();
		method.getTypeParameters().forEach(parameter -> typeParameters.add(parameter.getNameAsString()));
		owner.getTypeParameters().forEach(parameter -> typeParameters.add(parameter.getNameAsString()));
		ClassOrInterfaceDeclaration enclosing = owner.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		while (enclosing != null) {
			enclosing.getTypeParameters().forEach(parameter -> typeParameters.add(parameter.getNameAsString()));
			enclosing = enclosing.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
		}
		return !typeParameters.contains(reference.getNameAsString());
	}

	private static boolean existingCallsRemainFixedArity(InspectionContext context, MethodDeclaration method,
			ClassOrInterfaceDeclaration owner, Parameter parameter) {
		if (owner.findAll(MethodReferenceExpr.class)
			.stream()
			.anyMatch(reference -> reference.getIdentifier().equals(method.getNameAsString()))) {
			return false;
		}
		return owner.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> call.getNameAsString().equals(method.getNameAsString()))
			.filter(call -> call.getScope().isEmpty() || call.getScope().orElseThrow().isThisExpr())
			.allMatch(call -> call.getArguments().size() == method.getParameters().size() && knownArrayArgument(context,
					call.getArgument(call.getArguments().size() - 1), call, parameter.getType().asString()));
	}

	private static boolean knownArrayArgument(InspectionContext context, Expression argument, MethodCallExpr use,
			String expectedType) {
		if (argument instanceof ArrayCreationExpr creation && creation.getLevels().size() == 1) {
			return (creation.getElementType().asString() + "[]").equals(expectedType);
		}
		return TypeLookup.visibleTypePreservingArrays(context.compilationUnit(), argument, use)
			.filter(expectedType::equals)
			.isPresent();
	}

	private static JavaToken lastSignificantToken(ArrayType type) {
		JavaToken token = type.getTokenRange().orElseThrow().getEnd();
		while (token.getText().isBlank()) {
			token = token.getPreviousToken().orElseThrow();
		}
		return token;
	}

	private static JavaToken previousSignificantToken(JavaToken token) {
		JavaToken current = token;
		do {
			current = current.getPreviousToken().orElseThrow();
		}
		while (current.getText().isBlank());
		return current;
	}

}
