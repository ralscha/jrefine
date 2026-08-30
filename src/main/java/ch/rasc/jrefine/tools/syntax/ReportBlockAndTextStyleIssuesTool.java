package ch.rasc.jrefine.tools.syntax;

import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import ch.rasc.jrefine.analysis.LineEndingSupport;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.PolicyInspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.Statement;

/** Reports configurable brace, block, catch, comment, and literal formatting styles. */
public final class ReportBlockAndTextStyleIssuesTool implements PolicyInspectionTool {

	private static final Pattern OCTAL_FOLLOWED_BY_DIGIT = Pattern.compile(".*\\\\[0-7]{1,3}[0-9].*", Pattern.DOTALL);

	@Override
	public String id() {
		return "report-block-text-style-issues";
	}

	@Override
	public String description() {
		return "Report brace, switch-rule, multi-catch, comment, and literal styles";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		ArrayList<Finding> findings = new ArrayList<>();
		braces(context, findings);
		switches(context, findings);
		blocks(context, findings);
		comments(context, findings);
		strings(context, findings);
		return new ToolResult(List.copyOf(findings), false);
	}

	private static void braces(InspectionContext context, List<Finding> findings) {
		for (IfStmt statement : context.compilationUnit().findAll(IfStmt.class)) {
			branchBrace(statement.getThenStmt(), statement, findings);
			statement.getElseStmt().ifPresent(branch -> branchBrace(branch, statement, findings));
		}
		context.compilationUnit()
			.findAll(WhileStmt.class)
			.forEach(statement -> branchBrace(statement.getBody(), statement, findings));
		context.compilationUnit()
			.findAll(DoStmt.class)
			.forEach(statement -> branchBrace(statement.getBody(), statement, findings));
		context.compilationUnit()
			.findAll(ForStmt.class)
			.forEach(statement -> branchBrace(statement.getBody(), statement, findings));
		context.compilationUnit()
			.findAll(ForEachStmt.class)
			.forEach(statement -> branchBrace(statement.getBody(), statement, findings));
	}

	private static void branchBrace(Statement body, Node owner, List<Finding> findings) {
		if (!(body instanceof BlockStmt) && !(body instanceof IfStmt)) {
			findings.add(Finding.at(owner, "Control flow statement has no braces"));
		}
	}

	private static void switches(InspectionContext context, List<Finding> findings) {
		for (SwitchEntry entry : context.compilationUnit().findAll(SwitchEntry.class)) {
			if ("BLOCK".equals(entry.getType().name()) && entry.getStatements().size() == 1
					&& entry.getStatement(0) instanceof BlockStmt block && block.getStatements().size() == 1
					&& (block.getStatement(0) instanceof ExpressionStmt
							|| block.getStatement(0) instanceof ThrowStmt)) {
				findings.add(Finding.at(entry, "Labeled switch rule has a redundant code block"));
			}
		}
	}

	private static void blocks(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.findAll(BlockStmt.class)
			.stream()
			.filter(block -> block.getParentNode().orElse(null) instanceof BlockStmt)
			.forEach(block -> findings.add(Finding.at(block, "Unnecessary code block")));
	}

	private static void comments(InspectionContext context, List<Finding> findings) {
		context.compilationUnit()
			.getAllContainedComments()
			.stream()
			.filter(ReportBlockAndTextStyleIssuesTool::blockMarker)
			.forEach(comment -> findings.add(Finding.at(comment, "Block marker comment")));
	}

	private static boolean blockMarker(Comment comment) {
		String content = comment.getContent().strip().toLowerCase(Locale.ROOT);
		return content.matches("(end|begin)?\\s*(if|else|for|while|switch|try|catch|finally|region).*");
	}

	private static void strings(InspectionContext context, List<Finding> findings) {
		for (StringLiteralExpr literal : context.compilationUnit().findAll(StringLiteralExpr.class)) {
			String source = context.editor().text(literal);
			if (OCTAL_FOLLOWED_BY_DIGIT.matcher(source).matches()) {
				findings.add(Finding.at(literal, "Confusing octal escape sequence followed by a digit"));
			}
		}
		for (TextBlockLiteralExpr literal : context.compilationUnit().findAll(TextBlockLiteralExpr.class)) {
			String source = LineEndingSupport.normalize(context.editor().text(literal));
			String[] lines = source.split(LineEndingSupport.LINE_FEED, -1);
			for (int index = 1; index + 1 < lines.length; index++) {
				if (lines[index].matches(".*[ \\t]+$")) {
					findings.add(Finding.at(literal, "Trailing whitespace in text block"));
					break;
				}
			}
		}
	}

}
