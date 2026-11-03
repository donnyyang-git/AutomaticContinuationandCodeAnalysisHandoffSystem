package io.handoff.cli;
import io.handoff.index.ConfigurationIndexer;
import io.handoff.index.JavaIndexer;
import io.handoff.index.CodeGraph;
import java.io.IOException;

import io.handoff.persistence.ConnectionFactory;
import io.handoff.persistence.SchemaBootstrapper;
import io.handoff.runtime.AtomicWorkCoordinator;
import io.handoff.runtime.FailureInjection;
import io.handoff.runtime.InjectedFailureException;
import io.handoff.runtime.TaskRuntime;
import io.handoff.runtime.TaskState;
import io.handoff.runtime.WorkItem;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
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
                HandoffCommand.ResumeCommand.class,
                HandoffCommand.InjectFailureCommand.class,
                HandoffCommand.ConfigurationIndexCommand.class,
                HandoffCommand.QueryCommand.class})
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
                runAtomic(connection, taskId, pauseAfter);
                return SUCCESS;
            } catch (InjectedFailureException failure) {
                Runtime.getRuntime().halt(137);
                return 137;
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
                runAtomic(connection, taskId, -1);
                return SUCCESS;
            } catch (io.handoff.runtime.IntegrityFailure failure) {
                return reportIntegrityFailure(parent, failure);
            } catch (io.handoff.runtime.RuntimeException failure) {
                return reportFailure(parent, failure);
            }
        }
    }

    @Command(name = "inject-failure", description = "Arm one deterministic one-shot failure.")
    static final class InjectFailureCommand implements Callable<Integer> {
        @ParentCommand HandoffCommand parent;
        @Parameters(index = "0") String taskId;
        @Parameters(index = "1") String failureName;
        @Option(names = "--operation") String operationId;

        @Override
        public Integer call() throws Exception {
            try (Connection connection = openDatabase(parent)) {
                FailureInjection injection = FailureInjection.valueOf(failureName);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO failure_injection(injection_id,task_id,failure_name,target_operation_id,armed) VALUES(?,?,?,?,1)")) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, taskId);
                    statement.setString(3, injection.name());
                    statement.setString(4, operationId);
                    statement.executeUpdate();
                }
                return SUCCESS;
            } catch (IllegalArgumentException failure) {
                return reportFailure(parent, new io.handoff.runtime.RuntimeException(
                        "E_PRECONDITION", "Unknown failure: " + failureName));
            }
        }
    }

    @Command(name = "index", description = "Parse manifest-listed Java files into the code index.")
    static final class IndexCommand implements Callable<Integer> {
        @ParentCommand HandoffCommand parent;
        @Parameters(index = "0", description = "Existing snapshot ID.", paramLabel = "SNAPSHOT-ID") String snapshotId;
        @Option(names = "--source-root", required = true, paramLabel = "PATH") Path sourceRoot;
        @Option(names = "--manifest", required = true, paramLabel = "PATH") Path manifest;

        @Override
        public Integer call() throws Exception {
            try (Connection connection = openDatabase(parent)) {
                JavaIndexer.IndexResult result = new JavaIndexer().index(connection, snapshotId, sourceRoot, manifest);
                PrintWriter out = parent.commandSpec.commandLine().getOut();
                if (parent.json) {
                    out.printf("{\"manifest_java_files\":%d,\"parsed_files\":%d,\"symbols\":%d,\"diagnostics\":%d}%n",
                            result.manifestJavaFiles(), result.parsedFiles(), result.symbols(), result.diagnostics());
                } else {
                    out.printf("Indexed %d/%d Java files, %d symbols, %d diagnostics%n",
                            result.parsedFiles(), result.manifestJavaFiles(), result.symbols(), result.diagnostics());
                }
                out.flush();
                return SUCCESS;
            } catch (SQLException | IOException failure) {
                return reportFailure(parent, new io.handoff.runtime.RuntimeException("E_INDEX", failure.getMessage()));
            }
        }
    }

    @Command(name = "config-index", description = "Parse manifest-listed XML and JSP configuration files.")
    static final class ConfigurationIndexCommand implements Callable<Integer> {
        @ParentCommand HandoffCommand parent;
        @Parameters(index = "0", description = "Existing snapshot ID.", paramLabel = "SNAPSHOT-ID") String snapshotId;
        @Option(names = "--source-root", required = true, paramLabel = "PATH") Path sourceRoot;
        @Option(names = "--manifest", required = true, paramLabel = "PATH") Path manifest;
        @Option(names = "--task", description = "Task UUID to mark REQUIRES_REVIEW on parser/config conflict.") String taskId;

        @Override
        public Integer call() throws Exception {
            try (Connection connection = openDatabase(parent)) {
                ConfigurationIndexer.IndexResult result = new ConfigurationIndexer().index(
                        connection, snapshotId, sourceRoot, manifest, taskId);
                PrintWriter out = parent.commandSpec.commandLine().getOut();
                if (parent.json) {
                    out.printf("{\"parsed_files\":%d,\"xml_statements\":%d,\"xml_links\":%d,\"view_links\":%d,\"unresolved\":%d,\"diagnostics\":%d}%n",
                            result.parsedFiles(), result.xmlStatements(), result.xmlLinks(), result.viewLinks(),
                            result.unresolved(), result.diagnostics());
                } else {
                    out.printf("Indexed %d configuration files, %d XML statements, %d XML links, %d view links, %d unresolved, %d diagnostics%n",
                            result.parsedFiles(), result.xmlStatements(), result.xmlLinks(), result.viewLinks(),
                            result.unresolved(), result.diagnostics());
                }
                out.flush();
                return SUCCESS;
            } catch (ConfigurationIndexer.ConflictException failure) {
                return reportFailure(parent, new io.handoff.runtime.RuntimeException("E_PARSER_CONFLICT", failure.getMessage()));
            } catch (SQLException | IOException failure) {
                return reportFailure(parent, new io.handoff.runtime.RuntimeException("E_CONFIG_INDEX", failure.getMessage()));
            }
        }
    }


    @Command(name = "query", description = "Query code graph callers, callees, or reverse dependents.")
    static final class QueryCommand implements Callable<Integer> {
        @ParentCommand HandoffCommand parent;
        @Parameters(index = "0", description = "Direction: callers, callees, or reverseDependents.") String direction;
        @Parameters(index = "1", description = "Symbol ID.") String symbolId;
        @Option(names = "--snapshot", required = true, paramLabel = "SNAPSHOT-ID") String snapshotId;

        @Override
        public Integer call() throws Exception {
            try (Connection connection = openDatabase(parent)) {
                CodeGraph graph = new CodeGraph(connection);
                var relations = switch (direction) {
                    case "callers" -> graph.callers(snapshotId, symbolId);
                    case "callees" -> graph.callees(snapshotId, symbolId);
                    case "reverseDependents" -> graph.reverseDependents(snapshotId, symbolId);
                    default -> throw new io.handoff.runtime.RuntimeException("E_QUERY", "Unknown graph direction: " + direction);
                };
                PrintWriter out = parent.commandSpec.commandLine().getOut();
                for (CodeGraph.Relation relation : relations) {
                    out.printf("%s %s %s %s:%d%n", relation.kind(), relation.sourceSymbolId(),
                            relation.targetSymbolId() == null ? relation.targetExternalName() : relation.targetSymbolId(),
                            relation.evidencePath(), relation.evidenceLine());
                }
                out.flush();
                return SUCCESS;
            } catch (io.handoff.runtime.RuntimeException failure) {
                return reportFailure(parent, failure);
            } catch (SQLException failure) {
                return reportFailure(parent, new io.handoff.runtime.RuntimeException("E_QUERY", failure.getMessage()));
            }
        }
    }
    private static void runAtomic(Connection connection, String taskId, int pauseAfter) throws Exception {
        TaskRuntime runtime = new TaskRuntime(connection);
        TaskRuntime.TaskInfo current = runtime.task(taskId);
        if (current.state() == TaskState.PAUSED || current.state() == TaskState.INTERRUPTED
                || current.state() == TaskState.REQUIRES_REVIEW) {
            runtime.transition(taskId, TaskState.RESUMING, current.version());
            current = runtime.task(taskId);
        }
        if (current.state() == TaskState.CREATED) {
            runtime.transition(taskId, TaskState.INDEXING, current.version());
            current = runtime.task(taskId);
        }
        if (current.state() == TaskState.INDEXING || current.state() == TaskState.RESUMING) {
            runtime.transition(taskId, TaskState.ANALYZING, current.version());
        } else if (current.state() != TaskState.ANALYZING) {
            throw new io.handoff.runtime.RuntimeException(
                    "E_STATE_TRANSITION", "Cannot run task in state " + current.state());
        }
        int committed = 0;
        AtomicWorkCoordinator coordinator = new AtomicWorkCoordinator(connection);
        for (WorkItem item : runtime.orderedWork(taskId)) {
            if (!item.status().equals("PENDING") && !item.status().equals("RUNNING")) continue;
            FailureInjection injection = consumeInjection(connection, taskId, item.operationId());
            coordinator.commit(taskId, item.operationId(), injection);
            committed++;
            if (pauseAfter >= 0 && committed == pauseAfter) {
                TaskRuntime.TaskInfo paused = runtime.task(taskId);
                runtime.requestPause(taskId, paused.version());
                runtime.transition(taskId, TaskState.PAUSED, runtime.task(taskId).version());
                return;
            }
        }
        current = runtime.task(taskId);
        runtime.transition(taskId, TaskState.CHECKPOINTING, current.version());
        runtime.transition(taskId, TaskState.COMPLETED, runtime.task(taskId).version());
    }

    private static FailureInjection consumeInjection(Connection connection, String taskId, String operationId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT injection_id,failure_name FROM failure_injection WHERE task_id=? AND armed=1 "
                        + "AND (target_operation_id IS NULL OR target_operation_id=?) ORDER BY injection_id LIMIT 1")) {
            query.setString(1, taskId);
            query.setString(2, operationId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return FailureInjection.NONE;
                String id = result.getString(1);
                FailureInjection injection = FailureInjection.valueOf(result.getString(2));
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE failure_injection SET armed=0,fired_at=? WHERE injection_id=?")) {
                    update.setString(1, Instant.now().toString());
                    update.setString(2, id);
                    update.executeUpdate();
                }
                return injection;
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
