package ch.rasc.jrefine.engine;

import com.github.javaparser.ast.CompilationUnit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level, whole-file suppression for tools that must also be safe in apply mode.
 */
final class ToolSuppression {

	private static final Pattern DIRECTIVE = Pattern.compile("(?m)(?<![\\w-])jrefine-ignore-file[\\t ]+([^\\r\\n]*)");

	private ToolSuppression() {
	}

	static boolean isSuppressed(CompilationUnit unit, String toolId) {
		return isSuppressed(suppressedTools(unit), toolId);
	}

	static Set<String> suppressedTools(CompilationUnit unit) {
		Set<String> suppressed = new HashSet<>();
		unit.getAllContainedComments().forEach(comment -> {
			Matcher matcher = DIRECTIVE.matcher(comment.getContent());
			while (matcher.find()) {
				Arrays.stream(matcher.group(1).strip().split("[,\\s]+"))
					.filter(value -> !value.isEmpty())
					.forEach(suppressed::add);
			}
		});
		return Set.copyOf(suppressed);
	}

	static boolean isSuppressed(Set<String> suppressed, String toolId) {
		return suppressed.contains(toolId) || suppressed.contains("all") || suppressed.contains("*");
	}

}
