package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolRegistryTest {

	@Test
	void discoversBuiltInToolsThroughServiceLoader() {
		List<String> ids = ToolRegistry.load().all().stream().map(InspectionTool::id).toList();

		assertEquals(List.of("add-override-annotation", "add-serial-annotation", "collapse-loop-to-stream",
				"fix-javadoc-paragraphs", "fold-expression-into-stream", "inline-only-used-element",
				"join-declaration-and-assignment", "merge-duplicate-switch-branches", "merge-identical-catch-branches",
				"modernize-bigdecimal", "narrow-variable-scope", "narrow-variable-type", "normalize-array-declarations",
				"normalize-comparisons", "optimize-performance-expressions", "promote-integer-operation-to-long",
				"qualify-static-member-access", "remove-boxing-of-boxed-value", "remove-empty-initializers",
				"remove-empty-string-concatenation", "remove-invalid-serial-annotation", "remove-mapping-before-count",
				"remove-no-effect-string-replacement", "remove-redundant-array-creation",
				"remove-redundant-array-length-check", "remove-redundant-compare-call",
				"remove-redundant-declaration-elements", "remove-redundant-field-initialization",
				"remove-redundant-file-creation", "remove-redundant-interfaces",
				"remove-redundant-lambda-parameter-types", "remove-redundant-local-variable",
				"remove-redundant-method-override", "remove-redundant-no-arg-constructor",
				"remove-redundant-object-bounds", "remove-redundant-record-constructor",
				"remove-redundant-regex-replacement-escape", "remove-redundant-stream-optional-step",
				"remove-redundant-throws", "remove-redundant-type-arguments", "remove-redundant-type-cast",
				"remove-redundant-unmodifiable-wrapper", "remove-self-assignment", "remove-unnecessary-boxing",
				"remove-unnecessary-break", "remove-unnecessary-continue", "remove-unnecessary-enum-switch-default",
				"remove-unnecessary-final", "remove-unnecessary-modifiers", "remove-unnecessary-numeric-cast",
				"remove-unnecessary-parentheses", "remove-unnecessary-return", "remove-unnecessary-semicolons",
				"remove-unnecessary-string-escape", "remove-unnecessary-super-call", "remove-unnecessary-to-string",
				"remove-unnecessary-unboxing", "remove-unreachable-catch", "remove-unused-assignments",
				"remove-unused-imports", "replace-bigdecimal-legacy-rounding", "replace-cast-with-variable",
				"replace-guava-functional-primitives", "replace-number-constructor", "replace-redundant-class-call",
				"replace-string-builder-with-string", "replace-wildcard-imports", "report-abstraction-design-issues",
				"report-api-misuse-bugs", "report-api-naming-conflicts", "report-assertion-control-flow-bugs",
				"report-assignment-issues", "report-async-correctness-issues", "report-bitwise-operation-issues",
				"report-block-text-style-issues", "report-class-structure-issues", "report-cloning-issues",
				"report-code-maturity-issues", "report-collection-array-bugs", "report-collection-performance",
				"report-complex-arithmetic-expression", "report-concurrency-api-bugs",
				"report-concurrency-contract-bugs", "report-conditional-flow-issues", "report-constant-parameter",
				"report-control-flow-structure-issues", "report-declaration-contract-bugs",
				"report-declaration-style-issues", "report-duplicate-code", "report-embedded-resource-performance",
				"report-encapsulation-policy-issues", "report-equality-contract-bugs",
				"report-exception-contract-issues", "report-exception-flow-bugs", "report-expression-style-issues",
				"report-field-initialization-contract-issues", "report-floating-point-issues",
				"report-format-string-bugs", "report-functional-expression-redundancy", "report-guarded-state-issues",
				"report-inheritance-design-issues", "report-initialization-bugs", "report-injection-risks",
				"report-internationalization-policy-issues", "report-javabeans-contract-bugs",
				"report-javabeans-policy-issues", "report-javadoc-contract-issues", "report-javadoc-reference-issues",
				"report-jdbc-index-zero", "report-locale-sensitive-code", "report-logging-issues",
				"report-lombok-accessor", "report-lombok-contract-issues", "report-lossy-numeric-cast",
				"report-memory-issues", "report-module-contract-issues", "report-mutable-state-exposure",
				"report-name-shadowing-issues", "report-nullability-bugs", "report-numeric-conversion-issues",
				"report-numeric-literal-issues", "report-numeric-overflow", "report-portability-issues",
				"report-raw-parameterized-types", "report-reflection-contract-bugs",
				"report-resource-lifecycle-policy-issues", "report-resource-management-bugs",
				"report-security-hardening-issues", "report-security-sensitive-code",
				"report-serialization-contract-bugs", "report-serialization-state-bugs", "report-state-usage-bugs",
				"report-stream-lambda-performance", "report-string-performance",
				"report-synchronization-consistency-issues", "report-test-assertion-bugs",
				"report-testng-contract-bugs", "report-thread-coordination-issues", "report-threading-policy-issues",
				"report-throwable-construction-issues", "report-write-only-object", "simplify-annotations",
				"simplify-array-initializers", "simplify-boolean-expression", "simplify-collector",
				"simplify-comparator-method", "simplify-covered-conditions", "simplify-excessive-lambda",
				"simplify-for-each", "simplify-labels", "simplify-map-operations", "simplify-numeric-expressions",
				"simplify-obvious-null-check", "simplify-optional-call-chain", "simplify-pointless-bitwise-expressions",
				"simplify-range-check", "simplify-redundant-collection-operation",
				"simplify-redundant-java-time-operation", "simplify-redundant-string-operation",
				"simplify-stream-call-chain", "simplify-string-format", "sort-modifiers", "use-array-fill",
				"use-bulk-file-attributes", "use-bulk-operation", "use-clamp", "use-collection-copy-constructor",
				"use-collection-factory", "use-comparator-combinators", "use-diamond-operator", "use-enhanced-for",
				"use-enhanced-for-while", "use-enhanced-switch", "use-expression-lambda", "use-files-string-methods",
				"use-float-literal", "use-instanceof-patterns", "use-is-empty", "use-iterator-for-enumeration",
				"use-known-constant", "use-lambda-for-anonymous", "use-list-replace-all", "use-list-sort",
				"use-long-literal", "use-map-for-each", "use-math-min-max", "use-method-call-for-lambda",
				"use-method-reference", "use-method-reference-for-anonymous", "use-null-fallback-method",
				"use-numeric-compare", "use-objects-equals", "use-operator-assignment", "use-pattern-variable",
				"use-record-pattern", "use-remove-if", "use-sequenced-collection-methods",
				"use-shorter-lambda-alternative", "use-standard-charset", "use-standard-hash-code",
				"use-stream-for-guava-call", "use-string-builder", "use-string-contains", "use-string-repeat",
				"use-string-replace", "use-switch-expression", "use-switch-for-if", "use-text-block",
				"use-try-with-resources", "use-varargs-parameter"), ids);
	}

	@Test
	void rejectsDuplicateToolIds() {
		StubTool tool = new StubTool("same-id");

		assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(tool, tool)));
	}

	@Test
	void rejectsUnknownSelections() {
		ToolRegistry registry = new ToolRegistry(List.of(new StubTool("known")));

		assertThrows(IllegalArgumentException.class, () -> registry.select(List.of("missing")));
	}

	@Test
	void separatesHighConfidenceAndPolicyProfiles() {
		ToolRegistry registry = ToolRegistry.load();
		List<String> highConfidence = registry.select(List.of(), ToolProfile.HIGH_CONFIDENCE)
			.stream()
			.map(InspectionTool::id)
			.toList();
		List<String> policy = registry.select(List.of(), ToolProfile.POLICY).stream().map(InspectionTool::id).toList();

		assertTrue(highConfidence.contains("remove-unused-imports"));
		assertTrue(highConfidence.contains("report-reflection-contract-bugs"));
		assertTrue(highConfidence.contains("report-lombok-contract-issues"));
		assertTrue(highConfidence.contains("report-testng-contract-bugs"));
		assertTrue(highConfidence.contains("report-concurrency-contract-bugs"));
		assertTrue(highConfidence.contains("report-javadoc-contract-issues"));
		assertTrue(highConfidence.contains("report-javadoc-reference-issues"));
		assertTrue(highConfidence.contains("report-field-initialization-contract-issues"));
		assertTrue(highConfidence.contains("qualify-static-member-access"));
		assertTrue(highConfidence.contains("report-module-contract-issues"));
		assertTrue(highConfidence.contains("report-serialization-state-bugs"));
		assertTrue(highConfidence.contains("report-throwable-construction-issues"));
		assertTrue(highConfidence.contains("report-raw-parameterized-types"));
		assertTrue(highConfidence.contains("remove-redundant-declaration-elements"));
		assertFalse(highConfidence.contains("report-numeric-literal-issues"));
		assertFalse(highConfidence.contains("report-inheritance-design-issues"));
		assertFalse(highConfidence.contains("report-api-naming-conflicts"));
		assertFalse(highConfidence.contains("report-mutable-state-exposure"));
		assertFalse(highConfidence.contains("report-security-sensitive-code"));
		assertFalse(highConfidence.contains("report-security-hardening-issues"));
		assertFalse(highConfidence.contains("report-async-correctness-issues"));
		assertFalse(highConfidence.contains("report-guarded-state-issues"));
		assertFalse(highConfidence.contains("report-synchronization-consistency-issues"));
		assertFalse(highConfidence.contains("report-thread-coordination-issues"));
		assertFalse(highConfidence.contains("report-threading-policy-issues"));
		assertFalse(highConfidence.contains("report-resource-lifecycle-policy-issues"));
		assertFalse(highConfidence.contains("report-internationalization-policy-issues"));
		assertFalse(highConfidence.contains("report-javabeans-policy-issues"));
		assertFalse(highConfidence.contains("report-constant-parameter"));
		assertTrue(policy.contains("report-numeric-literal-issues"));
		assertTrue(policy.contains("report-security-sensitive-code"));
		assertTrue(policy.contains("report-security-hardening-issues"));
		assertTrue(policy.contains("report-async-correctness-issues"));
		assertTrue(policy.contains("report-exception-contract-issues"));
		assertTrue(policy.contains("report-inheritance-design-issues"));
		assertTrue(policy.contains("report-name-shadowing-issues"));
		assertTrue(policy.contains("report-abstraction-design-issues"));
		assertTrue(policy.contains("report-api-naming-conflicts"));
		assertTrue(policy.contains("report-encapsulation-policy-issues"));
		assertTrue(policy.contains("report-mutable-state-exposure"));
		assertTrue(policy.contains("report-guarded-state-issues"));
		assertTrue(policy.contains("report-synchronization-consistency-issues"));
		assertTrue(policy.contains("report-thread-coordination-issues"));
		assertTrue(policy.contains("report-threading-policy-issues"));
		assertTrue(policy.contains("report-resource-lifecycle-policy-issues"));
		assertTrue(policy.contains("report-internationalization-policy-issues"));
		assertTrue(policy.contains("report-javabeans-policy-issues"));
		assertTrue(policy.contains("report-constant-parameter"));
		assertFalse(policy.contains("remove-unused-imports"));
	}

	private record StubTool(String id) implements InspectionTool {
		@Override
		public String description() {
			return "stub";
		}

		@Override
		public ToolResult inspect(InspectionContext context, boolean applyFixes) {
			return new ToolResult(List.of(), false);
		}
	}

}
