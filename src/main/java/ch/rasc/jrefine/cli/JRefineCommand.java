package ch.rasc.jrefine.cli;

import ch.rasc.jrefine.api.Finding;
import ch.rasc.jrefine.api.InspectionTool;
import ch.rasc.jrefine.api.JavaVersion;
import ch.rasc.jrefine.api.Severity;
import ch.rasc.jrefine.engine.EngineOptions;
import ch.rasc.jrefine.engine.ReportedFinding;
import ch.rasc.jrefine.engine.ModernizationReport;
import ch.rasc.jrefine.engine.JRefineEngine;
import ch.rasc.jrefine.engine.ToolRegistry;
import ch.rasc.jrefine.engine.ToolProfile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "jrefine", description = "Inspect and modernize Java source files.", mixinStandardHelpOptions = true,
		versionProvider = JRefineVersionProvider.class, sortOptions = false)
public final class JRefineCommand implements Callable<Integer> {

	private final ToolRegistry registry;

	private final JRefineEngine engine;

	@Parameters(index = "0", arity = "0..1", paramLabel = "PATH", defaultValue = ".",
			description = "Java file or source tree to inspect (default: current directory)")
	private Path input;

	@Option(names = { "-w", "--apply" }, description = "Write safe fixes to source files")
	private boolean applyFixes;

	@Option(names = { "-t", "--tool" }, paramLabel = "ID", split = ",",
			description = "Run only this tool; repeat or use commas for multiple tools")
	private LinkedHashSet<String> requestedTools = new LinkedHashSet<>();

	@Option(names = "--profile", paramLabel = "NAME",
			description = "Tool profile: high-confidence (default), policy, or all")
	private String requestedProfile;

	@Option(names = "--suppress", paramLabel = "ID", split = ",",
			description = "Suppress a tool for this run; repeat or use commas")
	private LinkedHashSet<String> suppressedTools = new LinkedHashSet<>();

	@Option(names = "--severity", paramLabel = "ID=LEVEL", split = ",",
			description = "Override tool severity with info, warning, or error")
	private List<String> requestedSeverities = new ArrayList<>();

	@Option(names = "--minimum-severity", paramLabel = "LEVEL", description = "Run tools at or above this severity")
	private String requestedMinimumSeverity;

	@Option(names = "--threads", paramLabel = "N", description = "Maximum files inspected concurrently")
	private Integer requestedParallelism;

	@Option(names = "--target-java", paramLabel = "RELEASE", description = "Target Java release: 8, 11, 17, 21, or 25")
	private String requestedTargetJava;

	@Option(names = "--timings", description = "Print aggregate per-tool execution timings")
	private boolean timings;

	@Option(names = "--config", paramLabel = "FILE",
			description = "Configuration file (default: nearest .jrefine.properties)")
	private Path configurationPath;

	@Option(names = "--list-tools", description = "List available tools and exit")
	private boolean listTools;

	@picocli.CommandLine.Spec
	private picocli.CommandLine.Model.CommandSpec spec;

	public JRefineCommand(ToolRegistry registry, JRefineEngine engine) {
		this.registry = registry;
		this.engine = engine;
	}

	@Override
	public Integer call() {
		if (listTools) {
			registry.all()
				.forEach(tool -> this.out()
					.printf("%-34s %-15s %-7s Java %-2d %s%n", tool.id(), confidenceLabel(tool),
							tool.defaultSeverity().name().toLowerCase(Locale.ROOT), tool.minimumJavaVersion(),
							tool.description()));
			return 0;
		}

		ResolvedRun resolved = this.resolveRun();
		if (resolved == null) {
			return 2;
		}

		ModernizationReport report = engine.run(input, resolved.tools(), applyFixes, resolved.options());
		this.printReport(report);

		if (!report.errors().isEmpty()) {
			return 2;
		}
		if (!applyFixes && !report.findings().isEmpty()) {
			return 1;
		}
		if (applyFixes && report.findings().stream().anyMatch(finding -> !finding.fixed())) {
			return 1;
		}
		return 0;
	}

	private ResolvedRun resolveRun() {
		try {
			JRefineConfiguration configuration = JRefineConfiguration.load(input, configurationPath);
			ToolProfile profile = requestedProfile == null ? configuration.profile()
					: ToolProfile.parse(requestedProfile);

			LinkedHashSet<String> suppressed = new LinkedHashSet<>(configuration.suppressedTools());
			suppressed.addAll(suppressedTools);
			suppressed.forEach(registry::require);

			LinkedHashMap<String, Severity> severities = new LinkedHashMap<>(configuration.severityOverrides());
			parseSeverityAssignments(requestedSeverities, severities);
			severities.keySet().forEach(registry::require);

			Severity minimum = requestedMinimumSeverity == null ? configuration.minimumSeverity()
					: Severity.parse(requestedMinimumSeverity);
			int parallelism = requestedParallelism == null ? configuration.parallelism() : requestedParallelism;
			if (parallelism < 1) {
				throw new IllegalArgumentException("--threads must be a positive integer");
			}
			JavaVersion targetJava = requestedTargetJava == null ? configuration.targetJava()
					: JavaVersion.parse(requestedTargetJava);

			List<InspectionTool> profileTools = registry.select(requestedTools, profile);
			List<InspectionTool> incompatible = profileTools.stream()
				.filter(tool -> !targetJava.supports(tool.minimumJavaVersion()))
				.toList();
			if (!requestedTools.isEmpty() && !incompatible.isEmpty()) {
				InspectionTool tool = incompatible.getFirst();
				throw new IllegalArgumentException("Tool '" + tool.id() + "' requires Java " + tool.minimumJavaVersion()
						+ " but target Java is " + targetJava.release());
			}
			List<InspectionTool> selected = profileTools.stream()
				.filter(tool -> targetJava.supports(tool.minimumJavaVersion()))
				.filter(tool -> !suppressed.contains(tool.id()))
				.filter(tool -> severities.getOrDefault(tool.id(), tool.defaultSeverity()).atLeast(minimum))
				.toList();
			EngineOptions options = new EngineOptions(parallelism, timings || configuration.timings(), severities,
					targetJava);
			return new ResolvedRun(selected, options);
		}
		catch (IOException | IllegalArgumentException exception) {
			this.err().println(exception.getMessage());
			this.err().println("Use --list-tools to see available tool ids.");
			return null;
		}
	}

	private void printReport(ModernizationReport report) {
		for (ReportedFinding reported : report.findings()) {
			Finding finding = reported.finding();
			this.out()
				.printf("%s %s:%d:%d [%s:%s] %s%n", reported.fixed() ? "FIXED" : "CHECK",
						this.displayPath(reported.path()), finding.line(), finding.column(), reported.toolId(),
						reported.severity().name().toLowerCase(Locale.ROOT), finding.message());
		}
		report.errors()
			.forEach(error -> this.err().printf("ERROR %s: %s%n", this.displayPath(error.path()), error.message()));

		if (!report.errors().isEmpty()) {
			this.err()
				.printf("Failed with %d error(s) after scanning %d file(s).%n", report.errors().size(),
						report.scannedFiles());
		}
		else if (report.findings().isEmpty()) {
			this.out().printf("No findings in %d file(s).%n", report.scannedFiles());
		}
		else if (applyFixes) {
			long fixed = report.findings().stream().filter(finding -> finding.fixed()).count();
			long remaining = report.findings().size() - fixed;
			this.out().printf("Applied %d fix(es) in %d file(s).%n", fixed, report.changedFiles());
			if (remaining > 0) {
				this.out().printf("%d issue(s) remain without an applied fix.%n", remaining);
			}
		}
		else {
			long filesWithFindings = report.findings().stream().map(ReportedFinding::path).distinct().count();
			this.out()
				.printf("Found %d issue(s) in %d file(s). Run again with --apply to fix them.%n",
						report.findings().size(), filesWithFindings);
		}

		if (!report.timings().isEmpty()) {
			this.out().println("Tool timings:");
			report.timings()
				.forEach(timing -> this.out()
					.printf("  %-34s %8.3f ms  %d call(s)  %d finding(s)%n", timing.toolId(),
							timing.durationNanos() / 1_000_000.0, timing.invocations(), timing.findings()));
			this.out().printf("Total: %.3f ms%n", report.durationNanos() / 1_000_000.0);
		}
	}

	private static void parseSeverityAssignments(List<String> assignments, Map<String, Severity> destination) {
		for (String assignment : assignments) {
			int equals = assignment.indexOf('=');
			if (equals < 1 || equals == assignment.length() - 1) {
				throw new IllegalArgumentException("Invalid severity override '" + assignment + "'; expected ID=LEVEL");
			}
			String id = assignment.substring(0, equals).strip();
			destination.put(id, Severity.parse(assignment.substring(equals + 1)));
		}
	}

	private static String confidenceLabel(InspectionTool tool) {
		return tool.confidence().name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

	private String displayPath(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		Path workingDirectory = Path.of("").toAbsolutePath().normalize();
		try {
			return workingDirectory.relativize(absolute).toString();
		}
		catch (IllegalArgumentException ignored) {
			return absolute.toString();
		}
	}

	private PrintWriter out() {
		return spec.commandLine().getOut();
	}

	private PrintWriter err() {
		return spec.commandLine().getErr();
	}

	private record ResolvedRun(List<InspectionTool> tools, EngineOptions options) {
	}

}
