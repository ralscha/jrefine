package ch.rasc.jrefine.tools.controlflow;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import ch.rasc.jrefine.analysis.TypeLookup;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reports locally owned resources that have no visible close or ownership transfer. */
public final class ReportResourceManagementBugsTool implements InspectionTool {

	private static final Set<String> DIRECT_IO_TYPES = Set.of("FileInputStream", "FileOutputStream", "FileReader",
			"FileWriter", "RandomAccessFile");

	private static final Set<String> WRAPPER_IO_TYPES = Set.of("BufferedInputStream", "BufferedOutputStream",
			"BufferedReader", "BufferedWriter", "DataInputStream", "DataOutputStream", "InputStreamReader",
			"OutputStreamWriter", "ObjectInputStream", "ObjectOutputStream", "PrintStream", "PrintWriter",
			"PushbackInputStream", "PushbackReader", "SequenceInputStream");

	private static final Set<String> ZIP_TYPES = Set.of("GZIPInputStream", "GZIPOutputStream", "ZipInputStream",
			"ZipOutputStream", "ZipFile");

	private static final Set<String> JAR_TYPES = Set.of("JarInputStream", "JarOutputStream", "JarFile");

	private static final Set<String> SOCKET_TYPES = Set.of("Socket", "ServerSocket", "DatagramSocket",
			"MulticastSocket");

	private static final Set<String> CHANNEL_TYPES = Set.of("Channel", "ByteChannel", "ReadableByteChannel",
			"WritableByteChannel", "SeekableByteChannel", "FileChannel", "SocketChannel", "ServerSocketChannel",
			"DatagramChannel", "SourceChannel", "SinkChannel", "AsynchronousFileChannel", "AsynchronousSocketChannel",
			"AsynchronousServerSocketChannel");

	private static final Set<String> JDBC_TYPES = Set.of("Connection", "Statement", "PreparedStatement",
			"CallableStatement", "ResultSet");

	private static final Set<String> HIBERNATE_FACTORY_TYPES = Set.of("SessionFactory");

	private static final Set<String> HIBERNATE_RESOURCE_TYPES = Set.of("Session", "StatelessSession");

	private static final Set<String> JNDI_CONTEXT_TYPES = Set.of("Context", "InitialContext");

	private static final Set<String> JNDI_DIRECTORY_TYPES = Set.of("DirContext", "InitialDirContext");

	private static final Set<String> JNDI_LDAP_TYPES = Set.of("InitialLdapContext", "LdapContext");

	private static final Set<String> JNDI_ENUMERATION_TYPES = Set.of("NamingEnumeration");

	private static final Set<String> FILES_FACTORIES = Set.of("lines", "list", "walk", "find", "newInputStream",
			"newOutputStream", "newBufferedReader", "newBufferedWriter", "newByteChannel", "newDirectoryStream");

	private static final Set<String> CHANNEL_FACTORIES = Set.of("open");

	private static final Set<String> JDBC_FACTORIES = Set.of("getConnection", "createStatement", "prepareStatement",
			"prepareCall", "executeQuery", "getResultSet", "getGeneratedKeys");

	@Override
	public String id() {
		return "report-resource-management-bugs";
	}

	@Override
	public String description() {
		return "Report locally owned JDK, JDBC, Hibernate, JNDI, and AutoCloseable resources that are not closed";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		for (VariableDeclarator variable : context.compilationUnit().findAll(VariableDeclarator.class)) {
			if (variable.findAncestor(FieldDeclaration.class).isPresent() || variable.getInitializer().isEmpty()
					|| isTryResource(variable)) {
				continue;
			}
			ResourceKind kind = producedKind(context, variable.getInitializer().orElseThrow(), variable).orElse(null);
			if (kind == null || managedOrTransferred(variable)) {
				continue;
			}
			findings.add(Finding.at(variable, switch (kind) {
				case IO -> "I/O resource is not safely closed";
				case CHANNEL -> "Channel is not safely closed";
				case SOCKET -> "Socket is not safely closed";
				case JDBC -> "JDBC resource is not safely closed";
				case HIBERNATE -> "Hibernate resource is not safely closed";
				case JNDI -> "JNDI resource is not safely closed";
				case AUTO_CLOSEABLE -> "AutoCloseable resource is used without try-with-resources or close()";
			}));
		}
		return new ToolResult(List.copyOf(findings), false);
	}

	private static Optional<ResourceKind> producedKind(InspectionContext context, Expression expression, Node use) {
		if (expression instanceof ObjectCreationExpr creation) {
			String spelling = creation.getType().asString();
			Optional<ResourceKind> direct = directCreationKind(context, spelling);
			if (direct.isPresent()) {
				return direct;
			}
			Optional<ResourceKind> wrapper = wrapperKind(context, spelling);
			if (wrapper.isPresent() && !creation.getArguments().isEmpty()) {
				Expression wrapped = creation.getArgument(0);
				if (producedKind(context, wrapped, creation).isPresent()
						|| wrapped instanceof NameExpr name && visibleLocal(context, name.getNameAsString(), creation)
							.filter(variable -> knownResourceDeclaration(context, variable))
							.isPresent()) {
					return wrapper;
				}
			}
			if (localAutoCloseable(context, spelling)) {
				return Optional.of(ResourceKind.AUTO_CLOSEABLE);
			}
			return Optional.empty();
		}
		if (!(expression instanceof MethodCallExpr call)) {
			return Optional.empty();
		}
		if (staticOwner(context, call, "java.nio.file", "Files") && FILES_FACTORIES.contains(call.getNameAsString())) {
			return Optional
				.of("newByteChannel".equals(call.getNameAsString()) ? ResourceKind.CHANNEL : ResourceKind.IO);
		}
		if (call.getScope().isPresent() && CHANNEL_FACTORIES.contains(call.getNameAsString())) {
			String owner = call.getScope().orElseThrow().toString();
			if (TypeLookup.isKnownType(context.compilationUnit(), owner, "java.nio.channels", CHANNEL_TYPES)) {
				return Optional.of(ResourceKind.CHANNEL);
			}
		}
		if (staticOwner(context, call, "java.sql", "DriverManager") && "getConnection".equals(call.getNameAsString())) {
			return Optional.of(ResourceKind.JDBC);
		}
		if (call.getScope().isPresent() && JDBC_FACTORIES.contains(call.getNameAsString())) {
			String receiver = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
				.orElse("");
			if (TypeLookup.isKnownType(context.compilationUnit(), receiver, "java.sql", JDBC_TYPES)) {
				return Optional.of(ResourceKind.JDBC);
			}
			if ("getConnection".equals(call.getNameAsString())
					&& TypeLookup.isKnownType(context.compilationUnit(), receiver, "javax.sql", Set.of("DataSource"))) {
				return Optional.of(ResourceKind.JDBC);
			}
		}
		if (call.getScope().isPresent()
				&& Set.of("openSession", "openStatelessSession").contains(call.getNameAsString())) {
			String receiver = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
				.orElse("");
			if (TypeLookup.isKnownType(context.compilationUnit(), receiver, "org.hibernate", HIBERNATE_FACTORY_TYPES)) {
				return Optional.of(ResourceKind.HIBERNATE);
			}
		}
		if (call.getScope().isPresent() && Set.of("list", "listBindings", "search").contains(call.getNameAsString())) {
			String receiver = TypeLookup.visibleType(context.compilationUnit(), call.getScope().orElseThrow(), call)
				.orElse("");
			if (TypeLookup.isKnownType(context.compilationUnit(), receiver, "javax.naming", JNDI_CONTEXT_TYPES)
					|| TypeLookup.isKnownType(context.compilationUnit(), receiver, "javax.naming.directory",
							JNDI_DIRECTORY_TYPES)
					|| TypeLookup.isKnownType(context.compilationUnit(), receiver, "javax.naming.ldap", JNDI_LDAP_TYPES)
					|| use instanceof VariableDeclarator variable && TypeLookup.isKnownType(context.compilationUnit(),
							variable.getType().asString(), "javax.naming", JNDI_ENUMERATION_TYPES)) {
				return Optional.of(ResourceKind.JNDI);
			}
		}
		if ("getResourceAsStream".equals(call.getNameAsString()) && (call.getScope()
			.filter(scope -> scope.isMethodCallExpr() && "getClass".equals(scope.asMethodCallExpr().getNameAsString()))
			.isPresent()
				|| call.getScope()
					.filter(scope -> TypeLookup.visibleType(context.compilationUnit(), scope, call)
						.filter(type -> TypeLookup.isKnownJavaLangType(context.compilationUnit(), type,
								Set.of("Class", "ClassLoader")))
						.isPresent())
					.isPresent())) {
			return Optional.of(ResourceKind.IO);
		}
		return Optional.empty();
	}

	private static Optional<ResourceKind> directCreationKind(InspectionContext context, String spelling) {
		if (TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.io", DIRECT_IO_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.util.zip", Set.of("ZipFile"))
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.util.jar", Set.of("JarFile"))) {
			return Optional.of(ResourceKind.IO);
		}
		if (TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.net", SOCKET_TYPES)) {
			return Optional.of(ResourceKind.SOCKET);
		}
		if (TypeLookup.isKnownType(context.compilationUnit(), spelling, "javax.naming", Set.of("InitialContext"))
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "javax.naming.directory",
						Set.of("InitialDirContext"))
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "javax.naming.ldap",
						Set.of("InitialLdapContext"))) {
			return Optional.of(ResourceKind.JNDI);
		}
		return Optional.empty();
	}

	private static Optional<ResourceKind> wrapperKind(InspectionContext context, String spelling) {
		if (TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.io", WRAPPER_IO_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.util.zip", ZIP_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.util.jar", JAR_TYPES)) {
			return Optional.of(ResourceKind.IO);
		}
		return Optional.empty();
	}

	private static boolean knownResourceDeclaration(InspectionContext context, VariableDeclarator variable) {
		String spelling = variable.getType().asString();
		return TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.io", DIRECT_IO_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.io", WRAPPER_IO_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.net", SOCKET_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.nio.channels", CHANNEL_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "java.sql", JDBC_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "org.hibernate",
						HIBERNATE_RESOURCE_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "javax.naming", JNDI_CONTEXT_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "javax.naming.directory",
						JNDI_DIRECTORY_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "javax.naming.ldap", JNDI_LDAP_TYPES)
				|| TypeLookup.isKnownType(context.compilationUnit(), spelling, "javax.naming", JNDI_ENUMERATION_TYPES)
				|| localAutoCloseable(context, spelling);
	}

	private static boolean localAutoCloseable(InspectionContext context, String spelling) {
		String simple = TypeLookup.simpleName(spelling);
		return context.compilationUnit()
			.findAll(ClassOrInterfaceDeclaration.class)
			.stream()
			.filter(type -> type.getNameAsString().equals(simple))
			.anyMatch(type -> type.getImplementedTypes()
				.stream()
				.anyMatch(implemented -> TypeLookup.isKnownJavaLangType(context.compilationUnit(),
						implemented.asString(), Set.of("AutoCloseable"))
						|| TypeLookup.isKnownType(context.compilationUnit(), implemented.asString(), "java.io",
								Set.of("Closeable"))));
	}

	private static boolean staticOwner(InspectionContext context, MethodCallExpr call, String packageName,
			String owner) {
		return call.getScope()
			.filter(scope -> TypeLookup.isKnownType(context.compilationUnit(), scope.toString(), packageName,
					Set.of(owner)))
			.isPresent();
	}

	private static boolean isTryResource(VariableDeclarator variable) {
		return variable.findAncestor(TryStmt.class)
			.filter(statement -> statement.getResources()
				.stream()
				.anyMatch(resource -> resource.isAncestorOf(variable)))
			.isPresent();
	}

	private static boolean managedOrTransferred(VariableDeclarator variable) {
		Node owner = owner(variable);
		String name = variable.getNameAsString();
		for (NameExpr use : owner.findAll(NameExpr.class)) {
			if (!use.getNameAsString().equals(name) || !after(variable, use)) {
				continue;
			}
			if (use.findAncestor(TryStmt.class)
				.filter(statement -> statement.getResources()
					.stream()
					.anyMatch(resource -> resource == use || resource.isAncestorOf(use)))
				.isPresent()) {
				return true;
			}
			MethodCallExpr call = use.findAncestor(MethodCallExpr.class).orElse(null);
			if (call != null && call.getScope().filter(scope -> scope == use).isPresent()
					&& "close".equals(call.getNameAsString()) && call.getArguments().isEmpty()) {
				return true;
			}
			if (use.findAncestor(ReturnStmt.class)
				.filter(returned -> returned.getExpression()
					.filter(expression -> expression == use || expression.isAncestorOf(use))
					.isPresent())
				.isPresent()) {
				return true;
			}
			if (call != null && call.getArguments()
				.stream()
				.anyMatch(argument -> argument == use || argument.isAncestorOf(use))) {
				return true;
			}
			AssignExpr assignment = use.findAncestor(AssignExpr.class).orElse(null);
			if (assignment != null && assignment.getValue().isAncestorOf(use) && !methodReceiver(use)) {
				return true;
			}
			VariableDeclarator assigned = use.findAncestor(VariableDeclarator.class).orElse(null);
			if (assigned != null && assigned != variable && !methodReceiver(use)) {
				return true;
			}
			if (use.findAncestor(CallableDeclaration.class)
				.orElse(null) != variable.findAncestor(CallableDeclaration.class).orElse(null)) {
				return true;
			}
		}
		return false;
	}

	private static boolean methodReceiver(NameExpr use) {
		return use.findAncestor(MethodCallExpr.class)
			.flatMap(MethodCallExpr::getScope)
			.filter(scope -> scope == use || scope.isAncestorOf(use))
			.isPresent();
	}

	private static Node owner(VariableDeclarator variable) {
		return variable.findAncestor(CallableDeclaration.class)
			.map(Node.class::cast)
			.or(() -> variable.findAncestor(InitializerDeclaration.class).map(Node.class::cast))
			.orElseGet(() -> variable.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class)
				.map(Node.class::cast)
				.orElse(variable));
	}

	private static Optional<VariableDeclarator> visibleLocal(InspectionContext context, String name, Node use) {
		return context.compilationUnit()
			.findAll(VariableDeclarator.class)
			.stream()
			.filter(variable -> variable.getNameAsString().equals(name))
			.filter(variable -> variable.findAncestor(FieldDeclaration.class).isEmpty())
			.filter(variable -> after(variable, use))
			.filter(variable -> variable.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class)
				.filter(block -> block.isAncestorOf(use))
				.isPresent())
			.max(Comparator.comparingInt(variable -> variable.getBegin()
				.map(position -> position.line * 100_000 + position.column)
				.orElse(0)));
	}

	private static boolean after(Node first, Node second) {
		Position left = first.getBegin().orElse(Position.HOME);
		Position right = second.getBegin().orElse(Position.HOME);
		return left.line < right.line || left.line == right.line && left.column < right.column;
	}

	private enum ResourceKind {

		IO, CHANNEL, SOCKET, JDBC, HIBERNATE, JNDI, AUTO_CLOSEABLE

	}

}
