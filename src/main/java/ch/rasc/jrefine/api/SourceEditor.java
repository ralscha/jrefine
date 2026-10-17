package ch.rasc.jrefine.api;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import ch.rasc.jrefine.analysis.LineEndingSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Collects non-overlapping source edits addressed by JavaParser source ranges.
 *
 * <p>
 * Ranges are converted to offsets in the original source. Unchanged spans and
 * replacements are appended in source order, keeping untouched formatting byte-for-byte
 * stable.
 * </p>
 */
public final class SourceEditor {

	private final String source;

	private int[] lineStarts;

	private final List<Edit> edits = new ArrayList<>();

	public SourceEditor(String source) {
		this.source = Objects.requireNonNull(source, "source");
	}

	/** Returns the unmodified source this editor addresses. */
	public String source() {
		return source;
	}

	/** Returns the exact source text occupied by an inclusive JavaParser range. */
	public String text(Range range) {
		Objects.requireNonNull(range, "range");
		return source.substring(this.offset(range.begin), this.endOffset(range));
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
		int endExclusive = this.endOffset(range);
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
		this.queue(start, this.endOffset(range), "");
	}

	/** Removes a range together with adjacent horizontal whitespace on its right. */
	public void removeWithTrailingWhitespace(Range range) {
		Objects.requireNonNull(range, "range");
		int endExclusive = this.endOffset(range);
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
		int endExclusive = this.endOffset(range);
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

		List<Edit> ordered = edits.stream()
			.sorted(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::endExclusive))
			.toList();
		for (int index = 1; index < ordered.size(); index++) {
			Edit previous = ordered.get(index - 1);
			Edit current = ordered.get(index);
			if (current.start() < previous.endExclusive()) {
				throw new IllegalStateException("Inspection tools produced overlapping source edits");
			}
		}

		StringBuilder output = new StringBuilder(source.length());
		int offset = 0;
		for (Edit edit : ordered) {
			output.append(source, offset, edit.start());
			output.append(edit.replacement());
			offset = edit.endExclusive();
		}
		output.append(source, offset, source.length());
		return output.toString();
	}

	private int offset(Position position) {
		if (lineStarts == null) {
			lineStarts = findLineStarts(source);
		}
		if (position.line < 1 || position.line > lineStarts.length) {
			throw new IllegalArgumentException("Edit line is outside the source: " + position);
		}
		int lineStart = lineStarts[position.line - 1];
		int lastOffset = position.line < lineStarts.length ? lineStarts[position.line] - 1 : source.length();
		if (position.column < 1 || position.column - 1 > lastOffset - lineStart) {
			throw new IllegalArgumentException("Edit column is outside the source: " + position);
		}
		return lineStart + position.column - 1;
	}

	private int endOffset(Range range) {
		int end = this.offset(range.end);
		if (end == source.length()) {
			throw new IllegalArgumentException("Edit range ends outside the source: " + range);
		}
		return end + 1;
	}

	private void queue(int start, int endExclusive, String replacement) {
		edits.add(new Edit(start, endExclusive, replacement));
	}

	private static boolean isHorizontalWhitespace(char character) {
		return character == ' ' || character == '\t' || character == '\f';
	}

	private static int[] findLineStarts(String source) {
		int[] starts = new int[128];
		int size = 1;
		for (int index = 0; index < source.length(); index++) {
			char character = source.charAt(index);
			if (character == LineEndingSupport.CARRIAGE_RETURN_CHAR) {
				if (index + 1 < source.length() && source.charAt(index + 1) == LineEndingSupport.LINE_FEED_CHAR) {
					index++;
				}
			}
			else if (character != LineEndingSupport.LINE_FEED_CHAR) {
				continue;
			}
			if (size == starts.length) {
				starts = Arrays.copyOf(starts, starts.length * 2);
			}
			starts[size++] = index + 1;
		}
		return Arrays.copyOf(starts, size);
	}

	private record Edit(int start, int endExclusive, String replacement) {
	}

}
