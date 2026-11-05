package dev.protegemcp;

/** A failure the MCP client should see as a tool result with isError set. */
final class ToolError extends Exception {
    ToolError(String message) {
        super(message);
    }
}
