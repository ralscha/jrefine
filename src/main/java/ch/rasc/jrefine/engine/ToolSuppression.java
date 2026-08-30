package ch.rasc.jrefine.engine;

import com.github.javaparser.ast.CompilationUnit;

import java.util.Arrays;

/**
 * Source-level, whole-file suppression for tools that must also be safe in apply mode.
 */
final class ToolSuppression {

	private static final String DIRECTIVE = "jrefine-ignore-file";

	private ToolSuppression() {
	}

	static boolean isSuppressed(CompilationUnit unit, String toolId) {
		return unit.getAllContainedComments().stream().anyMatch(comment -> {
			String content = comment.getContent().strip();
			int directive = content.indexOf(DIRECTIVE);
			if (directive < 0) {
				return false;
			}
			String values = content.substring(directive + DIRECTIVE.length()).strip();
			return Arrays.stream(values.split("[,\\s]+"))
				.anyMatch(value -> value.equals(toolId) || value.equals("all") || value.equals("*"));
		});
	}

}
