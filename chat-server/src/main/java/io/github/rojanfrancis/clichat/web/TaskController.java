package io.github.rojanfrancis.clichat.web;

import io.github.rojanfrancis.clichat.ai.GeminiClient;
import io.github.rojanfrancis.clichat.exec.CrawlerCommand;
import io.github.rojanfrancis.clichat.exec.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TaskController {

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {}

    private final TaskService tasks;
    private final CrawlerCommand command;
    private final GeminiClient gemini;

    public TaskController(TaskService tasks, CrawlerCommand command, GeminiClient gemini) {
        this.tasks = tasks;
        this.command = command;
        this.gemini = gemini;
    }

    /** Interprets the message; if it is a crawl task, starts the CLI and returns the task id. */
    @PostMapping("/tasks")
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody ChatRequest request) {
        TaskService.Submission s = tasks.submit(request.message());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", s.taskId());
        body.put("action", s.intent().action());
        body.put("interpretedBy", s.intent().interpretedBy());
        if (s.reply() != null) {
            body.put("reply", s.reply());
        }
        return ResponseEntity.status(s.taskId() == null ? HttpStatus.OK : HttpStatus.ACCEPTED).body(body);
    }

    /** Server-sent events for one task: plan, started, log (one per CLI stderr line), result. */
    @GetMapping(path = "/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable String id) {
        return tasks.find(id).map(task -> {
            SseEmitter emitter = new SseEmitter(120_000L);
            task.subscribe(emitter);
            return ResponseEntity.ok(emitter);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
        boolean cancelled = tasks.cancel(id);
        return ResponseEntity.status(cancelled ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                .body(Map.of("cancelled", cancelled));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", command.isAvailable() ? "ok" : "crawler-missing",
                "crawlerCli", command.isAvailable() ? "installed" : "missing",
                "llm", gemini.isConfigured() ? "gemini (" + gemini.model() + ")" : "off (rule-based parser)");
    }

    @ExceptionHandler(TaskService.BusyException.class)
    public ResponseEntity<Map<String, Object>> busy(TaskService.BusyException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Type a message (up to 2,000 characters)."));
    }
}
