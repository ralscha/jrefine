# JRefine

JRefine is an extensible command-line source inspector and cleanup tool built on
[JavaParser](https://github.com/javaparser/javaparser). It scans a Java file or directory,
reports modernization opportunities, and can apply safe, range-based fixes while preserving the
surrounding source layout.

The 210 built-in tools are grouped by responsibility:

- Declarations: `add-override-annotation`, `add-serial-annotation`, `join-declaration-and-assignment`, `narrow-variable-scope`, `remove-empty-initializers`,
  `remove-redundant-field-initialization`, `remove-redundant-no-arg-constructor`,
  `remove-redundant-interfaces`, `remove-redundant-lambda-parameter-types`, `remove-redundant-local-variable`,
  `remove-redundant-declaration-elements`, `remove-redundant-method-override`, `remove-redundant-object-bounds`,
  `remove-redundant-record-constructor`,
  `remove-redundant-throws`, `remove-self-assignment`,
  `remove-invalid-serial-annotation`, `remove-unnecessary-final`, `remove-unnecessary-modifiers`, `remove-unnecessary-super-call`,
  `remove-unused-assignments`, `remove-unused-imports`, `replace-wildcard-imports`,
  `report-abstraction-design-issues`, `report-api-naming-conflicts`, `report-class-structure-issues`, `report-cloning-issues`,
  `report-code-maturity-issues`, `report-constant-parameter`, `report-declaration-contract-bugs`, `report-declaration-style-issues`,
  `report-duplicate-code`, `report-encapsulation-policy-issues`, `report-field-initialization-contract-issues`, `report-lombok-accessor`,
  `report-lombok-contract-issues`,
  `report-inheritance-design-issues`, `report-initialization-bugs`, `report-javabeans-contract-bugs`,
  `report-javabeans-policy-issues`,
  `report-javadoc-contract-issues`, `report-javadoc-reference-issues`,
  `report-memory-issues`, `report-module-contract-issues`, `report-mutable-state-exposure`, `report-name-shadowing-issues`, `report-nullability-bugs`,
  `report-raw-parameterized-types`,
  `report-security-hardening-issues`, `report-security-sensitive-code`, `report-serialization-contract-bugs`, `report-serialization-state-bugs`,
  `report-state-usage-bugs`, `use-varargs-parameter`
- Control flow: `collapse-loop-to-stream`, `merge-duplicate-switch-branches`, `merge-identical-catch-branches`,
  `remove-redundant-array-length-check`, `remove-unnecessary-break`, `remove-unnecessary-continue`,
  `remove-unnecessary-enum-switch-default`, `remove-unnecessary-return`, `remove-unreachable-catch`,
  `report-assertion-control-flow-bugs`, `report-async-correctness-issues`, `report-concurrency-api-bugs`, `report-concurrency-contract-bugs`, `report-conditional-flow-issues`,
  `report-control-flow-structure-issues`, `report-embedded-resource-performance`,
  `report-exception-contract-issues`, `report-exception-flow-bugs`, `report-guarded-state-issues`,
  `report-resource-lifecycle-policy-issues`, `report-resource-management-bugs`,
  `report-synchronization-consistency-issues`, `report-test-assertion-bugs`, `report-testng-contract-bugs`,
  `report-thread-coordination-issues`, `report-threading-policy-issues`,
  `simplify-labels`, `use-array-fill`, `use-bulk-operation`, `use-enhanced-for`,
  `use-enhanced-for-while`, `use-enhanced-switch`, `use-iterator-for-enumeration`, `use-list-replace-all`,
  `use-map-for-each`, `use-remove-if`, `use-string-repeat`, `use-switch-expression`, `use-switch-for-if`,
  `use-try-with-resources`
- Expressions: `fold-expression-into-stream`, `inline-only-used-element`, `modernize-bigdecimal`, `narrow-variable-type`, `normalize-comparisons`,
  `optimize-performance-expressions`,
  `promote-integer-operation-to-long`, `qualify-static-member-access`, `remove-boxing-of-boxed-value`, `remove-empty-string-concatenation`,
  `remove-mapping-before-count`, `remove-no-effect-string-replacement`,
  `remove-redundant-array-creation`, `remove-redundant-compare-call`, `remove-redundant-file-creation`,
  `remove-redundant-regex-replacement-escape`, `remove-redundant-stream-optional-step`,
  `remove-redundant-type-arguments`, `remove-redundant-type-cast`, `remove-redundant-unmodifiable-wrapper`,
  `remove-unnecessary-boxing`, `remove-unnecessary-numeric-cast`, `remove-unnecessary-string-escape`, `remove-unnecessary-to-string`,
  `remove-unnecessary-unboxing`, `replace-bigdecimal-legacy-rounding`, `replace-cast-with-variable`, `replace-number-constructor`,
  `replace-guava-functional-primitives`, `replace-redundant-class-call`, `report-jdbc-index-zero`,
  `report-api-misuse-bugs`, `report-assignment-issues`, `report-bitwise-operation-issues`,
  `report-collection-array-bugs`, `report-expression-style-issues`,
  `report-collection-performance`, `report-complex-arithmetic-expression`, `report-equality-contract-bugs`,
  `report-floating-point-issues`, `report-format-string-bugs`, `report-functional-expression-redundancy`, `report-injection-risks`,
  `report-internationalization-policy-issues`, `report-locale-sensitive-code`, `report-logging-issues`,
  `report-lossy-numeric-cast`,
  `report-numeric-conversion-issues`, `report-numeric-overflow`, `report-write-only-object`,
  `report-reflection-contract-bugs`,
  `report-stream-lambda-performance`, `report-string-performance`, `report-throwable-construction-issues`,
  `simplify-boolean-expression`, `simplify-collector`, `simplify-comparator-method`, `simplify-covered-conditions`,
  `simplify-excessive-lambda`, `simplify-for-each`, `simplify-map-operations`, `simplify-numeric-expressions`,
  `simplify-obvious-null-check`, `simplify-optional-call-chain`, `simplify-pointless-bitwise-expressions`,
  `simplify-range-check`,
  `simplify-redundant-collection-operation`, `simplify-redundant-java-time-operation`,
  `simplify-redundant-string-operation`, `simplify-stream-call-chain`, `simplify-string-format`,
  `use-bulk-file-attributes`, `use-clamp`,
  `use-collection-copy-constructor`, `use-collection-factory`, `use-comparator-combinators`, `use-expression-lambda`,
  `use-files-string-methods`, `use-float-literal`, `use-instanceof-patterns`, `use-is-empty`,
  `use-known-constant`, `use-lambda-for-anonymous`, `use-list-sort`, `use-long-literal`, `use-math-min-max`,
  `use-method-call-for-lambda`,
  `use-method-reference`, `use-method-reference-for-anonymous`, `use-null-fallback-method`, `use-numeric-compare`, `use-objects-equals`,
  `use-operator-assignment`, `use-pattern-variable`, `use-record-pattern`, `use-sequenced-collection-methods`,
  `use-shorter-lambda-alternative`, `use-standard-charset`, `use-standard-hash-code`,
  `use-stream-for-guava-call`, `use-string-contains`, `use-string-replace`
- Syntax: `fix-javadoc-paragraphs`, `normalize-array-declarations`,
  `report-block-text-style-issues`, `report-numeric-literal-issues`, `report-portability-issues`,
  `remove-unnecessary-parentheses`, `remove-unnecessary-semicolons`, `simplify-annotations`,
  `simplify-array-initializers`, `sort-modifiers`, `use-text-block`
- Types: `replace-string-builder-with-string`, `use-diamond-operator`, `use-string-builder`

Run `--list-tools` for their CLI descriptions. See [ROADMAP.md](ROADMAP.md) for the complete
IntelliJ Java inspection inventory, implementation mapping, and future candidates.

## Installation

JRefine requires JDK 25 or newer. Download `jrefine.jar` and
`jrefine.jar.sha256` from the
[latest GitHub release](https://github.com/ralscha/jrefine/releases/latest), then verify
the download:

```shell
sha256sum --check jrefine.jar.sha256
java -jar jrefine.jar --version
```

On Windows PowerShell:

```powershell
$expected = (Get-Content .\jrefine.jar.sha256).Split()[0]
$actual = (Get-FileHash -Algorithm SHA256 .\jrefine.jar).Hash.ToLowerInvariant()
$actual -eq $expected
java -jar .\jrefine.jar --version
```

The checksum comparison prints `True` when the Windows download matches the released artifact.

## Building from source

The project requires JDK 25 or newer. Maven itself does not need to be installed because the
repository contains Maven Wrapper scripts pinned to Maven 3.9.16.

On macOS or Linux:

```shell
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

The build produces a runnable, dependency-containing JAR at `target/jrefine.jar`.

## Development tasks

Install [Task](https://taskfile.dev/) to use the cross-platform commands in
[`Taskfile.yml`](Taskfile.yml):

```shell
task                 # list available tasks
task format          # apply Spring Java Format
task format-check    # check Java source formatting
task test            # run all tests
task verify          # run the complete CI verification build
task package         # build the runnable JAR
```

## CLI usage

Check an entire codebase without changing it:

```shell
java -jar target/jrefine.jar path/to/project
```

Apply fixes from the default high-confidence profile:

```shell
java -jar target/jrefine.jar --apply path/to/project
```

The default `high-confidence` profile contains safe rewrites and probable correctness checks.
Subjective style, complexity, and performance policies are opt-in:

```shell
java -jar target/jrefine.jar --profile policy path/to/project
java -jar target/jrefine.jar --profile all path/to/project
```

Select one or more inspections:

```shell
java -jar target/jrefine.jar --tool remove-unused-imports --apply path/to/project
java -jar target/jrefine.jar -t remove-unused-imports,use-diamond-operator path/to/project
```

Explicit `--tool` selections are not restricted by the active profile. Tools can be suppressed or
assigned project-specific severities, and a minimum severity can act as a quality gate:

```shell
java -jar target/jrefine.jar --suppress report-numeric-literal-issues path/to/project
java -jar target/jrefine.jar --severity report-api-misuse-bugs=error \
  --minimum-severity warning path/to/project
```

Set the source/API release that generated changes must support. Profile runs automatically omit
tools requiring a newer release; explicitly selecting an incompatible tool is an error:

```shell
java -jar target/jrefine.jar --target-java 17 --apply path/to/project
```

Supported targets are Java 8, 11, 17, 21, and 25. Java 25 is the default.

Use `--threads N` to control file-level parallelism and `--timings` to print aggregate per-tool
execution time. Check-only runs parse each file once; apply mode intentionally reparses between fix
rounds so independent transformations compose safely.

### Project configuration and suppressions

The CLI discovers the nearest `.jrefine.properties` from the input path upward. An explicit
`--config FILE` takes precedence. CLI values override configuration values, while CLI suppressions
are added to configured suppressions.

```properties
profile=high-confidence
target-java=17
minimum-severity=warning
suppress=tool-id,another-tool-id
severity.report-api-misuse-bugs=error
threads=8
timings=false
```

To suppress one tool for an entire source file, add a real Java comment anywhere in that file:

```java
// jrefine-ignore-file report-numeric-literal-issues
```

Whole-file suppression is deliberate: it prevents a suppressed transformation from being applied,
which a finding-only suppression could not guarantee safely.

Discover tools and options:

```shell
java -jar target/jrefine.jar --list-tools
java -jar target/jrefine.jar --help
```

Check mode exits with `1` when it finds issues, making it useful in CI. A successful apply exits
with `0`; invalid arguments, parse failures, and I/O failures exit with `2`. Directories commonly
used for generated or third-party files (`target`, `build`, `out`, `node_modules`, and hidden tool
directories) are skipped.

CI runs the normal Maven verification and `scripts/dogfood.sh`. The dogfood script copies the
working tree to an isolated directory, applies the high-confidence profile, recompiles and tests the
result, rescans it, and fails if applying fixes changed tracked source content.

## Releases

Pushing a `vX.Y.Z` tag whose version matches `pom.xml` runs the complete Maven verification and
dogfood build, checks the version reported by the packaged JAR, and creates a GitHub release. The
release contains the runnable `jrefine.jar` and its `jrefine.jar.sha256` checksum.

For example, publish version 1.0.0 after its release commit is on the default branch:

```shell
git tag v1.0.0
git push origin v1.0.0
```

The release workflow uses the repository's built-in `GITHUB_TOKEN`; no release secret is required.
The repository's GitHub Actions workflow permissions must allow read and write access to repository
contents.

## Adding an inspection

Each inspection implements the small API in
`ch.rasc.jrefine.api.InspectionTool`. The engine supplies an `InspectionContext` containing
the source path, JavaParser `CompilationUnit`, range-based `SourceEditor`, and target Java release. The implementation
returns source-located findings and queues edits only when `applyFixes` is true.

Style, complexity, and advisory performance rules implement `PolicyInspectionTool`, which assigns
the opt-in `policy` profile and `info` severity. Other tools default to `high-confidence` and
`warning`; either severity can be overridden by project configuration.

```java
public final class MyInspection implements InspectionTool {
    public String id() {
        return "my-inspection";
    }

    public String description() {
        return "Explain what this inspection improves";
    }

    public int minimumJavaVersion() {
        return 8;
    }

    public ToolResult inspect(InspectionContext context, boolean applyFixes) {
        // Find AST nodes and create Finding values.
        // When applying, use context.editor().replace(...) or .remove(...),
        // then update the AST to match.
        return ToolResult.of(findings, applyFixes);
    }
}
```

Built-in tools are grouped by responsibility under `ch.rasc.jrefine.tools`: `controlflow`,
`declarations`, `expressions`, `syntax`, and `types`. Cross-cutting source analysis belongs in
`ch.rasc.jrefine.analysis` rather than in a tool category. Rewrites that depend on evaluation
timing, capture, reassignment, or target typing should use `SemanticEvidence` and return no
candidate when the required fact cannot be proven.

Register the implementation by adding its fully qualified class name to:

```text
src/main/resources/META-INF/services/ch.rasc.jrefine.api.InspectionTool
```

The CLI discovers implementations through `ServiceLoader`, validates unique kebab-case IDs, and
automatically exposes them through `--list-tools` and `--tool`. In apply mode the engine reparses
after each fix round, reruns an inspection until it is stable, and then moves to the next selected
inspection. This lets independently developed tools compose without overlapping edits, while the
file is written only after the final source parses successfully.

## Safety boundaries

Unused-import analysis is syntax based so that it works without compiling the target project or
resolving its dependency classpath. It deliberately retains wildcard imports and can retain an
unused explicit import when the same simple name is also used as a local expression name. This
favours a false negative over deleting a required import.

Wildcard-import replacement expands imports only when referenced types can be found in the runtime
classpath or as public types in the same source root. Static wildcard imports require a loadable
owner type. Unresolvable and ambiguous imports are retained.

API and language-migration tools likewise require recognizable local shapes and known JDK receiver
types where a same-named user API could behave differently. Loop rewrites reject mutation or leaked
iterator/index state; switch and catch rewrites reject fall-through, related catch alternatives,
and comments that could not be moved without losing intent.

Diamond conversion skips anonymous classes, `var` initializers, and object creations used as a
method/field receiver because removing their explicit arguments can alter the inferred expression
type. The generated diamond syntax requires Java 7 or newer.

The other inspections follow the same conservative rule. Receiver-sensitive rewrites such as
`isEmpty()` and indexed-list conversion require a lexically visible known JDK type. Compound
assignment requires a stable target. Boolean cleanup retains expressions whose evaluation may have
side effects. `StringBuffer` conversion requires a non-escaping, non-captured local, and enhanced
for conversion rejects loops that still use the index or mutate the traversed value. These choices
intentionally prefer missed cleanup opportunities over behavior-changing edits.

The newer declaration and syntax cleanups likewise require local proof: Object rewrites reject
shadowing types, empty-string equality requires `java.lang.String`, constructor removal preserves
implicit access, label cleanup resolves its actual jump target, and edits containing comments are
generally retained when moving them could lose intent.

Verbose-code rewrites recognize integer range subsumption, canonical zero-to-length array-fill
loops, known Stream mapping stages immediately before `count()`, and `Objects.requireNonNull`
around intrinsically non-null expressions. Lombok accessor and duplicate-code opportunities are
report-only. Lombok contract checks require recognized imports, and generated-method static imports
are reported only when the annotated owner is available in the same source root. The
`lombok.copyableAnnotations` setting is deliberately not inferred, so Spring qualifier copying
remains excluded with other configuration-dependent Lombok policy.

Probable-bug inspections are report-only and use source-local evidence: declared types and generic
arguments, local inheritance and annotations, literal format/API arguments, statement ordering,
and recognizable JDK call shapes. Checks that normally rely on whole-project data flow or a
dependency classpath intentionally report only the subset that can be proven from one source file.

Runtime-safety reporters follow the same boundary. Resource findings require a locally owned value
from a proven JDK, JDBC, Hibernate, or JNDI constructor/factory and are suppressed by a visible
close, return, or argument-based ownership transfer. Injection findings require demonstrable parameter or local-data
flow into SQL, process, or native-library input; unknown fields and external constants are not
treated as tainted. Concurrency checks cover mechanical monitor, Lock, Thread, ThreadLocal, and
volatile-field mistakes rather than project-specific synchronization policy. Concurrency-contract
checks require source-local fields and hierarchies before reporting broken double-checked locking,
atomic updater declarations, static initialization, or synchronized overrides. JUnit checks require
recognized JUnit owners or static imports, and locale/charset checks report the implicit process
default without guessing whether `Locale.ROOT`, a user locale, or a particular charset is intended.
TestNG checks likewise require recognized framework annotations. Provider and method dependency
lookups traverse only source-local inheritance and are skipped when an external parent could supply
the declaration; expected checked exceptions are reported only when the method body contains no
call, construction, or direct throw that could produce one. Suite registration and group definitions
remain outside the Java-only source model.
Broader internationalization findings are opt-in because fixed-format dates, numbers, string
comparison, whitespace handling, and tokenization may be intentional for machine-readable data.

Construction-and-contract reporters also stay source-local. Initialization checks require direct
field-order evidence, explicit publication, or constructor dispatch proven within the source file,
with constructor checks honoring standard `this-escape` suppressions. Serialization checks do not
guess whether external supertypes implement `Serializable`; they report direct contracts and
source-local types only.
Throwable-construction checks recognize only JDK exception types with a known cause constructor;
same-named source types and already cause-accepting constructor calls are excluded.
Logging checks recognize established logging APIs and report eager concatenation only where the API
provides a parameterized or lazy alternative.

Security-sensitive-code findings are policy-level: they identify exposed mutable state, process-wide
system-property access, class-loading boundaries, obsolete security-manager use, and predictable
random generation only in security-named contexts. Security-hardening findings add conservative,
source-visible checks for unfiltered `ObjectInputStream` use, incompletely hardened JAXP factories,
literal weak cryptographic algorithms, and archive paths written without normalized containment
validation. Reflection checks validate literal lookups and
invocations only for types declared in the same source file, including compiler-generated record
and enum members. JavaBeans checks require a directly corresponding backing field and a mechanically
provable wrong-field or self-assignment, so computed accessors remain outside their scope.
Javadoc contract checks report only mechanically invalid tags, missing deprecation annotations,
dangling or package metadata, unbalanced structural HTML, and references to missing members of a
source-local type. External documentation references are retained without a project classpath.
Field-initialization checks require an explicitly imported, recognized non-null annotation and
accept visible initializer blocks, constructor delegation, and source-local initialization helpers.
Static-member qualification is limited to stable variable receivers and members declared on a
source-local type, so receiver side effects and ambiguous overloads are retained.

Reliability-policy reporters keep thread scheduling, monitor ownership, guarded-state annotations,
synchronization consistency, source-local wait/notify or await/signal coordination, broad exception handling,
direct `DriverManager` acquisition, terminal explicit closes in try-with-resources, and
exception-message/causality conventions opt-in. Async-correctness review covers discarded
`Future`/`CompletionStage` results, locally created executors without a visible lifecycle, and
stack-confined Java 25 `ThreadLocal` candidates for `ScopedValue`; returned or transferred ownership
is accepted. Serialization-state checks remain in the
high-confidence profile: they require a directly proven Serializable type plus source-local evidence
that a field cannot be serialized, a custom `readObject()` omits state, a transient initializer is
lost, a record hook is ignored, or a non-serializable ancestor cannot be constructed.

Inheritance and name-shadowing reporters are policy-level because overloads, abstract hierarchies,
and repeated member names may be intentional API design. They require the relevant declarations in
the same source file. Redundant declaration cleanup remains high-confidence and only removes
reifiable `@SafeVarargs`, duplicate throws types, source-local annotation arguments equal to their
declared defaults, and the implicitly required `java.base` module directive.

API-design reporters are also opt-in. Encapsulation checks require directly declared fields, nested
types, constructors, or source-local visibility relationships; mutable-state checks recognize only
known mutable JDK containers, arrays, buffers, and atomic holders and ignore defensive-copy returns.
Abstraction checks use known JDK `Optional` and collection types plus source-local interface and
subclass relationships, avoiding project-wide hierarchy guesses. Overly strong casts are reported
only when the cast is the receiver of a zero-argument operation supplied by one unique local parent;
they remain report-only because a stronger cast may intentionally enforce a `ClassCastException`.
Closed-hierarchy findings are limited to private nested types, whose possible Java subclasses are
contained by the same top-level declaration; annotated, marker, and functional interfaces are excluded.
These reporters skip generated compilation units; mutable-state checks also exclude
persistence-managed collections and annotated framework state.

API naming checks are opt-in and compare declarations only within the same compilation unit. They
cover parameter-name drift, constructor-like method names, case-only names, same-arity and varargs
overloads, and recognizable source-local or JDK functional interfaces. Module checks inspect parsed
`module-info.java` directives and report `ServiceLoader` calls only when a nearest source descriptor
can be read and the service type can be resolved from an explicit import or package declaration.
Assignment and bitwise issue reporters likewise remain diagnostic-only: parameter/field mutation,
nested assignment, used increment results, impossible masks, and out-of-range shifts often expose
an intent error for which a mechanical rewrite would be misleading.

Inheritance migrations resolve only locally declared supertypes (plus well-known `Object` methods),
and redundant interfaces are removed only when another local supertype proves the relationship.
Static inheritance likewise requires a source-local interface whose inherited API is entirely
static. JavaBeans construction/accessor conventions are opt-in, ignore implicit default
constructors, and recognize imported Lombok generation annotations.
The newer numeric tools infer primitive types lexically, limit overflow fixes to proven `long`
contexts, and leave precision-losing casts as report-only findings. Collection factories, legacy
enumerations, equality guards, append loops, and `if` chains are rewritten only for recognized JDK
owners and canonical source shapes. Raw generic reporting is limited to source-local declarations
and JDK types resolved from explicit, wildcard, or `java.lang` imports; class literals and generic
array-constructor references are excluded.

Text-block conversion is limited to comment-free, all-literal concatenations containing at least
two newline characters; generated escapes preserve the exact String value, including trailing
spaces and a missing final newline. Null-fallback conversion requires a proven local or parameter
and uses an eager fallback only when it is intrinsically non-null. Lazy conversion is limited to
array creation or exception-free source-local construction, with checked calls, mutations, nested
construction, and non-effectively-final captures retained as written.

Java 8 functional migrations likewise require canonical local shapes and known JDK or Guava owners.
Anonymous-class conversion rejects `this` and `super`, Map and collection pipelines require lexical
JDK receiver types, and try-with-resources conversion rejects resources used after the original
`try`. Guava stream migrations can change lazy evaluation to eager collection, matching the source
inspection's documented migration semantics.

Varargs migration is limited to a private, uniquely named method whose last parameter is a
one-dimensional reifiable array; public APIs, overload sets, overrides, generic components, and
multidimensional arrays are retained. Serialization replacement-hook visibility is reported only
for directly recognized Serializable owners, with the permitted private hook in a final class
excluded. Ambiguous inherited access checks are policy-level and require a unique source-local
superclass plus a directly visible surrounding field, captured value, or no-argument method.

Source-local data-flow cleanup moves declarations only when their initializer is a literal,
class literal, `this`, or an effectively-final local value and every use remains in the same
execution boundary. Guarded constant substitution requires an unreassigned local or parameter,
an exact `==` branch (or the false branch of `!=`), and emits a cast when needed to preserve the variable's
static type; floating-point and deferred lambda uses are excluded. Functional-wrapper and
constant-parameter findings are diagnostic-only, with the latter in the opt-in policy profile, and require known JDK functional interfaces or a
private non-overloaded method whose visible calls all agree.

Performance rewrites require lexically proven JDK wrapper, file, and collection types. Bulk file
attribute reads are limited to repeated calls in one statement where `IOException` is already
handled; as with the source inspection, callers should account for the different failure behavior
when a file does not exist. Bulk collection replacement handles only direct element-copy loops.

Redundancy cleanup is similarly scoped to recognizable JDK calls and locally provable target types.
Varargs-array removal is limited to `Arrays.asList`, reference-cast removal to assignment and return
conversions, and Stream/Optional cleanup to known pipelines. Array guards are unwrapped only when
they contain exactly the matching iteration, and immutable wrappers are removed only around known
JDK immutable factories. Java string templates are intentionally unsupported because the preview
feature was withdrawn and is not accepted by the project's parser.

Literal-element inlining requires side-effect-free initializers and preserves the accessed element's
expression type. Manual min/max conversion is limited to stable `int` and `long` locals to avoid
floating-point and narrow-integral differences. Empty-string removal requires the remaining syntax
to stay string-typed, while cast-variable reuse rejects intervening assignments and nested scopes.

Serialization annotation validation shares the same lexical member rules as annotation insertion,
preventing opposing fixes. JDBC index-zero and write-only-object checks are report-only because the
intended database column and the appropriate lifecycle correction cannot be inferred safely.
Write-only analysis currently covers non-escaping local JDK atomic holders with setter-only use.

The latest declaration and Java 21 cleanups remain source-local as well. Redundant overrides are
removed only against a visible superclass with the same contract, record constructors must match
their canonical component assignments, and throws cleanup is limited to private methods whose body
and call sites cannot require the declaration. Record-pattern conversion requires complete ordered
deconstruction and no later use of the original binding. Plain `replaceAll`, collection-copy, and
`String.format` rewrites require literal or known-JDK shapes that preserve evaluation semantics.

Verbose-code cleanup now also recognizes no-effect literal replacement, redundant apostrophe
escapes, cast-only weak locals, canonical collector and Stream terminal chains, terminal switch
breaks, exhaustive local-enum defaults, and broader catches made unreachable by the only explicit
checked exception. Transformations that need whole-program type or exception analysis remain out of
scope rather than guessing across unresolved APIs.

Straight-line local `StringBuilder` and `StringBuffer` assembly is collapsed only when every use is
an adjacent supported append or a later `toString()` conversion. Range cleanup is limited to stable
local integral values whose finite domain proves a single included or excluded value. Excessive
lambda cleanup accepts only trivial `Optional` suppliers whose eager value evaluation is inert.

Numeric-issue coverage uses lexical primitive types and constant-expression evaluation. Safe fixes
are limited to recognized JDK wrappers and `BigDecimal`, equality-style NaN checks, sign-safe oddness
checks, proven Math identities, and side-effect-free arithmetic identities. Rounding choices,
floating-point tolerances, conversion intent, literal style, and expression decomposition remain
report-only because those decisions require project-specific numerical policy.

Performance coverage applies automatic changes only for locally proven JDK forms such as array
constructor references, `Files` stream factories, wrapper factories/parsers, character search
overloads, separated builder appends, bounded random integers, enum identity, and class literals.
Capacity sizing, collection implementation changes, static conversion, lifecycle handling, loop
restructuring, regex caching, boxing policy, and embedded-device thresholds remain report-only
because their best correction depends on workload, API contracts, or deployment constraints.

## License

JRefine is licensed under the [Apache License 2.0](LICENSE).
