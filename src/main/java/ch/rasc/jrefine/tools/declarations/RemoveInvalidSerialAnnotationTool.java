package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Removes java.io.Serial from members not recognized by Java serialization. */
public final class RemoveInvalidSerialAnnotationTool implements InspectionTool {

	@Override
	public String id() {
		return "remove-invalid-serial-annotation";
	}

	@Override
	public String description() {
		return "Remove @Serial annotations from invalid members";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<AnnotationExpr> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(FieldDeclaration.class)
			.stream()
			.filter(field -> SerialMemberSupport.ownerKind(context, field).isPresent())
			.filter(field -> !SerialMemberSupport.validField(context, field))
			.map(field -> serialAnnotation(context, field))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(method -> SerialMemberSupport.ownerKind(context, method).isPresent())
			.filter(method -> !SerialMemberSupport.validMethod(context, method))
			.map(method -> serialAnnotation(context, method))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		for (AnnotationExpr annotation : candidates) {
			findings.add(Finding.at(annotation, "@Serial is used on a member not recognized by serialization"));
			if (applyFixes) {
				context.editor().removeLine(annotation);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<AnnotationExpr> serialAnnotation(InspectionContext context, Node member) {
		return member.getChildNodes()
			.stream()
			.filter(AnnotationExpr.class::isInstance)
			.map(AnnotationExpr.class::cast)
			.filter(annotation -> knownSerial(context, annotation))
			.findFirst();
	}

	private static boolean knownSerial(InspectionContext context, AnnotationExpr annotation) {
		String spelling = annotation.getNameAsString();
		if ("java.io.Serial".equals(spelling)) {
			return true;
		}
		if (!"Serial".equals(spelling) || context.compilationUnit()
			.findAll(AnnotationDeclaration.class)
			.stream()
			.anyMatch(declaration -> "Serial".equals(declaration.getNameAsString()))) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && ("java.io.Serial".equals(imported.getNameAsString())
					|| imported.isAsterisk() && "java.io".equals(imported.getNameAsString())));
	}

}
