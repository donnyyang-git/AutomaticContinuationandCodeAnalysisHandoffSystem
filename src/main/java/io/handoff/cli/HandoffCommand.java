package io.handoff.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
        name = "handoff",
        version = "1.0.0",
        mixinStandardHelpOptions = true,
        description = "Automatic continuation and code analysis handoff CLI.",
        subcommands = HandoffCommand.StatusCommand.class)
public final class HandoffCommand implements Runnable {
    static final int SUCCESS = 0;
    static final int PRECONDITION_ERROR = 10;

    @Option(
            names = "--db",
            description = "SQLite database path.",
            defaultValue = "work/handoff.db",
            paramLabel = "PATH")
    Path database;

    @Option(
            names = "--json",
            description = "Emit machine-readable output.")
    boolean json;

    @Override
    public void run() {
        // Picocli renders the root help when requested. Without a subcommand, keep the
        // shell successful and quiet so future commands can own their output.
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new HandoffCommand()).execute(args);
        System.exit(exitCode);
    }

    @Command(name = "status", description = "Show the durable task status.")
    static final class StatusCommand implements Callable<Integer> {
        @ParentCommand
        HandoffCommand parent;

        @Spec
        CommandSpec commandSpec;

        @Parameters(index = "0", description = "Task UUID.", paramLabel = "TASK-ID")
        String taskId;

        @Override
        public Integer call() {
            PrintWriter out = commandSpec.commandLine().getOut();
            if (parent.json) {
                out.print(
                        "{\"error\":{\"code\":\"E_PRECONDITION\",\"message\":\"Unknown task: "
                                + taskId
                                + "\"}}\n");
            } else {
                out.print("Unknown task: " + taskId + "\n");
            }
            out.flush();
            return PRECONDITION_ERROR;
        }
    }
}
