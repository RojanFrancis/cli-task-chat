package io.github.rojanfrancis.clichat.result;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The final answer shown in the chat. {@code status} comes from the CLI's own exit code and
 * report, never from the LLM, so the chat cannot claim success that did not happen.
 */
public record TaskOutcome(
        String status,      // success | partial | failed | blocked | rejected | timeout | cancelled | error
        String headline,
        String summary,
        List<String> keyPoints,
        String summarySource,
        JsonNode result,    // clean structured JSON for the user (null when nothing was crawled)
        JsonNode rawReport, // exactly what the CLI printed, parsed
        Execution execution) {

    public record Execution(String command, long pid, int exitCode, String exitMeaning, long durationMs,
                            boolean timedOut, boolean cancelled, int logLines, boolean stdoutTruncated) {}
}
