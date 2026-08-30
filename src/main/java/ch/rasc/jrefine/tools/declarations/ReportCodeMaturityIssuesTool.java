package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.github.javaparser.ast.expr.AnnotationExpr;

/**
 * Reports diagnostic leftovers, deprecated APIs, finalization, obsolete types, and
 * maturity concerns.
 */
public final class ReportCodeMaturityIssuesTool implements PolicyInspectionTool {

	private static final int MIN_COMMENTED_CODE_LINES = 4;

	private static final Pattern CODE_COMMENT = Pattern
		.compile("(?s).*(?:\\b(?:class|interface|enum|record|if|for|while|return|throw|new)\\b|[;{}]).*");

	private static final Set<String> OBSOLETE_COLLECTIONS = Set.of("Hashtable", "Stack", "Vector");

	private static final Set<String> OBSOLETE_DATE_TIME = Set.of("Calendar", "Date", "GregorianCalendar",
			"SimpleTimeZone", "TimeZone");

	@Override
	public String id() {
		return "report-code-maturity-issues";
	}

	@Override
	public String description() {
		return "Report debug output, deprecated APIs, finalization, Optional nulls, and obsolete JDK types";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		debugOutput(context, findings);
		comments(context, findings);
		deprecatedUsage(context, findings);
		extractionCandidates(context, findings);
		optionalNulls(context, findings);
		scheduledForRemoval(context, findings);
		obsoleteTypes(context, findings);
		finalization(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void debugOutput(InspectionContext context, List<Finding> findings) {
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if ("printStackTrace".equals(call.getNameAsString()) && call.getArguments().isEmpty()) {
				findings.add(Finding.at(call, "Call to printStackTrace()"));
			}
			if ("dumpStack".equals(call.getNameAsString()) && call.getArguments().isEmpty()
					&& call.getScope()
						.filter(scope -> Set.of("Thread", "java.lang.Thread").contains(scope.toString()))
						.isPresent()) {
				findings.add(Finding.at(call, "Call to Thread.dumpStack()"));
			}
			if (Set.of("print", "println").contains(call.getNameAsString()) && call.getArguments().size() == 1
					&& call.getScope()
						.filter(scope -> Set.of("System.out", "java.lang.System.out").contains(scope.toString()))
						.isPresent()
					&& throwable(context, call.getArgument(0), call)) {
				findings.add(Finding.at(call, "Throwable is printed to System.out"));
			}
		}
	}

	private static boolean throwable(InspectionContext context, Expression expression, Node use) {
		String type = null;
		if (expression instanceof ObjectCreationExpr creation) {
			type = creation.getType().getNameAsString();
		}
		if (expression instanceof NameExpr) {
			type = TypeLookup.visibleType(context.compilationUnit(), expression, use).orElse(null);
		}
		if (type == null) {
			return false;
		}
		type = simple(type);
		return "Throwable".equals(type) || "Exception".equals(type) || "Error".equals(type)
				|| type.endsWith("Exception") || type.endsWith("Error");
	}

	private static void comments(InspectionContext context, List<Finding> findings) {
		ArrayList<Comment> lineComments = new ArrayList<>();
		List<Comment> comments = context.compilationUnit()
			.getAllContainedComments()
			.stream()
			.filter(comment -> !(comment instanceof JavadocComment))
			.sorted(Comparator.comparingInt(
					(Comment comment) -> comment.getBegin().map(position -> position.line).orElse(Integer.MAX_VALUE))
				.thenComparingInt(
						comment -> comment.getBegin().map(position -> position.column).orElse(Integer.MAX_VALUE)))
			.toList();
		for (Comment comment : comments) {
			if (comment instanceof LineComment) {
				if (!lineComments.isEmpty() && !immediatelyFollows(lineComments.getLast(), comment)) {
					reportLineComments(lineComments, findings);
					lineComments.clear();
				}
				lineComments.add(comment);
				continue;
			}
			reportLineComments(lineComments, findings);
			lineComments.clear();
			if (contentLines(comment) >= MIN_COMMENTED_CODE_LINES && looksLikeCode(comment.getContent())) {
				findings.add(Finding.at(comment, "Commented out code"));
			}
		}
		reportLineComments(lineComments, findings);
	}

	private static void reportLineComments(List<Comment> comments, List<Finding> findings) {
		if (comments.stream().filter(comment -> !comment.getContent().isBlank()).count() < MIN_COMMENTED_CODE_LINES) {
			return;
		}
		String content = String.join(LineEndingSupport.LINE_FEED, comments.stream().map(Comment::getContent).toList());
		if (looksLikeCode(content)) {
			findings.add(Finding.at(comments.getFirst(), "Commented out code"));
		}
	}

	private static boolean immediatelyFollows(Comment previous, Comment next) {
		return previous.getEnd().isPresent() && next.getBegin().isPresent()
				&& previous.getEnd().orElseThrow().line + 1 == next.getBegin().orElseThrow().line;
	}

	private static long contentLines(Comment comment) {
		return comment.getContent().lines().filter(line -> !line.isBlank()).count();
	}

	private static boolean looksLikeCode(String value) {
		String content = value.strip();
		return !content.startsWith("TODO") && !content.startsWith("FIXME") && CODE_COMMENT.matcher(content).matches();
	}

	private static void deprecatedUsage(InspectionContext context, List<Finding> findings) {
		HashSet<String> deprecatedTypes = new HashSet<>();
		HashSet<String> deprecatedMembers = new HashSet<>();
		HashSet<String> removalTypes = new HashSet<>();
		HashSet<String> removalMembers = new HashSet<>();
		for (ClassOrInterfaceDeclaration type : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
			AnnotationExpr annotation = type.getAnnotationByName("Deprecated").orElse(null);
			if (annotation != null) {
				deprecatedTypes.add(type.getNameAsString());
				if (forRemoval(annotation)) {
					removalTypes.add(type.getNameAsString());
				}
			}
		}
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			AnnotationExpr annotation = method.getAnnotationByName("Deprecated").orElse(null);
			if (annotation != null) {
				deprecatedMembers.add(method.getNameAsString());
				if (forRemoval(annotation)) {
					removalMembers.add(method.getNameAsString());
				}
			}
		}
		for (FieldDeclaration field : context.compilationUnit().findAll(FieldDeclaration.class)) {
			AnnotationExpr annotation = field.getAnnotationByName("Deprecated").orElse(null);
			if (annotation == null) {
				continue;
			}
			field.getVariables().forEach(variable -> deprecatedMembers.add(variable.getNameAsString()));
			if (forRemoval(annotation)) {
				field.getVariables().forEach(variable -> removalMembers.add(variable.getNameAsString()));
			}
		}
		context.compilationUnit()
			.findAll(ClassOrInterfaceType.class)
			.stream()
			.filter(type -> deprecatedTypes.contains(type.getNameAsString()))
			.forEach(type -> deprecatedFinding(type, removalTypes.contains(type.getNameAsString()), findings));
		context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> deprecatedMembers.contains(call.getNameAsString()))
			.forEach(call -> deprecatedFinding(call, removalMembers.contains(call.getNameAsString()), findings));
		context.compilationUnit()
			.findAll(FieldAccessExpr.class)
			.stream()
			.filter(access -> deprecatedMembers.contains(access.getNameAsString()))
			.forEach(access -> deprecatedFinding(access, removalMembers.contains(access.getNameAsString()), findings));
		context.compilationUnit()
			.findAll(NameExpr.class)
			.stream()
			.filter(name -> deprecatedMembers.contains(name.getNameAsString()))
			.forEach(name -> deprecatedFinding(name, removalMembers.contains(name.getNameAsString()), findings));
	}

	private static void deprecatedFinding(Node node, boolean removal, List<Finding> findings) {
		findings.add(Finding.at(node, "Deprecated API usage"));
		findings.add(Finding.at(node, "Deprecated member is still used"));
		if (removal) {
			findings.add(Finding.at(node, "Usage of API marked for removal"));
		}
	}

	private static boolean forRemoval(AnnotationExpr annotation) {
		return annotation.isNormalAnnotationExpr() && annotation.asNormalAnnotationExpr()
			.getPairs()
			.stream()
			.anyMatch(pair -> "forRemoval".equals(pair.getNameAsString()) && pair.getValue().isBooleanLiteralExpr()
					&& pair.getValue().asBooleanLiteralExpr().getValue());
	}

	private static void extractionCandidates(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(MethodDeclaration.class)
			.stream()
			.filter(method -> method.getBody().filter(body -> body.getStatements().size() > 20).isPresent())
			.forEach(method -> findings
				.add(Finding.at(method, "Long method contains a fragment that can be extracted")));
	}

	private static void optionalNulls(InspectionContext context, List<Finding> findings) {
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if ("Optional".equals(simple(variable.getType().asString()))
					&& variable.getInitializer().orElse(null) instanceof NullLiteralExpr) {
				findings.add(Finding.at(variable, "Null value is used for Optional type"));
			}
		}
		for (AssignExpr assignment : context.compilationUnit().findAll(AssignExpr.class)) {
			if (!(assignment.getValue() instanceof NullLiteralExpr)) {
				continue;
			}
			String type = TypeLookup.visibleType(context.compilationUnit(), assignment.getTarget(), assignment)
				.map(ReportCodeMaturityIssuesTool::simple)
				.orElse("");
			if ("Optional".equals(type)) {
				findings.add(Finding.at(assignment, "Null value is assigned to Optional type"));
			}
		}
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			if (!"Optional".equals(simple(method.getType().asString())) || method.getBody().isEmpty()) {
				continue;
			}
			method.getBody()
				.orElseThrow()
				.findAll(ReturnStmt.class)
				.stream()
				.filter(returned -> returned.getExpression().orElse(null) instanceof NullLiteralExpr)
				.forEach(returned -> findings.add(Finding.at(returned, "Null is returned for Optional type")));
		}
	}

	private static void scheduledForRemoval(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(AnnotationExpr.class)
			.stream()
			.filter(annotation -> "ScheduledForRemoval".equals(annotation.getName().getIdentifier()))
			.filter(annotation -> !annotation.isNormalAnnotationExpr() || annotation.asNormalAnnotationExpr()
				.getPairs()
				.stream()
				.noneMatch(pair -> "inVersion".equals(pair.getNameAsString())))
			.forEach(annotation -> findings
				.add(Finding.at(annotation, "Redundant @ScheduledForRemoval annotation has no inVersion")));
	}

	private static void obsoleteTypes(InspectionContext context, List<Finding> findings) {
		for (ClassOrInterfaceType type : context.compilationUnit().findAll(ClassOrInterfaceType.class)) {
			String simple = type.getNameAsString();
			if (OBSOLETE_COLLECTIONS.contains(simple) && knownJavaUtil(context, type, simple)) {
				findings.add(Finding.at(type, "Use of obsolete collection type " + simple));
			}
			if (OBSOLETE_DATE_TIME.contains(simple) && knownJavaUtil(context, type, simple)) {
				findings.add(Finding.at(type, "Use of obsolete date-time API " + simple));
			}
		}
	}

	private static void finalization(InspectionContext context, List<Finding> findings) {
		for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
			if (!finalizer(method)) {
				continue;
			}
			findings.add(Finding.at(method, "finalize() overrides deprecated finalization and should be removed"));
			if (method.isPublic()) {
				findings.add(Finding.at(method, "finalize() should not be public"));
			}
		}
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			if (!"finalize".equals(call.getNameAsString()) || !call.getArguments().isEmpty()
					|| supportingSuperFinalize(call)) {
				continue;
			}
			findings.add(Finding.at(call, "finalize() is called explicitly"));
		}
	}

	private static boolean finalizer(MethodDeclaration method) {
		return "finalize".equals(method.getNameAsString()) && method.getParameters().isEmpty()
				&& method.getType().isVoidType()
				&& method.findAncestor(ClassOrInterfaceDeclaration.class)
					.filter(ClassOrInterfaceDeclaration::isInterface)
					.isEmpty();
	}

	private static boolean supportingSuperFinalize(MethodCallExpr call) {
		return call.getScope().filter(SuperExpr.class::isInstance).isPresent()
				&& call.findAncestor(MethodDeclaration.class)
					.filter(ReportCodeMaturityIssuesTool::finalizer)
					.isPresent();
	}

	private static boolean knownJavaUtil(InspectionContext context, ClassOrInterfaceType type, String simple) {
		if (type.getScope().isPresent()) {
			return type.toString().startsWith("java.util.");
		}
		if (context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.anyMatch(declaration -> declaration.getNameAsString().equals(simple))) {
			return false;
		}
		return context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> imported.getNameAsString().equals("java.util." + simple)
					|| imported.isAsterisk() && "java.util".equals(imported.getNameAsString()));
	}

	private static String simple(String type) {
		String currentType = type;
		int generic = currentType.indexOf('<');
		if (generic >= 0) {
			currentType = currentType.substring(0, generic);
		}
		int dot = currentType.lastIndexOf('.');
		return dot < 0 ? currentType : currentType.substring(dot + 1);
	}

}
