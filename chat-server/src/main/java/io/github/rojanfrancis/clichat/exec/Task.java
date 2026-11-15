package io.github.rojanfrancis.clichat.exec;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One chat task. Events are buffered so a browser that connects a moment after the task
 * starts still receives everything from the beginning.
 */
public class Task {

    public record Event(String type, Object data) {}

    private final String id;
    private final Instant createdAt = Instant.now();
    private final List<Event> events = new ArrayList<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final CliRunner.Handle handle = new CliRunner.Handle();
    private boolean done;

    public Task(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public CliRunner.Handle handle() {
        return handle;
    }

    public synchronized boolean isDone() {
        return done;
    }

    // emit, finish and subscribe share one lock so a late subscriber gets the backlog
    // and then the live events exactly once, in order.
    public synchronized void emit(String type, Object data) {
        Event event = new Event(type, data);
        events.add(event);
        for (SseEmitter emitter : emitters) {
            send(emitter, event);
        }
    }

    public synchronized void finish() {
        done = true;
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
    }

    public synchronized void subscribe(SseEmitter emitter) {
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        for (Event e : events) {
            send(emitter, e);
        }
        if (done) {
            emitter.complete();
        } else {
            emitters.add(emitter);
        }
    }

    private void send(SseEmitter emitter, Event event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
        } catch (IOException | IllegalStateException e) {
            emitters.remove(emitter);
        }
    }
}
