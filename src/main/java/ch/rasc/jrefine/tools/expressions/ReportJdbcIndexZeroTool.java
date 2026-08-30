package ch.rasc.jrefine.tools.expressions;

import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.List;
import java.util.Set;

/** Reports zero-based column or parameter indexes passed to known JDBC interfaces. */
public final class ReportJdbcIndexZeroTool implements InspectionTool {

	private static final Set<String> COLUMN_GETTERS = Set.of("getArray", "getAsciiStream", "getBigDecimal",
			"getBinaryStream", "getBlob", "getBoolean", "getByte", "getBytes", "getCharacterStream", "getClob",
			"getDate", "getDouble", "getFloat", "getInt", "getLong", "getNCharacterStream", "getNClob", "getNString",
			"getObject", "getRef", "getRowId", "getShort", "getSQLXML", "getString", "getTime", "getTimestamp",
			"getUnicodeStream", "getURL");

	private static final Set<String> PARAMETER_SETTERS = Set.of("setArray", "setAsciiStream", "setBigDecimal",
			"setBinaryStream", "setBlob", "setBoolean", "setByte", "setBytes", "setCharacterStream", "setClob",
			"setDate", "setDouble", "setFloat", "setInt", "setLong", "setNCharacterStream", "setNClob", "setNString",
			"setNull", "setObject", "setRef", "setRowId", "setShort", "setSQLXML", "setString", "setTime",
			"setTimestamp", "setUnicodeStream", "setURL");

	@Override
	public String id() {
		return "report-jdbc-index-zero";
	}

	@Override
	public String description() {
		return "Report JDBC column and parameter index 0";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<Finding> findings = context.compilationUnit()
			.findAll(MethodCallExpr.class)
			.stream()
			.filter(call -> indexZero(context, call))
			.map(call -> Finding.at(call.getArgument(0), "JDBC indexes start at 1; index 0 is invalid"))
			.toList();
		return new ToolResult(List.copyOf(findings), false);
	}

	private static boolean indexZero(InspectionContext context, MethodCallExpr call) {
		if (call.getScope().isEmpty() || call.getArguments().isEmpty()
				|| !(call.getArgument(0) instanceof IntegerLiteralExpr index) || index.asNumber().intValue() != 0) {
			return false;
		}
		String type = ExpressionToolSupport.visibleSimpleType(context, call.getScope().orElseThrow(), call).orElse("");
		if (!ExpressionToolSupport.knownType(context.compilationUnit(), type, "java.sql",
				Set.of("ResultSet", "PreparedStatement", "CallableStatement"))) {
			return false;
		}
		if ("ResultSet".equals(type)) {
			return COLUMN_GETTERS.contains(call.getNameAsString()) || call.getNameAsString().startsWith("update");
		}
		if (PARAMETER_SETTERS.contains(call.getNameAsString())) {
			return true;
		}
		return "CallableStatement".equals(type) && (COLUMN_GETTERS.contains(call.getNameAsString())
				|| call.getNameAsString().startsWith("registerOutParameter"));
	}

}
