package ch.rasc.jrefine.api;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import ch.rasc.jrefine.analysis.LineEndingSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Collects non-overlapping source edits addressed by JavaParser source ranges.
 *
 * <p>
 * Ranges are converted to offsets in the original source and applied from the end of the
 * file backwards. This keeps untouched formatting byte-for-byte stable.
 * </p>
 */
public final class SourceEditor {

	private final String source;

	private final int[] lineStarts;

	private final List<Edit> edits = new ArrayList<>();

	public SourceEditor(String source) {
		this.source = Objects.requireNonNull(source, "source");
		this.lineStarts = findLineStarts(source);
	}

	/** Returns the unmodified source this editor addresses. */
	public String source() {
		return source;
	}

	/** Returns the exact source text occupied by an inclusive JavaParser range. */
	public String text(Range range) {
		Objects.requireNonNull(range, "range");
		return source.substring(this.offset(range.begin), this.offset(range.end) + 1);
	}

	/** Returns the exact source text occupied by a parsed node. */
	public String text(Node node) {
		Range range = Objects.requireNonNull(node, "node")
			.getRange()
			.orElseThrow(() -> new IllegalArgumentException("Cannot read a node without a source range"));
		return this.text(range);
	}

	/** Inserts text immediately before the character at the supplied source position. */
	public void insert(Position position, String value) {
		Objects.requireNonNull(position, "position");
		Objects.requireNonNull(value, "value");
		int at = this.offset(position);
		this.queue(at, at, value);
	}

	/** Inserts text immediately after the character at the supplied source position. */
	public void insertAfter(Position position, String value) {
		Objects.requireNonNull(position, "position");
		Objects.requireNonNull(value, "value");
		int at = this.offset(position) + 1;
		if (at > source.length()) {
			throw new IllegalArgumentException("Insertion position is outside the source: " + position);
		}
		this.queue(at, at, value);
	}

	/** Replaces the inclusive JavaParser range with the supplied text. */
	public void replace(Range range, String replacement) {
		Objects.requireNonNull(range, "range");
		Objects.requireNonNull(replacement, "replacement");
		int start = this.offset(range.begin);
		int endExclusive = this.offset(range.end) + 1;
		if (endExclusive > source.length()) {
			throw new IllegalArgumentException("Edit range ends outside the source: " + range);
		}
		this.queue(start, endExclusive, replacement);
	}

	/** Removes the exact source range occupied by a parsed node. */
	public void remove(Node node) {
		Range range = Objects.requireNonNull(node, "node")
			.getRange()
			.orElseThrow(() -> new IllegalArgumentException("Cannot edit a node without a source range"));
		this.replace(range, "");
	}

	/** Removes a range together with adjacent horizontal whitespace on its left. */
	public void removeWithLeadingWhitespace(Range range) {
		Objects.requireNonNull(range, "range");
		int start = this.offset(range.begin);
		while (start > 0 && isHorizontalWhitespace(source.charAt(start - 1))) {
			start--;
		}
		this.queue(start, this.offset(range.end) + 1, "");
	}

	/** Removes a range together with adjacent horizontal whitespace on its right. */
	public void removeWithTrailingWhitespace(Range range) {
		Objects.requireNonNull(range, "range");
		int endExclusive = this.offset(range.end) + 1;
		while (endExclusive < source.length() && isHorizontalWhitespace(source.charAt(endExclusive))) {
			endExclusive++;
		}
		this.queue(this.offset(range.begin), endExclusive, "");
	}

	/**
	 * Removes a node and its line ending when no other non-whitespace text occurs on its
	 * line. If the line also contains a comment or another construct, only the node
	 * itself is removed.
	 */
	public void removeLine(Node node) {
		Range range = Objects.requireNonNull(node, "node")
			.getRange()
			.orElseThrow(() -> new IllegalArgumentException("Cannot edit a node without a source range"));
		this.removeLine(range);
	}

	/** Removes a range and its otherwise blank source line. */
	public void removeLine(Range range) {
		Objects.requireNonNull(range, "range");
		int start = this.offset(range.begin);
		int endExclusive = this.offset(range.end) + 1;
		int lineStart = lineStarts[range.begin.line - 1];
		int lineEndExclusive = range.end.line < lineStarts.length ? lineStarts[range.end.line] : source.length();

		if (source.substring(lineStart, start).isBlank()
				&& source.substring(endExclusive, lineEndExclusive).isBlank()) {
			this.queue(lineStart, lineEndExclusive, "");
		}
		else {
			this.queue(start, endExclusive, "");
		}
	}

	public boolean hasEdits() {
		return !edits.isEmpty();
	}

	/** Applies all queued edits and returns the resulting source. */
	public String render() {
		if (edits.isEmpty()) {
			return source;
		}

		List<Edit> ordered = edits.stream().sorted(Comparator.comparingInt(Edit::start)).toList();
		for (int index = 1; index < ordered.size(); index++) {
			Edit previous = ordered.get(index - 1);
			Edit current = ordered.get(index);
			if (current.start() < previous.endExclusive()) {
				throw new IllegalStateException("Inspection tools produced overlapping source edits");
			}
		}

		StringBuilder output = new StringBuilder(source);
		for (int index = ordered.size() - 1; index >= 0; index--) {
			Edit edit = ordered.get(index);
			output.replace(edit.start(), edit.endExclusive(), edit.replacement());
		}
		return output.toString();
	}

	private int offset(Position position) {
		if (position.line < 1 || position.line > lineStarts.length) {
			throw new IllegalArgumentException("Edit line is outside the source: " + position);
		}
		int offset = lineStarts[position.line - 1] + position.column - 1;
		if (offset < 0 || offset > source.length()) {
			throw new IllegalArgumentException("Edit column is outside the source: " + position);
		}
		return offset;
	}

	private void queue(int start, int endExclusive, String replacement) {
		edits.add(new Edit(start, endExclusive, replacement));
	}

	private static boolean isHorizontalWhitespace(char character) {
		return character == ' ' || character == '\t' || character == '\f';
	}

	private static int[] findLineStarts(String source) {
		ArrayList<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int index = 0; index < source.length(); index++) {
			char character = source.charAt(index);
			if (character == LineEndingSupport.CARRIAGE_RETURN_CHAR) {
				if (index + 1 < source.length() && source.charAt(index + 1) == LineEndingSupport.LINE_FEED_CHAR) {
					index++;
				}
				starts.add(index + 1);
			}
			else if (character == LineEndingSupport.LINE_FEED_CHAR) {
				starts.add(index + 1);
			}
		}
		return starts.stream().mapToInt(Integer::intValue).toArray();
	}

	private record Edit(int start, int endExclusive, String replacement) {
	}

}
