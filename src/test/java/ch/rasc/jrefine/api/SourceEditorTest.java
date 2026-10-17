package ch.rasc.jrefine.api;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import static ch.rasc.jrefine.analysis.LineEndingSupport.CARRIAGE_RETURN_LINE_FEED;
import static ch.rasc.jrefine.analysis.LineEndingSupport.CARRIAGE_RETURN;
import static ch.rasc.jrefine.analysis.LineEndingSupport.LINE_FEED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceEditorTest {

	@Test
	void appliesEditsWithoutChangingLineEndingsOrSurroundingText() {
		SourceEditor editor = new SourceEditor(String.join(CARRIAGE_RETURN_LINE_FEED, "one", "two", "three", ""));
		editor.replace(new Range(new Position(2, 1), new Position(2, 3)), "TWO");

		assertEquals(String.join(CARRIAGE_RETURN_LINE_FEED, "one", "TWO", "three", ""), editor.render());
	}

	@Test
	void addressesBareCarriageReturnAndMixedLineEndingsLikeJavaParser() {
		String source = "one\rtwo\r\nthree\nfour";
		SourceEditor editor = new SourceEditor(source);
		editor.replace(new Range(new Position(2, 1), new Position(2, 3)), "TWO");
		editor.replace(new Range(new Position(4, 1), new Position(4, 4)), "FOUR");

		assertEquals("one" + CARRIAGE_RETURN + "TWO" + CARRIAGE_RETURN_LINE_FEED + "three" + LINE_FEED + "FOUR",
				editor.render());
	}

	@Test
	void appliesSeveralEditsAgainstOriginalPositions() {
		SourceEditor editor = new SourceEditor("alpha beta gamma");
		editor.replace(new Range(new Position(1, 1), new Position(1, 5)), "A");
		editor.replace(new Range(new Position(1, 12), new Position(1, 16)), "G");

		assertEquals("A beta G", editor.render());
	}

	@Test
	void rejectsOverlappingEdits() {
		SourceEditor editor = new SourceEditor("abcdef");
		editor.replace(new Range(new Position(1, 2), new Position(1, 4)), "x");
		editor.replace(new Range(new Position(1, 4), new Position(1, 5)), "y");

		assertThrows(IllegalStateException.class, editor::render);
	}

	@Test
	void rejectsColumnsThatCrossIntoAnotherLine() {
		SourceEditor editor = new SourceEditor("abc\ndef");
		assertThrows(IllegalArgumentException.class, () -> editor.insert(new Position(1, 5), "x"));
		assertThrows(IllegalArgumentException.class, () -> editor.insert(new Position(2, 0), "x"));
		assertThrows(IllegalArgumentException.class, () -> editor.insert(new Position(2, Integer.MAX_VALUE), "x"));
	}

	@Test
	void onlyInsertionMayAddressTheEndOfTheSource() {
		SourceEditor editor = new SourceEditor("abc");
		Range outside = new Range(new Position(1, 4), new Position(1, 4));
		assertThrows(IllegalArgumentException.class, () -> editor.text(outside));
		assertThrows(IllegalArgumentException.class, () -> editor.removeWithLeadingWhitespace(outside));
		assertThrows(IllegalArgumentException.class, () -> editor.removeWithTrailingWhitespace(outside));
		editor.insert(new Position(1, 4), "d");
		assertEquals("abcd", editor.render());
	}

	@Test
	void composesBoundaryInsertionsInQueueOrder() {
		SourceEditor editor = new SourceEditor("abc");
		editor.replace(new Range(new Position(1, 2), new Position(1, 2)), "B");
		editor.insert(new Position(1, 2), "first");
		editor.insert(new Position(1, 2), "second");
		editor.insert(new Position(1, 3), "after");
		assertEquals("afirstsecondBafterc", editor.render());
	}

	@Test
	void rendersManyLengthChangingEditsAgainstOriginalOffsets() {
		SourceEditor editor = new SourceEditor("ab".repeat(10_000));
		for (int index = 0; index < 10_000; index++) {
			Position position = new Position(1, index * 2 + 1);
			editor.replace(new Range(position, position), "longer");
		}
		assertEquals("longerb".repeat(10_000), editor.render());
	}

	@Test
	void removesAnOtherwiseEmptyNodeLine() {
		String source = String.join(CARRIAGE_RETURN_LINE_FEED, "import java.util.Map;", "", "class Sample {}", "");
		CompilationUnit compilationUnit = StaticJavaParser.parse(source);
		SourceEditor editor = new SourceEditor(source);

		editor.removeLine(compilationUnit.getImport(0));

		assertEquals(CARRIAGE_RETURN_LINE_FEED + "class Sample {}" + CARRIAGE_RETURN_LINE_FEED, editor.render());
	}

	@Test
	void preservesOtherTextOnTheRemovedNodesLine() {
		String source = String.join(LINE_FEED, "import java.util.Map; // explain this import", "class Sample {}", "");
		CompilationUnit compilationUnit = StaticJavaParser.parse(source);
		SourceEditor editor = new SourceEditor(source);

		editor.removeLine(compilationUnit.getImport(0));

		assertEquals(String.join(LINE_FEED, " // explain this import", "class Sample {}", ""), editor.render());
	}

	@Test
	void readsExactNodeTextAndSupportsInsertionOnEitherSide() {
		String source = "class Sample {}" + LINE_FEED;
		CompilationUnit compilationUnit = StaticJavaParser.parse(source);
		TypeDeclaration<?> declaration = compilationUnit.getType(0);
		SourceEditor editor = new SourceEditor(source);

		assertEquals("class Sample {}", editor.text(declaration));
		editor.insert(declaration.getBegin().orElseThrow(), "public ");
		editor.insertAfter(declaration.getEnd().orElseThrow(), ";");

		assertEquals("public class Sample {};" + LINE_FEED, editor.render());
	}

}
