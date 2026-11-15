package io.github.rojanfrancis.clichat.exec;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Starts a process and supervises it: streams stderr line by line as it arrives, collects
 * stdout up to a size cap, enforces a wall-clock timeout and supports cancellation.
 * The child gets an empty environment, so API keys and other secrets never reach it.
 */
@Component
public class CliRunner {

    static final int MAX_STDOUT_BYTES = 4 * 1024 * 1024;
    static final int MAX_STDERR_LINES = 500;

    /** Lets the caller stop a running process. */
    public static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Process process;

        public void cancel() {
            cancelled.set(true);
            Process p = process;
            if (p != null) {
                p.destroy();
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }

    public ExecutionResult run(List<String> argv, String displayCommand, Duration timeout,
                               Consumer<String> onStderrLine, Handle handle) throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("cli-task-");
        ProcessBuilder pb = new ProcessBuilder(argv).directory(workDir.toFile());
        Map<String, String> env = pb.environment();
        String path = env.get("PATH");
        env.clear();
        if (path != null) {
            env.put("PATH", path);
        }
        env.put("LANG", "C.UTF-8");

        long start = System.currentTimeMillis();
        Process process = pb.start();
        handle.process = process;
        if (handle.isCancelled()) {
            process.destroy();
        }
        process.getOutputStream().close(); // the CLI reads nothing from stdin

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        AtomicBoolean truncated = new AtomicBoolean();
        List<String> stderr = Collections.synchronizedList(new ArrayList<>());

        Thread outReader = Thread.ofVirtual().start(() -> copyCapped(process.getInputStream(), stdout, truncated));
        Thread errReader = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (stderr.size() < MAX_STDERR_LINES) {
                        stderr.add(line);
                        onStderrLine.accept(line);
                    }
                }
            } catch (IOException ignored) {
                // stream closes when the process is killed
            }
        });

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean timedOut = false;
        if (!finished) {
            timedOut = !handle.isCancelled();
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
        outReader.join(2000);
        errReader.join(2000);
        deleteQuietly(workDir);

        boolean cancelled = handle.isCancelled();
        int exit = (timedOut || (cancelled && !finished)) ? -1 : process.exitValue();
        return new ExecutionResult(displayCommand, process.pid(), exit, timedOut, cancelled,
                System.currentTimeMillis() - start, stdout.toString(StandardCharsets.UTF_8), truncated.get(),
                List.copyOf(stderr));
    }

    private static void copyCapped(InputStream in, ByteArrayOutputStream out, AtomicBoolean truncated) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) != -1) {
                int room = MAX_STDOUT_BYTES - out.size();
                if (room > 0) {
                    out.write(buf, 0, Math.min(n, room));
                }
                if (n > room) {
                    truncated.set(true); // keep draining so the child never blocks on a full pipe
                }
            }
        } catch (IOException ignored) {
            // stream closes when the process is killed
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var files = Files.walk(dir)) {
            files.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir; the OS cleans it up eventually
        }
    }
}
