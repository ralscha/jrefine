package ch.rasc.jrefine;

import ch.rasc.jrefine.cli.JRefineCommand;
import ch.rasc.jrefine.engine.JRefineEngine;
import ch.rasc.jrefine.engine.ToolRegistry;
import picocli.CommandLine;

import java.io.PrintWriter;

/** Application entry point. */
public final class JRefineMain {

	private JRefineMain() {
	}

	public static void main(String[] args) {
		System.exit(execute(args, new PrintWriter(System.out, true), new PrintWriter(System.err, true)));
	}

	public static int execute(String[] args, PrintWriter out, PrintWriter err) {
		CommandLine command = new CommandLine(new JRefineCommand(ToolRegistry.load(), new JRefineEngine()));
		command.setOut(out);
		command.setErr(err);
		return command.execute(args);
	}

}
