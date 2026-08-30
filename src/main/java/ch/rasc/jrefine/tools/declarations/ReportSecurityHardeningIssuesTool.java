package ch.rasc.jrefine.tools.declarations;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reports security-sensitive APIs whose hardening is not visible in the source. */
public final class ReportSecurityHardeningIssuesTool implements PolicyInspectionTool {

	private static final Set<String> DESERIALIZATION_METHODS = Set.of("readObject", "readUnshared");

	private static final Set<String> FILE_WRITE_METHODS = Set.of("copy", "newOutputStream", "write", "writeString");

	@Override
	public String id() {
		return "report-security-hardening-issues";
	}

	@Override
	public String description() {
		return "Report unfiltered deserialization, insecure XML, weak cryptography, and zip slip risks";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
			unfilteredDeserialization(context, call, findings);
			insecureXmlProcessing(context, call, findings);
			weakCryptography(context, call, findings);
			directZipSlip(context, call, findings);
		}
		zipSlipVariables(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void unfilteredDeserialization(InspectionContext context, MethodCallExpr call,
			List<Finding> findings) {
		if (!DESERIALIZATION_METHODS.contains(call.getNameAsString()) || call.getScope().isEmpty()
				|| !receiverType(context, call, "java.io", Set.of("ObjectInputStream"))) {
			return;
		}
		String receiver = simpleReceiver(call.getScope().orElseThrow());
		if (receiver == null || hasObjectInputFilter(context, call, receiver)) {
			return;
		}
		findings.add(Finding.at(call,
				"ObjectInputStream deserialization has no visible ObjectInputFilter; configure a per-stream or global filter"));
	}

	private static boolean hasObjectInputFilter(InspectionContext context, MethodCallExpr read, String receiver) {
		CallableDeclaration<?> callable = callable(read);
		if (callable == null) {
			return false;
		}
		return callable.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> before(call, read))
			.anyMatch(call -> "setObjectInputFilter".equals(call.getNameAsString())
					&& call.getScope().map(Object::toString).filter(receiver::equals).isPresent()
					|| Set.of("setSerialFilter", "setSerialFilterFactory").contains(call.getNameAsString())
							&& objectInputFilterConfig(context, call));
	}

	private static boolean objectInputFilterConfig(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty()) {
			return false;
		}
		String scope = call.getScope().orElseThrow().toString();
		if (scope.equals("ObjectInputFilter.Config") || scope.equals("java.io.ObjectInputFilter.Config")) {
			return TypeLookup.isKnownType(context.compilationUnit(), "ObjectInputFilter", "java.io",
					Set.of("ObjectInputFilter"));
		}
		return scope.equals("Config") && context.compilationUnit()
			.getImports()
			.stream()
			.anyMatch(imported -> imported.getNameAsString().equals("java.io.ObjectInputFilter.Config"));
	}

	private static void insecureXmlProcessing(InspectionContext context, MethodCallExpr terminal,
			List<Finding> findings) {
		XmlFactory kind = xmlFactory(context, terminal);
		if (kind == null) {
			return;
		}
		Expression scope = terminal.getScope().orElseThrow();
		String receiver = simpleReceiver(scope);
		if (receiver != null) {
			VariableDeclarator factory = localFactory(context, terminal, receiver, kind);
			if (factory == null || hardenedXmlFactory(terminal, receiver, kind)) {
				return;
			}
		}
		else if (!directFactoryCreation(context, scope, kind)) {
			return;
		}
		findings.add(Finding.at(terminal, kind.label + " is used without complete source-visible XML hardening"));
	}

	private static XmlFactory xmlFactory(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty()) {
			return null;
		}
		for (XmlFactory kind : XmlFactory.values()) {
			if (!kind.terminals.contains(call.getNameAsString())) {
				continue;
			}
			Expression scope = call.getScope().orElseThrow();
			if (scope instanceof NameExpr && TypeLookup.visibleType(context.compilationUnit(), scope, call)
				.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, kind.packageName,
						Set.of(kind.type)))
				.isPresent()) {
				return kind;
			}
			if (directFactoryCreation(context, scope, kind)) {
				return kind;
			}
		}
		return null;
	}

	private static VariableDeclarator localFactory(InspectionContext context, MethodCallExpr use, String name,
			XmlFactory kind) {
		CallableDeclaration<?> owner = callable(use);
		if (owner == null) {
			return null;
		}
		return owner.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> variable.getNameAsString().equals(name))
			.filter(variable -> before(variable, use))
			.filter(variable -> TypeLookup.isKnownType(context.compilationUnit(), variable.getType().asString(),
					kind.packageName, Set.of(kind.type)))
			.filter(variable -> variable.getInitializer()
				.filter(initializer -> directFactoryCreation(context, initializer, kind))
				.isPresent())
			.reduce((first, second) -> second)
			.orElse(null);
	}

	private static boolean directFactoryCreation(InspectionContext context, Expression expression, XmlFactory kind) {
		if (!(expression instanceof MethodCallExpr creation) || creation.getScope().isEmpty()
				|| !kind.creators.contains(creation.getNameAsString())) {
			return false;
		}
		return TypeLookup.isKnownType(context.compilationUnit(), creation.getScope().orElseThrow().toString(),
				kind.packageName, Set.of(kind.type));
	}

	private static boolean hardenedXmlFactory(MethodCallExpr terminal, String receiver, XmlFactory kind) {
		CallableDeclaration<?> owner = callable(terminal);
		if (owner == null) {
			return false;
		}
		List<MethodCallExpr> configuration = owner.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> before(call, terminal))
			.filter(call -> call.getScope().map(Object::toString).filter(receiver::equals).isPresent())
			.toList();
		return switch (kind) {
			case DOCUMENT_BUILDER,
					SCHEMA ->
				hasTrueFeature(configuration, "FEATURE_SECURE_PROCESSING")
						&& hasEmptyProperty(configuration, "ACCESS_EXTERNAL_DTD")
						&& hasEmptyProperty(configuration, "ACCESS_EXTERNAL_SCHEMA");
			case SAX_PARSER -> hasTrueFeature(configuration, "FEATURE_SECURE_PROCESSING")
					&& hasFalseFeature(configuration, "external-general-entities")
					&& hasFalseFeature(configuration, "external-parameter-entities")
					&& hasFalseFeature(configuration, "load-external-dtd");
			case STAX -> hasFalseProperty(configuration, "SUPPORT_DTD")
					&& hasFalseProperty(configuration, "IS_SUPPORTING_EXTERNAL_ENTITIES");
			case TRANSFORMER -> hasTrueFeature(configuration, "FEATURE_SECURE_PROCESSING")
					&& hasEmptyProperty(configuration, "ACCESS_EXTERNAL_DTD")
					&& hasEmptyProperty(configuration, "ACCESS_EXTERNAL_STYLESHEET");
		};
	}

	private static boolean hasTrueFeature(List<MethodCallExpr> calls, String key) {
		return calls.stream()
			.anyMatch(call -> "setFeature".equals(call.getNameAsString()) && keyedBoolean(call, key, true));
	}

	private static boolean hasFalseFeature(List<MethodCallExpr> calls, String key) {
		return calls.stream()
			.anyMatch(call -> "setFeature".equals(call.getNameAsString()) && keyedBoolean(call, key, false));
	}

	private static boolean hasFalseProperty(List<MethodCallExpr> calls, String key) {
		return calls.stream()
			.anyMatch(call -> "setProperty".equals(call.getNameAsString()) && keyedBoolean(call, key, false));
	}

	private static boolean keyedBoolean(MethodCallExpr call, String key, boolean expected) {
		return call.getArguments().size() == 2 && keyMatches(call.getArgument(0), key)
				&& call.getArgument(1) instanceof BooleanLiteralExpr literal && literal.getValue() == expected;
	}

	private static boolean hasEmptyProperty(List<MethodCallExpr> calls, String key) {
		return calls.stream()
			.anyMatch(call -> Set.of("setAttribute", "setProperty").contains(call.getNameAsString())
					&& call.getArguments().size() == 2 && keyMatches(call.getArgument(0), key)
					&& call.getArgument(1) instanceof StringLiteralExpr literal && literal.getValue().isEmpty());
	}

	private static boolean keyMatches(Expression expression, String key) {
		if (expression instanceof StringLiteralExpr literal) {
			return literal.getValue().toLowerCase(Locale.ROOT).contains(key.toLowerCase(Locale.ROOT).replace('_', '-'));
		}
		return expression.toString().endsWith("." + key) || expression.toString().equals(key);
	}

	private static void weakCryptography(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		if (!"getInstance".equals(call.getNameAsString()) || call.getArguments().isEmpty()
				|| !(call.getArgument(0) instanceof StringLiteralExpr literal)) {
			return;
		}
		String algorithm = literal.getValue().replace(" ", "").toUpperCase(Locale.ROOT);
		String issue = null;
		if (staticOwner(context, call, "java.security", "MessageDigest")
				&& Set.of("MD2", "MD5", "SHA", "SHA1", "SHA-1").contains(algorithm)) {
			issue = "Weak message-digest algorithm '" + literal.getValue() + "'";
		}
		else if (staticOwner(context, call, "javax.crypto", "Cipher") && weakCipher(algorithm)) {
			issue = "Weak or underspecified cipher transformation '" + literal.getValue() + "'";
		}
		else if (staticOwner(context, call, "javax.crypto", "Mac")
				&& Set.of("HMACMD5", "HMACSHA1", "HMACSHA-1").contains(algorithm)) {
			issue = "Weak MAC algorithm '" + literal.getValue() + "'";
		}
		else if (staticOwner(context, call, "java.security", "Signature")
				&& (algorithm.startsWith("MD2WITH") || algorithm.startsWith("MD5WITH")
						|| algorithm.startsWith("SHA1WITH") || algorithm.startsWith("SHA-1WITH"))) {
			issue = "Weak signature algorithm '" + literal.getValue() + "'";
		}
		if (issue != null) {
			findings.add(Finding.at(call, issue));
		}
	}

	private static boolean weakCipher(String algorithm) {
		String primitive = algorithm.split("/", -1)[0];
		String mode = algorithm.contains("/") ? algorithm.split("/", -1)[1] : "";
		return Set.of("DES", "DESEDE", "TRIPLEDES", "RC2", "RC4", "ARCFOUR", "BLOWFISH").contains(primitive)
				|| "ECB".equals(mode) || Set.of("AES", "CAMELLIA", "SEED").contains(primitive) && mode.isEmpty();
	}

	private static void zipSlipVariables(InspectionContext context, List<Finding> findings) {
		for (VariableDeclarator target : context.compilationUnit().findAll(VariableDeclarator.class)) {
			Expression initializer = target.getInitializer().orElse(null);
			ZipResolution resolution = zipResolution(context, initializer, target);
			if (resolution == null) {
				continue;
			}
			CallableDeclaration<?> owner = callable(target);
			if (owner == null) {
				continue;
			}
			MethodCallExpr methodSink = owner.findAll(MethodCallExpr.class)
				.stream()
				.filter(call -> before(target, call))
				.filter(call -> fileSink(context, call, target.getNameAsString()))
				.findFirst()
				.orElse(null);
			ObjectCreationExpr streamSink = owner.findAll(ObjectCreationExpr.class)
				.stream()
				.filter(creation -> before(target, creation))
				.filter(creation -> outputStreamSink(context, creation, target.getNameAsString()))
				.findFirst()
				.orElse(null);
			Node sink = methodSink != null ? methodSink : streamSink;
			if (sink != null && (!resolution.normalized()
					|| !hasContainmentGuard(owner, target.getNameAsString(), resolution.root(), sink))) {
				findings.add(Finding.at(target,
						"Archive entry path reaches a file-write sink without a normalized containment check (zip slip)"));
			}
		}
	}

	private static void directZipSlip(InspectionContext context, MethodCallExpr call, List<Finding> findings) {
		if (!FILE_WRITE_METHODS.contains(call.getNameAsString())
				|| !staticOwner(context, call, "java.nio.file", "Files")) {
			return;
		}
		if (call.getArguments().stream().anyMatch(argument -> zipResolution(context, argument, call) != null)) {
			findings.add(Finding.at(call,
					"Archive entry path reaches a file-write sink without a normalized containment check (zip slip)"));
		}
	}

	private static ZipResolution zipResolution(InspectionContext context, Expression expression, Node use) {
		Expression candidate = expression;
		boolean normalized = false;
		if (candidate instanceof MethodCallExpr normalize && "normalize".equals(normalize.getNameAsString())
				&& normalize.getScope().isPresent()) {
			normalized = true;
			candidate = normalize.getScope().orElseThrow();
		}
		if (!(candidate instanceof MethodCallExpr resolve) || !"resolve".equals(resolve.getNameAsString())
				|| resolve.getScope().isEmpty() || resolve.getArguments().size() != 1
				|| !(resolve.getArgument(0) instanceof MethodCallExpr entryName)
				|| !"getName".equals(entryName.getNameAsString()) || entryName.getScope().isEmpty()) {
			return null;
		}
		Expression entry = entryName.getScope().orElseThrow();
		if (TypeLookup.visibleType(context.compilationUnit(), entry, use)
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, "java.util.zip",
					Set.of("ZipEntry")))
			.isEmpty()) {
			return null;
		}
		return new ZipResolution(resolve.getScope().orElseThrow().toString(), normalized);
	}

	private static boolean fileSink(InspectionContext context, MethodCallExpr call, String target) {
		return FILE_WRITE_METHODS.contains(call
			.getNameAsString()) && staticOwner(context, call, "java.nio.file", "Files") && call.getArguments()
				.stream()
				.anyMatch(argument -> argument.isNameExpr() && argument.asNameExpr().getNameAsString().equals(target));
	}

	private static boolean outputStreamSink(InspectionContext context, ObjectCreationExpr creation, String target) {
		if (!TypeLookup.isKnownType(context.compilationUnit(), creation.getType().asString(), "java.io",
				Set.of("FileOutputStream"))) {
			return false;
		}
		return creation.getArguments()
			.stream()
			.anyMatch(
					argument -> argument.toString().equals(target) || argument.toString().equals(target + ".toFile()"));
	}

	private static boolean hasContainmentGuard(CallableDeclaration<?> owner, String target, String root, Node sink) {
		return owner.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> before(call, sink))
			.anyMatch(call -> "startsWith".equals(call.getNameAsString())
					&& call.getScope()
						.map(Object::toString)
						.filter(scope -> scope.equals(target) || scope.equals(target + ".normalize()"))
						.isPresent()
					&& call.getArguments().size() == 1
					&& Set.of(root, root + ".normalize()").contains(call.getArgument(0).toString()));
	}

	private static boolean receiverType(InspectionContext context, MethodCallExpr call, String packageName,
			Set<String> types) {
		Expression receiver = call.getScope().orElseThrow();
		return TypeLookup.visibleType(context.compilationUnit(), receiver, call)
			.filter(type -> TypeLookup.isKnownType(context.compilationUnit(), type, packageName, types))
			.isPresent();
	}

	private static boolean staticOwner(InspectionContext context, MethodCallExpr call, String packageName,
			String type) {
		return call.getScope()
			.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), packageName,
					Set.of(type)))
			.isPresent();
	}

	private static String simpleReceiver(Expression expression) {
		return expression instanceof NameExpr name ? name.getNameAsString() : null;
	}

	private static CallableDeclaration<?> callable(Node node) {
		return node.findAncestor(CallableDeclaration.class).orElse(null);
	}

	private static boolean before(Node first, Node second) {
		Position left = first.getBegin().orElse(Position.HOME);
		Position right = second.getBegin().orElse(Position.HOME);
		return left.line < right.line || left.line == right.line && left.column < right.column;
	}

	private enum XmlFactory {

		DOCUMENT_BUILDER("javax.xml.parsers", "DocumentBuilderFactory", "DocumentBuilderFactory",
				Set.of("newDocumentBuilder"), Set.of("newInstance", "newDefaultInstance")),
		SAX_PARSER("javax.xml.parsers", "SAXParserFactory", "SAXParserFactory", Set.of("newSAXParser"),
				Set.of("newInstance", "newDefaultInstance")),
		STAX("javax.xml.stream", "XMLInputFactory", "XMLInputFactory",
				Set.of("createXMLStreamReader", "createXMLEventReader"),
				Set.of("newInstance", "newFactory", "newDefaultFactory")),
		TRANSFORMER("javax.xml.transform", "TransformerFactory", "TransformerFactory",
				Set.of("newTransformer", "newTemplates"), Set.of("newInstance", "newDefaultInstance")),
		SCHEMA("javax.xml.validation", "SchemaFactory", "SchemaFactory", Set.of("newSchema"),
				Set.of("newInstance", "newDefaultInstance"));

		private final String packageName;

		private final String type;

		private final String label;

		private final Set<String> terminals;

		private final Set<String> creators;

		XmlFactory(String packageName, String type, String label, Set<String> terminals, Set<String> creators) {
			this.packageName = packageName;
			this.type = type;
			this.label = label;
			this.terminals = terminals;
			this.creators = creators;
		}

	}

	private record ZipResolution(String root, boolean normalized) {
	}

}
