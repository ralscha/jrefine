package ch.rasc.jrefine.analysis;

import ch.rasc.jrefine.TestSources;
import com.github.javaparser.ast.expr.NameExpr;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeLookupTest {

	@Test
	void preservesArrayDimensionsWhenErasingGenericTypes() {
		var unit = TestSources.parse("""
				import java.util.List;
				class Sample {
				    Object value(List<List<String>>[][] values) { return values; }
				}
				""").compilationUnit();
		NameExpr use = unit.findFirst(NameExpr.class).orElseThrow();
		assertEquals("List[][]", TypeLookup.visibleTypePreservingArrays(unit, use, use).orElseThrow());
	}

	@Test
	void requiresTheExactJavaUtilPackage() {
		var unit = TestSources.parse("import java.util.concurrent.*; class Sample {}").compilationUnit();
		assertFalse(TypeLookup.isKnownJavaUtilType(unit, "List", Set.of("List")));
		assertFalse(TypeLookup.isKnownJavaUtilType(unit, "java.util.custom.List", Set.of("List")));
		assertTrue(TypeLookup.isKnownJavaUtilType(unit, "java.util.List", Set.of("List")));
	}

	@Test
	void resolvesSupportedSubpackageTypesOnlyInTheirOwnPackage() {
		var unit = TestSources.parse("""
				import java.util.concurrent.*;
				import java.util.function.Function;
				import java.util.stream.Stream;
				class Sample {}
				""").compilationUnit();
		assertTrue(TypeLookup.isKnownJavaUtilType(unit, "ConcurrentHashMap", Set.of("ConcurrentHashMap")));
		assertTrue(TypeLookup.isKnownJavaUtilType(unit, "CopyOnWriteArrayList", Set.of("CopyOnWriteArrayList")));
		assertTrue(TypeLookup.isKnownJavaUtilType(unit, "Function", Set.of("Function")));
		assertTrue(TypeLookup.isKnownJavaUtilType(unit, "Stream", Set.of("Stream")));
		assertFalse(TypeLookup.isKnownJavaUtilType(unit, "java.util.Function", Set.of("Function")));
	}

}
