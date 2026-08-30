package ch.rasc.jrefine.engine;

import ch.rasc.jrefine.api.InspectionContext;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.SourceEditor;
import ch.rasc.jrefine.api.ToolResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.LongAdder;

/** Parses source files, runs selected tools, and writes fixed source when requested. */
public final class JRefineEngine {

	private static final int MAX_FIX_ROUNDS_PER_TOOL = 100;

	private final SourceScanner sourceScanner;

	public JRefineEngine() {
		this(new SourceScanner());
	}

	JRefineEngine(SourceScanner sourceScanner) {
		this.sourceScanner = sourceScanner;
	}

	public ModernizationReport run(Path input, List<InspectionTool> tools, boolean applyFixes) {
		return run(input, tools, applyFixes, EngineOptions.defaults());
	}

	public ModernizationReport run(Path input, List<InspectionTool> tools, boolean applyFixes, EngineOptions options) {
		InspectionTool incompatible = tools.stream()
			.filter(tool -> !options.targetJava().supports(tool.minimumJavaVersion()))
			.findFirst()
			.orElse(null);
		if (incompatible != null) {
			throw new IllegalArgumentException("Tool '" + incompatible.id() + "' requires Java "
					+ incompatible.minimumJavaVersion() + " but target Java is " + options.targetJava().release());
		}
		long runStarted = System.nanoTime();
		List<Path> sourceFiles;
		try {
			sourceFiles = sourceScanner.findJavaFiles(input);
		}
		catch (IOException exception) {
			return new ModernizationReport(0, 0, List.of(), List.of(new ProcessingError(input, exception.getMessage())),
					List.of(), System.nanoTime() - runStarted);
		}

		TimingCollector timings = new TimingCollector(options.collectTimings());
		List<FileOutcome> outcomes = processFiles(sourceFiles, tools, applyFixes, options, timings);
		ArrayList<ReportedFinding> findings = new ArrayList<>();
		ArrayList<ProcessingError> errors = new ArrayList<>();
		int changedFiles = 0;
		for (FileOutcome outcome : outcomes) {
			findings.addAll(outcome.findings());
			errors.addAll(outcome.errors());
			if (outcome.changed()) {
				changedFiles++;
			}
		}
		return new ModernizationReport(sourceFiles.size(), changedFiles, findings, errors, timings.snapshot(),
				System.nanoTime() - runStarted);
	}

	private List<FileOutcome> processFiles(List<Path> sourceFiles, List<InspectionTool> tools, boolean applyFixes,
			EngineOptions options, TimingCollector timings) {
		if (sourceFiles.size() < 2 || options.parallelism() == 1) {
			return sourceFiles.stream().map(path -> processFile(path, tools, applyFixes, options, timings)).toList();
		}

		int workers = Math.min(options.parallelism(), sourceFiles.size());
		try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
			List<Future<FileOutcome>> futures = sourceFiles.stream()
				.map(path -> executor.submit(() -> processFile(path, tools, applyFixes, options, timings)))
				.toList();
			ArrayList<FileOutcome> outcomes = new ArrayList<>();
			for (int index = 0; index < futures.size(); index++) {
				try {
					outcomes.add(futures.get(index).get());
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					outcomes.add(FileOutcome.error(sourceFiles.get(index), "Inspection was interrupted"));
				}
				catch (ExecutionException exception) {
					outcomes.add(FileOutcome.error(sourceFiles.get(index),
							"Inspection failed: " + usefulMessage(exception.getCause())));
				}
			}
			return List.copyOf(outcomes);
		}
	}

	private FileOutcome processFile(Path sourceFile, List<InspectionTool> tools, boolean applyFixes,
			EngineOptions options, TimingCollector timings) {
		String source;
		try {
			source = Files.readString(sourceFile, StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			return FileOutcome.error(sourceFile, "Could not read file: " + exception.getMessage());
		}

		JavaParser parser = createParser();
		return applyFixes ? applyTools(sourceFile, source, tools, options, timings, parser)
				: inspectTools(sourceFile, source, tools, options, timings, parser);
	}

	private FileOutcome inspectTools(Path sourceFile, String source, List<InspectionTool> tools, EngineOptions options,
			TimingCollector timings, JavaParser parser) {
		ArrayList<ProcessingError> errors = new ArrayList<>();
		CompilationUnit compilationUnit = parseSource(parser, source, sourceFile, errors);
		if (compilationUnit == null) {
			return new FileOutcome(List.of(), errors, false);
		}

		ArrayList<ReportedFinding> findings = new ArrayList<>();
		for (InspectionTool tool : tools) {
			if (ToolSuppression.isSuppressed(compilationUnit, tool.id())) {
				continue;
			}
			try {
				InspectionContext context = new InspectionContext(sourceFile, compilationUnit, new SourceEditor(source),
						options.targetJava());
				ToolResult result = inspectTimed(tool, context, false, timings);
				if (result.changed()) {
					throw new IllegalStateException("A tool reported a change during a check-only run");
				}
				result.findings()
					.forEach(finding -> findings
						.add(new ReportedFinding(sourceFile, tool.id(), finding, options.severity(tool), false)));
			}
			catch (RuntimeException exception) {
				errors.add(new ProcessingError(sourceFile,
						"Tool '" + tool.id() + "' failed: " + usefulMessage(exception)));
				break;
			}
		}
		return new FileOutcome(findings, errors, false);
	}

	private FileOutcome applyTools(Path sourceFile, String originalSource, List<InspectionTool> tools,
			EngineOptions options, TimingCollector timings, JavaParser parser) {
		String currentSource = originalSource;
		boolean fileChanged = false;
		boolean inspectionFailed = false;
		ArrayList<ReportedFinding> fileFindings = new ArrayList<>();
		ArrayList<ProcessingError> errors = new ArrayList<>();

		for (InspectionTool tool : tools) {
			try {
				int fixRound = 0;
				boolean rerun;
				do {
					CompilationUnit compilationUnit = parseSource(parser, currentSource, sourceFile, errors);
					if (compilationUnit == null) {
						inspectionFailed = true;
						break;
					}
					if (ToolSuppression.isSuppressed(compilationUnit, tool.id())) {
						rerun = false;
						continue;
					}
					SourceEditor editor = new SourceEditor(currentSource);
					InspectionContext context = new InspectionContext(sourceFile, compilationUnit, editor,
							options.targetJava());
					ToolResult result = inspectTimed(tool, context, true, timings);
					result.findings()
						.forEach(finding -> fileFindings.add(new ReportedFinding(sourceFile, tool.id(), finding,
								options.severity(tool), result.changed())));
					rerun = result.changed();
					if (rerun) {
						if (!editor.hasEdits()) {
							throw new IllegalStateException("A tool reported a change but produced no source edit");
						}
						String rendered = editor.render();
						if (rendered.equals(currentSource)) {
							throw new IllegalStateException("A tool reported a change but left the source unchanged");
						}
						currentSource = rendered;
						fileChanged = true;
						fixRound++;
						if (fixRound >= MAX_FIX_ROUNDS_PER_TOOL) {
							throw new IllegalStateException(
									"Exceeded " + MAX_FIX_ROUNDS_PER_TOOL + " fix rounds; the tool may not converge");
						}
					}
				}
				while (rerun);
				if (inspectionFailed) {
					break;
				}
			}
			catch (RuntimeException exception) {
				errors.add(new ProcessingError(sourceFile,
						"Tool '" + tool.id() + "' failed: " + usefulMessage(exception)));
				inspectionFailed = true;
				break;
			}
		}

		boolean fixesWritten = !fileChanged;
		boolean changed = false;
		if (fileChanged && !inspectionFailed && parseSource(parser, currentSource, sourceFile, errors) != null) {
			try {
				Files.writeString(sourceFile, currentSource, StandardCharsets.UTF_8);
				fixesWritten = true;
				changed = true;
			}
			catch (IOException | RuntimeException exception) {
				errors.add(new ProcessingError(sourceFile, "Could not write file: " + usefulMessage(exception)));
			}
		}

		if (!fixesWritten) {
			fileFindings.replaceAll(reported -> new ReportedFinding(reported.path(), reported.toolId(),
					reported.finding(), reported.severity(), false));
		}
		return new FileOutcome(fileFindings, errors, changed);
	}

	private static ToolResult inspectTimed(InspectionTool tool, InspectionContext context, boolean applyFixes,
			TimingCollector timings) {
		long started = timings.enabled() ? System.nanoTime() : 0;
		ToolResult result = null;
		try {
			result = tool.inspect(context, applyFixes);
			return result;
		}
		finally {
			if (timings.enabled()) {
				timings.record(tool.id(), System.nanoTime() - started, result == null ? 0 : result.findings().size());
			}
		}
	}

	private static CompilationUnit parseSource(JavaParser parser, String source, Path sourceFile,
			List<ProcessingError> errors) {
		ParseResult<CompilationUnit> result;
		try {
			result = parser.parse(source);
		}
		catch (RuntimeException exception) {
			errors.add(new ProcessingError(sourceFile, "Could not parse file: " + usefulMessage(exception)));
			return null;
		}

		if (!result.isSuccessful() || result.getResult().isEmpty()) {
			String message = result.getProblems().isEmpty() ? "Parser produced no compilation unit"
					: result.getProblems().getFirst().getVerboseMessage();
			errors.add(new ProcessingError(sourceFile, "Could not parse file: " + message));
			return null;
		}
		return result.getResult().orElseThrow();
	}

	private static JavaParser createParser() {
		ParserConfiguration configuration = new ParserConfiguration().setCharacterEncoding(StandardCharsets.UTF_8)
			.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
			.setStoreTokens(true);
		return new JavaParser(configuration);
	}

	private static String usefulMessage(Throwable throwable) {
		return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
	}

	private record FileOutcome(List<ReportedFinding> findings, List<ProcessingError> errors, boolean changed) {
		private static FileOutcome error(Path path, String message) {
			return new FileOutcome(List.of(), List.of(new ProcessingError(path, message)), false);
		}
	}

	private static final class TimingCollector {

		private final boolean enabled;

		private final Map<String, TimingAccumulator> values = new ConcurrentHashMap<>();

		private TimingCollector(boolean enabled) {
			this.enabled = enabled;
		}

		private boolean enabled() {
			return enabled;
		}

		private void record(String toolId, long durationNanos, int findings) {
			TimingAccumulator value = values.computeIfAbsent(toolId, ignored -> new TimingAccumulator());
			value.invocations.increment();
			value.findings.add(findings);
			value.durationNanos.add(durationNanos);
		}

		private List<ToolTiming> snapshot() {
			if (!enabled) {
				return List.of();
			}
			return values.entrySet()
				.stream()
				.map(entry -> new ToolTiming(entry.getKey(), entry.getValue().invocations.sum(),
						entry.getValue().findings.sum(), entry.getValue().durationNanos.sum()))
				.sorted(Comparator.comparingLong(ToolTiming::durationNanos)
					.reversed()
					.thenComparing(ToolTiming::toolId))
				.toList();
		}

	}

	private static final class TimingAccumulator {

		private final LongAdder invocations = new LongAdder();

		private final LongAdder findings = new LongAdder();

		private final LongAdder durationNanos = new LongAdder();

	}

}
