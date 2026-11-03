package io.handoff.cli;

import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
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
        subcommands = {
                HandoffCommand.StatusCommand.class,
                HandoffCommand.RunCommand.class,
                HandoffCommand.PauseCommand.class,
                HandoffCommand.ResumeCommand.class})
public final class HandoffCommand implements Runnable {
    static final int SUCCESS = 0;
    static final int PRECONDITION_ERROR = 10;

    @Spec
    CommandSpec commandSpec;

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
        try (Connection connection = ConnectionFactory.open(database)) {
            SchemaBootstrapper.bootstrap(connection);
        } catch (SQLException failure) {
            throw new CommandLine.ExecutionException(
                    commandSpec.commandLine(), "Unable to bootstrap database", failure);
        }
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

    @Command(name = "run", description = "Run pending work sequentially.")
    static final class RunCommand implements Callable<Integer> {
        @ParentCommand HandoffCommand parent;
        @Parameters(index = "0", description = "Task UUID.", paramLabel = "TASK-ID") String taskId;
        @Option(names = "--pause-after", description = "Request pause after this many committed work items.", defaultValue = "-1")
        int pauseAfter;

        @Override
        public Integer call() throws Exception {
            try (Connection connection = openDatabase(parent)) {
                io.handoff.runtime.TaskRuntime runtime = new io.handoff.runtime.TaskRuntime(connection);
                var executed = new java.util.concurrent.atomic.AtomicInteger();
                runtime.run(taskId, item -> {
                    if (pauseAfter >= 0 && executed.incrementAndGet() == pauseAfter) {
                        runtime.requestPause(taskId, runtime.task(taskId).version());
                    }
                });
                return SUCCESS;
            } catch (io.handoff.runtime.RuntimeException failure) {
                return reportFailure(parent, failure);
            }
        }
    }

    @Command(name = "pause", description = "Request pause at the next transaction boundary.")
    static final class PauseCommand implements Callable<Integer> {
        @ParentCommand HandoffCommand parent;
        @Parameters(index = "0", description = "Task UUID.", paramLabel = "TASK-ID") String taskId;

        @Override
        public Integer call() throws Exception {
            try (Connection connection = openDatabase(parent)) {
                io.handoff.runtime.TaskRuntime runtime = new io.handoff.runtime.TaskRuntime(connection);
                runtime.requestPause(taskId, runtime.task(taskId).version());
                return SUCCESS;
            } catch (io.handoff.runtime.RuntimeException failure) {
                return reportFailure(parent, failure);
            }
        }
    }

    @Command(name = "resume", description = "Resume pending work from the last boundary.")
    static final class ResumeCommand implements Callable<Integer> {
        @ParentCommand HandoffCommand parent;
        @Parameters(index = "0", description = "Task UUID.", paramLabel = "TASK-ID") String taskId;

        @Override
        public Integer call() throws Exception {
            try (Connection connection = openDatabase(parent)) {
                new io.handoff.runtime.IntegrityValidator(connection).validate(taskId);
                new io.handoff.runtime.TaskRuntime(connection).run(taskId, item -> { });
                return SUCCESS;
            } catch (io.handoff.runtime.IntegrityFailure failure) {
                return reportIntegrityFailure(parent, failure);
            } catch (io.handoff.runtime.RuntimeException failure) {
                return reportFailure(parent, failure);
            }
        }
    }

    private static Connection openDatabase(HandoffCommand parent) throws SQLException {
        Connection connection = ConnectionFactory.open(parent.database);
        SchemaBootstrapper.bootstrap(connection);
        return connection;
    }

    private static int reportIntegrityFailure(HandoffCommand parent, io.handoff.runtime.IntegrityFailure failure) {
        if (parent.json) {
            parent.commandSpec.commandLine().getOut().printf(
                    "{\"error\":{\"code\":\"E_INTEGRITY\",\"message\":\"%s\"}}%n",
                    failure.getMessage().replace("\\", "\\\\").replace("\"", "\\\""));
        } else {
            parent.commandSpec.commandLine().getOut().println(failure.getMessage());
        }
        return 30;
    }


    private static int reportFailure(HandoffCommand parent, io.handoff.runtime.RuntimeException failure) {
        if (parent.json) {
            parent.commandSpec.commandLine().getOut().printf(
                    "{\"error\":{\"code\":\"%s\",\"message\":\"%s\"}}%n",
                    failure.code(), failure.getMessage().replace("\\", "\\\\").replace("\"", "\\\""));
        } else {
            parent.commandSpec.commandLine().getOut().println(failure.getMessage());
        }
        return PRECONDITION_ERROR;
    }
}
