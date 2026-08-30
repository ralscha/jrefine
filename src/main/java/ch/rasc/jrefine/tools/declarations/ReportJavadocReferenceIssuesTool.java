package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reports malformed HTML and invalid source-local references in Javadoc. */
public final class ReportJavadocReferenceIssuesTool implements InspectionTool {

	private static final Pattern INLINE_REFERENCE = Pattern.compile("\\{@(?:link|linkplain|value)\\b([^}]*)}",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern INLINE_REFERENCE_START = Pattern.compile("\\{@(?:link|linkplain|value)\\b",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern SEE_REFERENCE = Pattern.compile("(?m)^\\s*\\*?\\s*@see\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern INLINE_LITERAL = Pattern.compile("\\{@(?:code|literal)\\s+.*?}",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Set<String> BALANCED_HTML = Set.of("b", "blockquote", "code", "em", "i", "ol", "pre", "strong",
			"table", "td", "th", "tr", "ul");

	@Override
	public String id() {
		return "report-javadoc-reference-issues";
	}

	@Override
	public String description() {
		return "Report malformed Javadoc HTML and invalid source-local link, value, and see references";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		Map<String, TypeDeclaration<?>> types = uniqueTypes(context);
		for (JavadocComment comment : context.compilationUnit()
			.getAllComments()
			.stream()
			.filter(JavadocComment.class::isInstance)
			.map(JavadocComment.class::cast)
			.toList()) {
			references(context, comment, types, findings);
			html(comment, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void references(InspectionContext context, JavadocComment comment,
			Map<String, TypeDeclaration<?>> types, List<Finding> findings) {
		String content = comment.getContent();
		Matcher inline = INLINE_REFERENCE.matcher(content);
		int matchedInline = 0;
		boolean emptyInline = false;
		while (inline.find()) {
			matchedInline++;
			String reference = referenceTarget(inline.group(1));
			if (reference.isEmpty()) {
				emptyInline = true;
			}
			else {
				validateReference(context, comment, reference, types, findings);
			}
		}
		int declaredInline = matches(content, INLINE_REFERENCE_START);
		if (matchedInline != declaredInline || emptyInline) {
			findings.add(Finding.at(comment, "Javadoc contains an empty or unterminated inline reference"));
		}
		Matcher see = SEE_REFERENCE.matcher(content);
		while (see.find()) {
			String reference = referenceTarget(content.substring(see.end()));
			if (!reference.isEmpty()) {
				validateReference(context, comment, reference, types, findings);
			}
		}
	}

	private static String referenceTarget(String raw) {
		String value = raw.replaceAll("(?m)\\R\\s*\\*?\\s*", " ").stripLeading();
		int parentheses = 0;
		int angles = 0;
		int brackets = 0;
		int end = 0;
		for (; end < value.length(); end++) {
			char character = value.charAt(end);
			if (Character.isWhitespace(character) && parentheses == 0 && angles == 0 && brackets == 0) {
				break;
			}
			if (character == '(') {
				parentheses++;
			}
			else if (character == ')' && parentheses > 0) {
				parentheses--;
			}
			else if (character == '<') {
				angles++;
			}
			else if (character == '>' && angles > 0) {
				angles--;
			}
			else if (character == '[') {
				brackets++;
			}
			else if (character == ']' && brackets > 0) {
				brackets--;
			}
		}
		return value.substring(0, end).replaceAll("\\s+", "");
	}

	private static void validateReference(InspectionContext context, JavadocComment comment, String reference,
			Map<String, TypeDeclaration<?>> types, List<Finding> findings) {
		int memberSeparator = reference.indexOf('#');
		if (memberSeparator < 0) {
			return;
		}
		String typePart = reference.substring(0, memberSeparator);
		String memberPart = reference.substring(memberSeparator + 1);
		TypeDeclaration<?> target = typePart.isEmpty() ? containingType(comment) : sourceType(context, typePart, types);
		if (target == null || memberPart.isBlank()) {
			if (target != null) {
				findings.add(Finding.at(comment, "Javadoc reference has no member after '#': " + reference));
			}
			return;
		}
		if (!memberExists(target, memberPart, types, new HashSet<>())) {
			findings
				.add(Finding.at(comment, "Javadoc reference does not resolve to a source-local member: " + reference));
		}
	}

	private static TypeDeclaration<?> containingType(JavadocComment comment) {
		Node declaration = comment.getCommentedNode().orElse(null);
		if (declaration instanceof TypeDeclaration<?> type) {
			return type;
		}
		return declaration == null ? null : declaration.findAncestor(TypeDeclaration.class).orElse(null);
	}

	private static TypeDeclaration<?> sourceType(InspectionContext context, String spelling,
			Map<String, TypeDeclaration<?>> types) {
		String simple = TypeLookup.simpleName(spelling);
		TypeDeclaration<?> type = types.get(simple);
		if (type == null || !spelling.contains(".")) {
			return type;
		}
		String packageName = context.compilationUnit()
			.getPackageDeclaration()
			.map(declaration -> declaration.getNameAsString())
			.orElse("");
		return spelling.equals(packageName + "." + simple) ? type : null;
	}

	private static boolean memberExists(TypeDeclaration<?> type, String spelling, Map<String, TypeDeclaration<?>> types,
			Set<String> visited) {
		if (directMemberExists(type, spelling)) {
			return true;
		}
		if (!(type instanceof ClassOrInterfaceDeclaration declaration) || !visited.add(type.getNameAsString())) {
			return false;
		}
		ArrayList<ClassOrInterfaceType> parents = new ArrayList<>(declaration.getExtendedTypes());
		parents.addAll(declaration.getImplementedTypes());
		boolean unresolvedParent = false;
		for (ClassOrInterfaceType reference : parents) {
			TypeDeclaration<?> parent = types.get(TypeLookup.simpleName(reference.asString()));
			if (parent == null) {
				unresolvedParent = true;
			}
			else if (memberExists(parent, spelling, types, visited)) {
				return true;
			}
		}
		return unresolvedParent || objectMember(spelling);
	}

	private static boolean directMemberExists(TypeDeclaration<?> type, String spelling) {
		int parameters = spelling.indexOf('(');
		String name = parameters < 0 ? spelling : spelling.substring(0, parameters);
		if (name.isBlank()) {
			return false;
		}
		if (parameters >= 0) {
			int closing = spelling.lastIndexOf(')');
			if (closing < parameters) {
				return false;
			}
			int arity = parameterArity(spelling.substring(parameters + 1, closing));
			return type.getMembers()
				.stream()
				.filter(MethodDeclaration.class::isInstance)
				.map(MethodDeclaration.class::cast)
				.anyMatch(method -> method.getNameAsString().equals(name) && method.getParameters().size() == arity)
					|| type.getMembers()
						.stream()
						.filter(ConstructorDeclaration.class::isInstance)
						.map(ConstructorDeclaration.class::cast)
						.anyMatch(constructor -> constructor.getNameAsString().equals(name)
								&& constructor.getParameters().size() == arity)
					|| arity == 0 && type.getMembers()
						.stream()
						.filter(AnnotationMemberDeclaration.class::isInstance)
						.map(AnnotationMemberDeclaration.class::cast)
						.anyMatch(member -> member.getNameAsString().equals(name))
					|| arity == 0 && type instanceof RecordDeclaration record
							&& record.getParameters()
								.stream()
								.anyMatch(component -> component.getNameAsString().equals(name));
		}
		return type.getMembers()
			.stream()
			.filter(FieldDeclaration.class::isInstance)
			.map(FieldDeclaration.class::cast)
			.flatMap(field -> field.getVariables().stream())
			.anyMatch(variable -> variable.getNameAsString().equals(name))
				|| type.getMembers()
					.stream()
					.filter(MethodDeclaration.class::isInstance)
					.map(MethodDeclaration.class::cast)
					.anyMatch(method -> method.getNameAsString().equals(name))
				|| type.getMembers()
					.stream()
					.filter(EnumConstantDeclaration.class::isInstance)
					.map(EnumConstantDeclaration.class::cast)
					.anyMatch(constant -> constant.getNameAsString().equals(name))
				|| type instanceof EnumDeclaration enumeration && enumeration.getEntries()
					.stream()
					.anyMatch(constant -> constant.getNameAsString().equals(name));
	}

	private static boolean objectMember(String spelling) {
		int parameters = spelling.indexOf('(');
		if (parameters < 0 || spelling.lastIndexOf(')') < parameters) {
			return false;
		}
		String name = spelling.substring(0, parameters);
		int arity = parameterArity(spelling.substring(parameters + 1, spelling.lastIndexOf(')')));
		return Set.of("clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString").contains(name)
				&& arity == 0 || "equals".equals(name) && arity == 1 || "wait".equals(name) && arity <= 2;
	}

	private static int parameterArity(String parameters) {
		String value = parameters.strip();
		if (value.isEmpty()) {
			return 0;
		}
		int depth = 0;
		int arity = 1;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '<' || character == '(' || character == '[') {
				depth++;
			}
			else if (character == '>' || character == ')' || character == ']') {
				depth--;
			}
			else if (character == ',' && depth == 0) {
				arity++;
			}
		}
		return arity;
	}

	private static void html(JavadocComment comment, List<Finding> findings) {
		String content = INLINE_LITERAL.matcher(comment.getContent()).replaceAll("");
		for (String tag : BALANCED_HTML) {
			int openings = matches(content, Pattern.compile("(?i)<\\s*" + Pattern.quote(tag) + "(?:\\s[^>]*)?>"));
			int closings = matches(content, Pattern.compile("(?i)</\\s*" + Pattern.quote(tag) + "\\s*>"));
			if (openings != closings) {
				findings.add(Finding.at(comment, "Javadoc HTML has unbalanced <" + tag + "> tags"));
			}
		}
	}

	private static int matches(String input, Pattern pattern) {
		int count = 0;
		Matcher matcher = pattern.matcher(input);
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	private static Map<String, TypeDeclaration<?>> uniqueTypes(InspectionContext context) {
		HashMap<String, List<TypeDeclaration<?>>> grouped = new HashMap<>();
		context.compilationUnit()
			.findAll(TypeDeclaration.class)
			.forEach(type -> grouped.computeIfAbsent(type.getNameAsString(), ignored -> new ArrayList<>()).add(type));
		HashMap<String, TypeDeclaration<?>> result = new HashMap<>();
		grouped.forEach((name, declarations) -> {
			if (declarations.size() == 1) {
				result.put(name, declarations.getFirst());
			}
		});
		return result;
	}

}
