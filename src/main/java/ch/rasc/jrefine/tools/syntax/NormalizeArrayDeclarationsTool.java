package ch.rasc.jrefine.tools.syntax;

import com.github.javaparser.Position;
import java.util.List;
import com.github.javaparser.JavaToken;
import com.github.javaparser.Range;
import com.github.javaparser.ast.type.ArrayType;
import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;

import java.util.ArrayList;

/** Moves C-style array brackets from a name to its type. */
public final class NormalizeArrayDeclarationsTool implements InspectionTool {

	@Override
	public String id() {
		return "normalize-array-declarations";
	}

	@Override
	public String description() {
		return "Move C-style array brackets from variable and method names to their types";
	}

	@Override
	public ToolResult inspect(InspectionContext context, boolean applyFixes) {
		List<ArrayType> candidates = context.compilationUnit()
			.findAll(ArrayType.class)
			.stream()
			.filter(type -> type.getOrigin() == ArrayType.Origin.NAME)
			.toList();
		ArrayList<Finding> findings = new ArrayList<>();
		for (ArrayType type : candidates) {
			findings.add(Finding.at(type, "Move array brackets to the type"));
			if (applyFixes) {
				JavaToken close = lastSignificantToken(type);
				JavaToken open = previousSignificantToken(close);
				if (!"]".equals(close.getText()) || !"[".equals(open.getText())) {
					throw new IllegalStateException("Could not locate C-style array brackets");
				}
				Position bracketStart = type.getAnnotations().isEmpty() ? open.getRange().orElseThrow().begin
						: type.getAnnotations().get(0).getRange().orElseThrow().begin;
				String bracketText = context.editor().text(new Range(bracketStart, close.getRange().orElseThrow().end));
				context.editor().replace(new Range(bracketStart, close.getRange().orElseThrow().end), "");
				context.editor().insertAfter(type.getElementType().getRange().orElseThrow().end, bracketText);
				type.setOrigin(ArrayType.Origin.TYPE);
			}
		}
		return ToolResult.of(findings, applyFixes);
	}

	private static JavaToken lastSignificantToken(ArrayType type) {
		JavaToken token = type.getTokenRange().orElseThrow().getEnd();
		while (token.getText().isBlank()) {
			token = token.getPreviousToken().orElseThrow();
		}
		return token;
	}

	private static JavaToken previousSignificantToken(JavaToken token) {
		JavaToken currentToken = token;
		do {
			currentToken = currentToken.getPreviousToken().orElseThrow();
		}
		while (currentToken.getText().isBlank());
		return currentToken;
	}

}
