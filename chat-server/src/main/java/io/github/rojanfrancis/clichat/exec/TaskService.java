package io.github.rojanfrancis.clichat.exec;

import io.github.rojanfrancis.clichat.intent.IntentService;
import io.github.rojanfrancis.clichat.intent.TaskIntent;
import io.github.rojanfrancis.clichat.result.ResultProcessor;
import io.github.rojanfrancis.clichat.result.TaskOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * The orchestration layer: message → intent → approved CLI command → supervised run →
 * validated outcome. Progress is published as events: plan, started, log, result, done.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    public record Submission(String taskId, TaskIntent intent, String reply) {}

    public static class BusyException extends RuntimeException {
        public BusyException() {
            super("The server is already running the maximum number of tasks. Try again in a few seconds.");
        }
    }

    private final IntentService intents;
    private final CrawlerCommand command;
    private final CliRunner runner;
    private final ResultProcessor results;
    private final Duration timeout;
    private final Semaphore slots;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TaskService(IntentService intents, CrawlerCommand command, CliRunner runner, ResultProcessor results,
                       @Value("${crawler.process-timeout-seconds:75}") int timeoutSeconds,
                       @Value("${crawler.max-concurrent:2}") int maxConcurrent) {
        this.intents = intents;
        this.command = command;
        this.runner = runner;
        this.results = results;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.slots = new Semaphore(maxConcurrent);
    }

    public Submission submit(String message) {
        TaskIntent intent = intents.interpret(message);
        if (!intent.runsCli()) {
            return new Submission(null, intent, intent.reply());
        }
        if (!slots.tryAcquire()) {
            throw new BusyException();
        }
        Task task = new Task(UUID.randomUUID().toString());
        tasks.put(task.id(), task);
        List<String> argv = command.build(intent);
        String display = CrawlerCommand.display(argv);

        task.emit("plan", plan(intent, display));
        executor.submit(() -> {
            try {
                execute(task, intent, argv, display);
            } finally {
                slots.release();
                task.finish();
            }
        });
        return new Submission(task.id(), intent, null);
    }

    private void execute(Task task, TaskIntent intent, List<String> argv, String display) {
        if (!command.isAvailable()) {
            task.emit("result", Map.of("status", "error", "headline",
                    "The crawler CLI is not installed on the server (expected at " + command.jar().getFileName() + ")."));
            return;
        }
        task.emit("started", Map.of("command", display, "timeoutSeconds", timeout.toSeconds(), "at", Instant.now().toString()));
        try {
            ExecutionResult exec = runner.run(argv, display, timeout, line -> task.emit("log", line), task.handle());
            log.info("task {} finished: exit {} in {} ms", task.id(), exec.exitCode(), exec.durationMs());
            TaskOutcome outcome = results.process(intent, exec);
            task.emit("result", outcome);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.emit("result", Map.of("status", "error", "headline", "The task was interrupted."));
        } catch (Exception e) {
            log.warn("task {} could not run the CLI: {}", task.id(), e.toString());
            task.emit("result", Map.of("status", "error", "headline",
                    "The CLI could not be started: " + e.getClass().getSimpleName()));
        }
    }

    private static Map<String, Object> plan(TaskIntent intent, String display) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("action", "crawl_site");
        plan.put("url", intent.url());
        plan.put("maxPages", intent.maxPages());
        plan.put("maxDepth", intent.maxDepth());
        plan.put("wantSummary", intent.wantSummary());
        plan.put("wantJson", intent.wantJson());
        if (intent.focus() != null) plan.put("focus", intent.focus());
        plan.put("interpretedBy", intent.interpretedBy());
        plan.put("command", display);
        plan.put("steps", List.of(
                "Validate the URL and limits",
                "Run the sitecrawl CLI as a separate process (no shell)",
                "Stream its log and capture stdout, stderr and the exit code",
                "Check the JSON report against the exit code",
                (intent.wantSummary() ? "Summarise the pages and " : "") + "return structured JSON"));
        return plan;
    }

    public Optional<Task> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public boolean cancel(String id) {
        Task task = tasks.get(id);
        if (task == null || task.isDone()) {
            return false;
        }
        task.handle().cancel();
        task.emit("log", "[server] cancel requested, stopping the CLI process");
        return true;
    }

    /** Forget finished tasks after ten minutes. */
    @Scheduled(fixedDelay = 60_000)
    void evictOldTasks() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        tasks.values().removeIf(t -> t.isDone() && t.createdAt().isBefore(cutoff));
    }
}
