package io.github.rojanfrancis.clichat.exec;

import java.util.List;

/** Everything captured from one CLI run. */
public record ExecutionResult(
        String command,
        long pid,
        int exitCode,          // -1 when the process was killed
        boolean timedOut,
        boolean cancelled,
        long durationMs,
        String stdout,
        boolean stdoutTruncated,
        List<String> stderr) {

    public String exitMeaning() {
        if (timedOut) return "killed after timeout";
        if (cancelled) return "cancelled by user";
        return switch (exitCode) {
            case 0 -> "success";
            case 1 -> "unexpected error";
            case 2 -> "partial result";
            case 3 -> "failed";
            case 4 -> "invalid input";
            case 5 -> "blocked by robots.txt";
            default -> "unknown exit code";
        };
    }
}
