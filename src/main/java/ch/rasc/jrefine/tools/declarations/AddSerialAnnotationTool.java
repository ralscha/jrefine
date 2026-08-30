package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import ch.rasc.jrefine.analysis.ImportSupport;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Adds {@code @Serial} to recognized serialization fields and hooks. */
public final class AddSerialAnnotationTool implements InspectionTool {

	@Override
	public String id() {
		return "add-serial-annotation";
	}

	@Override
	public int minimumJavaVersion() {
		return 14;
	}

	@Override
	public String description() {
		return "Add @Serial to serialization fields and methods";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Node> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(FieldDeclaration.class)
			.stream()
			.filter(field -> SerialMemberSupport.validField(context, field))
			.filter(AddSerialAnnotationTool::lacksAnnotation)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(method -> SerialMemberSupport.validMethod(context, method))
			.filter(AddSerialAnnotationTool::lacksAnnotation)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		String annotationName = candidates.isEmpty() ? "Serial"
				: ImportSupport.useType(context, "java.io.Serial", applyFixes);
		for (Node candidate : candidates) {
			findings.add(Finding.at(candidate, "Add @Serial annotation"));
			if (applyFixes) {
				String indent = " ".repeat(Math.max(0, candidate.getBegin().orElseThrow().column - 1));
				String lineEnding = LineEndingSupport.detect(context.editor().source());
				context.editor().insert(candidate.getBegin().orElseThrow(), "@" + annotationName + lineEnding + indent);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static boolean lacksAnnotation(Node node) {
		return node instanceof NodeWithAnnotations<?> annotated && annotated.getAnnotations()
			.stream()
			.noneMatch(annotation -> "Serial".equals(annotation.getName().getIdentifier()));
	}

}
