package dev.kevindubois.rollout.agent.model;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class ActivityEventStore {

    private static final int MAX_EVENTS = 100;
    private final List<ActivityEvent> events = new ArrayList<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    public synchronized ActivityEvent publish(String type, String message) {
        return publish(type, message, null);
    }

    public synchronized ActivityEvent publish(String type, String message, String details) {
        ActivityEvent event = new ActivityEvent(idCounter.incrementAndGet(), type, message, details);
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        return event;
    }

    public synchronized List<ActivityEvent> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized List<ActivityEvent> getEventsSince(long lastId) {
        return events.stream()
            .filter(e -> e.id() > lastId)
            .toList();
    }

    public synchronized void clear() {
        events.clear();
    }
}
