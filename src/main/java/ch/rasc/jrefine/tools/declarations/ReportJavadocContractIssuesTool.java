package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reports source-local mismatches between Javadoc tags and their declarations. */
public final class ReportJavadocContractIssuesTool implements InspectionTool {

	private static final Pattern BLOCK_TAG = Pattern
		.compile("(?m)^[ \\t]*\\*?[ \\t]*@([A-Za-z]+)\\b[ \\t]*([^\\r\\n]*)");

	private static final Set<String> VALUE_REQUIRED_TAGS = Set.of("author", "exception", "see", "serialfield", "since",
			"throws", "version");

	@Override
	public String id() {
		return "report-javadoc-contract-issues";
	}

	@Override
	public String description() {
		return "Report dangling Javadoc and declaration, parameter, return, and deprecation mismatches";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		packageInfo(context, findings);
		for (JavadocComment comment : javadocs(context)) {
			Node declaration = comment.getCommentedNode().orElse(null);
			if (!documentableDeclaration(declaration) && packageDocumentation(context, comment)) {
				declaration = context.compilationUnit().getPackageDeclaration().orElse(null);
				if (declaration == null) {
					continue;
				}
			}
			if (!documentableDeclaration(declaration)) {
				findings
					.add(Finding.at(comment, "Dangling Javadoc comment is not attached to a documentable declaration"));
				continue;
			}
			declarationTags(comment, declaration, findings);
			deprecatedContract(comment, declaration, findings);
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void packageInfo(InspectionContext context, List<Finding> findings) {
		if (!isPackageInfo(context) || context.compilationUnit().getPackageDeclaration().isPresent()) {
			return;
		}
		Node location = javadocs(context).stream()
			.findFirst()
			.map(Node.class::cast)
			.or(() -> context.compilationUnit().getTypes().stream().findFirst().map(Node.class::cast))
			.orElse(context.compilationUnit());
		if (location.getBegin().isPresent()) {
			findings.add(Finding.at(location, "package-info.java does not contain a package declaration"));
		}
	}

	private static boolean packageDocumentation(InspectionContext context, JavadocComment comment) {
		return isPackageInfo(context)
				&& javadocs(context).stream().findFirst().filter(candidate -> candidate == comment).isPresent();
	}

	private static boolean isPackageInfo(InspectionContext context) {
		return "package-info.java".equalsIgnoreCase(context.path().getFileName().toString());
	}

	private static void declarationTags(JavadocComment comment, Node declaration, List<Finding> findings) {
		List<BlockTag> tags = blockTags(comment);
		Set<String> allowedParameters = allowedParameters(declaration);
		HashSet<String> seenParameters = new HashSet<>();
		for (BlockTag tag : tags) {
			if ("param".equals(tag.name())) {
				String parameter = firstWord(tag.value());
				if (parameter.isEmpty()) {
					findings.add(Finding.at(comment, "Javadoc @param tag has no parameter name"));
				}
				else if (!seenParameters.add(parameter)) {
					findings.add(Finding.at(comment, "Javadoc has duplicate @param tag for '" + parameter + "'"));
				}
				else if (!allowedParameters.contains(parameter)) {
					findings.add(Finding.at(comment,
							"Javadoc @param tag does not match declaration parameter '" + parameter + "'"));
				}
			}
			else if (VALUE_REQUIRED_TAGS.contains(tag.name()) && firstWord(tag.value()).isEmpty()) {
				findings.add(Finding.at(comment, "Javadoc @" + tag.name() + " tag has no value"));
			}
		}
		long returns = tags.stream().filter(tag -> "return".equals(tag.name())).count();
		if (returns > 1) {
			findings.add(Finding.at(comment, "Javadoc has duplicate @return tags"));
		}
		if (returns > 0 && !returnValueDeclaration(declaration)) {
			findings.add(Finding.at(comment, "Javadoc @return tag does not match a value-returning declaration"));
		}
		long deprecated = tags.stream().filter(tag -> "deprecated".equals(tag.name())).count();
		if (deprecated > 1) {
			findings.add(Finding.at(comment, "Javadoc has duplicate @deprecated tags"));
		}
	}

	private static void deprecatedContract(JavadocComment comment, Node declaration, List<Finding> findings) {
		boolean deprecatedTag = blockTags(comment).stream().anyMatch(tag -> "deprecated".equals(tag.name()));
		if (!deprecatedTag || !(declaration instanceof BodyDeclaration<?> body)
				|| !(body instanceof NodeWithAnnotations<?> annotated)) {
			return;
		}
		boolean annotation = annotated.getAnnotations()
			.stream()
			.anyMatch(value -> "Deprecated".equals(value.getName().getIdentifier()));
		if (!annotation) {
			findings.add(Finding.at(comment, "Javadoc @deprecated tag is missing a @Deprecated annotation"));
		}
	}

	private static Set<String> allowedParameters(Node declaration) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		if (declaration instanceof CallableDeclaration<?> callable) {
			callable.getParameters().stream().map(parameter -> parameter.getNameAsString()).forEach(result::add);
		}
		if (declaration instanceof RecordDeclaration record) {
			record.getParameters().stream().map(parameter -> parameter.getNameAsString()).forEach(result::add);
		}
		if (declaration instanceof NodeWithTypeParameters<?> typed) {
			typed.getTypeParameters()
				.stream()
				.map(parameter -> "<" + parameter.getNameAsString() + ">")
				.forEach(result::add);
		}
		return result;
	}

	private static boolean returnValueDeclaration(Node declaration) {
		return declaration instanceof MethodDeclaration method && !method.getType().isVoidType()
				|| declaration instanceof AnnotationMemberDeclaration;
	}

	private static boolean documentableDeclaration(Node declaration) {
		return declaration instanceof BodyDeclaration<?> || declaration instanceof PackageDeclaration
				|| declaration instanceof ModuleDeclaration;
	}

	private static List<BlockTag> blockTags(JavadocComment comment) {
		ArrayList<BlockTag> result = new ArrayList<>();
		Matcher matcher = BLOCK_TAG.matcher(comment.getContent());
		while (matcher.find()) {
			result.add(new BlockTag(matcher.group(1).toLowerCase(Locale.ROOT), matcher.group(2).strip()));
		}
		return result;
	}

	private static String firstWord(String value) {
		String stripped = value.strip();
		int whitespace = -1;
		for (int index = 0; index < stripped.length(); index++) {
			if (Character.isWhitespace(stripped.charAt(index))) {
				whitespace = index;
				break;
			}
		}
		return whitespace < 0 ? stripped : stripped.substring(0, whitespace);
	}

	private static List<JavadocComment> javadocs(InspectionContext context) {
		return context.compilationUnit()
			.getAllComments()
			.stream()
			.filter(JavadocComment.class::isInstance)
			.map(JavadocComment.class::cast)
			.toList();
	}

	private record BlockTag(String name, String value) {
	}

}
