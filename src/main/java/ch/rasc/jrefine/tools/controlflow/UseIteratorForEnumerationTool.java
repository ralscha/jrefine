package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Migrates Vector and Hashtable Enumeration traversal to Iterator. */
public final class UseIteratorForEnumerationTool implements InspectionTool {

	@Override
	public String id() {
		return "use-iterator-for-enumeration";
	}

	@Override
	public String description() {
		return "Replace collection Enumeration traversal with Iterator";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Candidate> candidates = context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.map(variable -> candidate(context, variable))
			.flatMap(Optional::stream)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		String iteratorName = candidates.isEmpty() ? "Iterator"
				: ImportSupport.useType(context, "java.util.Iterator", applyFixes);
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.variable(), "Replace Enumeration with Iterator"));
			if (applyFixes) {
				String arguments = candidate.type()
					.getTypeArguments()
					.map(types -> "<" + types.stream()
						.map(Object::toString)
						.reduce((left, right) -> left + ", " + right)
						.orElse("") + ">")
					.orElse("");
				context.editor().replace(candidate.type().getRange().orElseThrow(), iteratorName + arguments);
				context.editor()
					.replace(candidate.initializer().getRange().orElseThrow(), iteratorInitializer(context, candidate));
				for (MethodCallExpr call : candidate.uses()) {
					String name = "hasMoreElements".equals(call.getNameAsString()) ? "hasNext" : "next";
					context.editor().replace(call.getName().getRange().orElseThrow(), name);
				}
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> candidate(InspectionContext context, VariableDeclarator variable) {
		if (!(variable.getType() instanceof ClassOrInterfaceType type)
				|| !TypeLookup.isKnownJavaUtilType(context.compilationUnit(), type.asString(), Set.of("Enumeration"))
				|| !(variable.getInitializer().orElse(null) instanceof MethodCallExpr initializer)
				|| !(initializer.getScope().orElse(null) instanceof NameExpr receiver)
				|| initializer.getArguments().size() != 0 || AstSupport.hasComment(context, variable)
				|| !(variable.getParentNode().orElse(null) instanceof VariableDeclarationExpr declaration)
				|| !(declaration.getParentNode().orElse(null) instanceof ExpressionStmt)
				|| AstSupport.ancestor(variable, BlockStmt.class).isEmpty()) {
			return Optional.empty();
		}
		String receiverType = TypeLookup.visibleType(context.compilationUnit(), receiver, variable).orElse("");
		Kind kind = kind(context, receiverType, initializer.getNameAsString()).orElse(null);
		if (kind == null) {
			return Optional.empty();
		}
		BlockStmt owner = AstSupport.ancestor(variable, BlockStmt.class).orElseThrow();
		List<MethodCallExpr> uses = owner.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(variable.getNameAsString()))
			.filter(name -> name.getBegin().orElseThrow().isAfter(variable.getEnd().orElseThrow()))
			.map(name -> name.getParentNode()
				.filter(MethodCallExpr.class::isInstance)
				.map(MethodCallExpr.class::cast)
				.filter(call -> call.getScope().filter(name::equals).isPresent() && call.getArguments().isEmpty()
						&& Set.of("hasMoreElements", "nextElement").contains(call.getNameAsString())))
			.flatMap(Optional::stream)
			.toList();
		long allReferences = owner.findAll(NameExpr.class)
			.stream()
			.filter(name -> name.getNameAsString().equals(variable.getNameAsString()))
			.filter(name -> name.getBegin().orElseThrow().isAfter(variable.getEnd().orElseThrow()))
			.count();
		if (uses.isEmpty() || uses.size() != allReferences) {
			return Optional.empty();
		}
		return Optional.of(new Candidate(variable, type, initializer, receiver, kind, List.copyOf(uses)));
	}

	private static Optional<Kind> kind(InspectionContext context, String receiverType, String method) {
		if ("elements".equals(method)
				&& TypeLookup.isKnownJavaUtilType(context.compilationUnit(), receiverType, Set.of("Vector"))) {
			return Optional.of(Kind.VECTOR);
		}
		if (TypeLookup.isKnownJavaUtilType(context.compilationUnit(), receiverType, Set.of("Hashtable"))) {
			if ("keys".equals(method)) {
				return Optional.of(Kind.KEYS);
			}
			if ("elements".equals(method)) {
				return Optional.of(Kind.VALUES);
			}
		}
		return Optional.empty();
	}

	private static String iteratorInitializer(InspectionContext context, Candidate candidate) {
		String receiver = context.editor().text(candidate.receiver());
		return switch (candidate.kind()) {
			case VECTOR -> receiver + ".iterator()";
			case KEYS -> receiver + ".keySet().iterator()";
			case VALUES -> receiver + ".values().iterator()";
		};
	}

	private enum Kind {

		VECTOR, KEYS, VALUES

	}

	private record Candidate(VariableDeclarator variable, ClassOrInterfaceType type, MethodCallExpr initializer,
			NameExpr receiver, Kind kind, List<MethodCallExpr> uses) {
	}

}
