package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import ch.rasc.jrefine.analysis.AstSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Optional;

/** Uses the Java 11 Files string convenience methods for explicit charset conversions. */
public final class UseFilesStringMethodsTool implements InspectionTool {

	@Override
	public String id() {
		return "use-files-string-methods";
	}

	@Override
	public int minimumJavaVersion() {
		return 11;
	}

	@Override
	public String description() {
		return "Use Files.readString() and Files.writeString()";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Candidate> candidates = new ArrayList<>();
		context.compilationUnit()
			.findAll(ObjectCreationExpr.class)
			.stream()
			.map(creation -> readCandidate(context, creation))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.map(call -> writeCandidate(context, call))
			.flatMap(Optional::stream)
			.forEach(candidates::add);
		ArrayList<Finding> findings = new ArrayList<>();
		for (Candidate candidate : candidates) {
			findings.add(Finding.at(candidate.node(), "Use Files." + candidate.method() + "()"));
			if (applyFixes) {
				context.editor().replace(candidate.node().getRange().orElseThrow(), candidate.replacement());
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static Optional<Candidate> readCandidate(InspectionContext context, ObjectCreationExpr creation) {
		if (!"String".equals(creation.getType().getNameAsString()) || creation.getArguments().size() != 2
				|| creation.getAnonymousClassBody().isPresent() || AstSupport.hasComment(context, creation)
				|| !(creation.getArgument(0) instanceof MethodCallExpr read)
				|| !filesCall(context, read, "readAllBytes") || read.getArguments().size() != 1) {
			return Optional.empty();
		}
		String owner = context.editor().text(read.getScope().orElseThrow());
		return Optional.of(new Candidate(creation, "readString",
				owner + ".readString(" + context.editor().text(read.getArgument(0)) + ", "
						+ context.editor().text(creation.getArgument(1)) + ")"));
	}

	private static Optional<Candidate> writeCandidate(InspectionContext context, MethodCallExpr call) {
		if (!filesCall(context, call, "write") || call.getArguments().size() < 2 || AstSupport.hasComment(context, call)
				|| !(call.getArgument(1) instanceof MethodCallExpr bytes) || !"getBytes".equals(bytes.getNameAsString())
				|| bytes.getScope().isEmpty() || bytes.getArguments().size() != 1) {
			return Optional.empty();
		}
		ArrayList<String> arguments = new ArrayList<>();
		arguments.add(context.editor().text(call.getArgument(0)));
		arguments.add(context.editor().text(bytes.getScope().orElseThrow()));
		arguments.add(context.editor().text(bytes.getArgument(0)));
		call.getArguments().stream().skip(2).map(context.editor()::text).forEach(arguments::add);
		return Optional.of(new Candidate(call, "writeString", context.editor().text(call.getScope().orElseThrow())
				+ ".writeString(" + String.join(", ", arguments) + ")"));
	}

	private static boolean filesCall(InspectionContext context, MethodCallExpr call, String method) {
		if (!call.getNameAsString().equals(method) || call.getScope().isEmpty()) {
			return false;
		}
		String scope = call.getScope().orElseThrow().toString();
		if ("java.nio.file.Files".equals(scope)) {
			return true;
		}
		if (!"Files".equals(scope)) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> !imported.isStatic() && "java.nio.file.Files".equals(imported.getNameAsString()));
	}

	private record Candidate(Node node, String method, String replacement) {
	}

}
