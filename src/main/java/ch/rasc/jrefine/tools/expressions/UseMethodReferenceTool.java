package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.SemanticEvidence;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import java.util.List;

/** Replaces forwarding expression lambdas with bound method or constructor references. */
public final class UseMethodReferenceTool implements InspectionTool {

	@Override
	public String id() {
		return "use-method-reference";
	}

	@Override
	public String description() {
		return "Replace forwarding lambdas with method references";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(LambdaExpr.class)
			.stream()
			.map(lambda -> candidate(context, lambda))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.lambda(), "Replace lambda with method reference"));
			if (applyFixes) {
				context.editor().replace(candidate.lambda().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, LambdaExpr lambda) {
		if (lambda.getExpressionBody().isEmpty() || AstSupport.hasComment(context, lambda)) {
			return Optional.empty();
		}
		Expression body = lambda.getExpressionBody().orElseThrow();
		List<String> parameters = lambda.getParameters()
			.stream()
			.map(parameter -> parameter.getNameAsString())
			.toList();
		if (body instanceof MethodCallExpr call && call.getScope().isPresent()
				&& forwarded(parameters, call.getArguments())
				&& safeBoundScope(context, call.getScope().orElseThrow(), lambda)
				&& call.getScope()
					.orElseThrow()
					.findAll(NameExpr.class)
					.stream()
					.noneMatch(name -> parameters.contains(name.getNameAsString()))) {
			return Optional.of(new Candidate(lambda,
					context.editor().text(call.getScope().orElseThrow()) + "::" + call.getNameAsString()));
		}
		if (body instanceof MethodCallExpr call && call.getScope().isEmpty()
				&& forwarded(parameters, call.getArguments())) {
			return unscopedQualifier(lambda, call)
				.map(qualifier -> new Candidate(lambda, qualifier + "::" + call.getNameAsString()));
		}
		if (body instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isEmpty()
				&& forwarded(parameters, creation.getArguments())) {
			return Optional.of(new Candidate(lambda, context.editor().text(creation.getType()) + "::new"));
		}
		return Optional.empty();
	}

	private static boolean safeBoundScope(InspectionContext context, Expression scope, LambdaExpr lambda) {
		return SemanticEvidence.isStableMethodReferenceReceiver(context, scope, lambda);
	}

	private static Optional<String> unscopedQualifier(LambdaExpr lambda, MethodCallExpr call) {
		Optional<TypeDeclaration<?>> owner = enclosingType(lambda);
		if (owner.isEmpty()) {
			return Optional.empty();
		}
		List<MethodDeclaration> methods = owner.orElseThrow()
			.getMembers()
			.stream()
			.filter(MethodDeclaration.class::isInstance)
			.map(MethodDeclaration.class::cast)
			.filter(method -> method.getNameAsString().equals(call.getNameAsString())
					&& method.getParameters().size() == call.getArguments().size())
			.toList();
		if (methods.size() != 1) {
			return Optional.empty();
		}
		MethodDeclaration method = methods.getFirst();
		if (method.isStatic()) {
			return Optional.of(owner.orElseThrow().getNameAsString());
		}
		return staticContext(lambda) ? Optional.empty() : Optional.of("this");
	}

	private static Optional<TypeDeclaration<?>> enclosingType(Node node) {
		Optional<Node> parent = node.getParentNode();
		while (parent.isPresent()) {
			Node value = parent.orElseThrow();
			if (value instanceof TypeDeclaration<?> declaration) {
				return Optional.of(declaration);
			}
			parent = value.getParentNode();
		}
		return Optional.empty();
	}

	private static boolean staticContext(LambdaExpr lambda) {
		Optional<MethodDeclaration> method = AstSupport.ancestor(lambda, MethodDeclaration.class);
		if (method.isPresent()) {
			return method.orElseThrow().isStatic();
		}
		Optional<FieldDeclaration> field = AstSupport.ancestor(lambda, FieldDeclaration.class);
		if (field.isPresent()) {
			return field.orElseThrow().isStatic();
		}
		Optional<InitializerDeclaration> initializer = AstSupport.ancestor(lambda, InitializerDeclaration.class);
		return initializer.map(InitializerDeclaration::isStatic).orElse(false);
	}

	private static boolean forwarded(List<String> parameters, NodeList<Expression> arguments) {
		if (parameters.size() != arguments.size()) {
			return false;
		}
		for (int index = 0; index < parameters.size(); index++) {
			if (!(arguments.get(index) instanceof NameExpr name)
					|| !name.getNameAsString().equals(parameters.get(index))) {
				return false;
			}
		}
		return true;
	}

	private record Candidate(LambdaExpr lambda, String replacement) {
	}

}
