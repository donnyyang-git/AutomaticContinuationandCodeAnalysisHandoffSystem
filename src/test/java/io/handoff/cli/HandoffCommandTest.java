package io.handoff.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class HandoffCommandTest {
    private static final String UNKNOWN_TASK_ID = "00000000-0000-7000-8000-000000000000";

    @Test
    void helpIsSuccessfulAndDescribesTheCommand() {
        StringWriter output = new StringWriter();
        CommandLine command = command(output);

        int exitCode = command.execute("--help");

        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("Usage: handoff"));
    }

    @Test
    void unknownTaskStatusReturnsDeterministicPreconditionError() {
        StringWriter output = new StringWriter();
        CommandLine command = command(output);

        int exitCode = command.execute("--json", "status", UNKNOWN_TASK_ID);

        assertEquals(10, exitCode);
        assertEquals(
                "{\"error\":{\"code\":\"E_PRECONDITION\",\"message\":\"Unknown task: "
                        + UNKNOWN_TASK_ID
                        + "\"}}\n",
                output.toString());
    }

    private static CommandLine command(StringWriter output) {
        CommandLine command = new CommandLine(new HandoffCommand());
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(output));
        return command;
    }
}
