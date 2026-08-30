package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Set;

/** Removes explicit java.lang.Object superclass and type-parameter bounds. */
public final class RemoveRedundantObjectBoundsTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-redundant-object-bounds";
	}

	@Override
	public String description() {
		return "Remove explicit extends Object declarations and type bounds";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> !type.isInterface() && type.getExtendedTypes().size() == 1)
			.map(type -> type.getExtendedTypes().get(0))
			.filter(bound -> redundantObject(context, bound))
			.forEach(bound -> candidates.add(new Candidate(bound, "Remove explicit Object superclass")));
		context.compilationUnit()
			.findAll(TypeParameter.class)
			.stream()
			.filter(parameter -> parameter.getTypeBound().size() == 1)
			.map(parameter -> parameter.getTypeBound().get(0))
			.filter(bound -> redundantObject(context, bound))
			.forEach(bound -> candidates.add(new Candidate(bound, "Remove redundant Object type bound")));

		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.bound(), candidate.message()));
			if (applyFixes) {
				Range range = AstSupport.rangeFromPreviousToken(candidate.bound(), "extends");
				context.editor().removeWithLeadingWhitespace(range);
				candidate.bound().remove();
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean redundantObject(InspectionContext context, ClassOrInterfaceType bound) {
		return bound.getAnnotations().isEmpty() && bound.getTypeArguments().isEmpty()
				&& !AstSupport.hasComment(context, bound)
				&& TypeLookup.isKnownJavaLangType(context.compilationUnit(), bound.asString(), Set.of("Object"));
	}

	private record Candidate(Node bound, String message) {
	}

}
