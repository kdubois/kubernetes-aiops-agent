package dev.kevindubois.rollout.agent.model;

import java.time.Instant;

public record ActivityEvent(
    long id,
    Instant timestamp,
    String type,
    String message,
    String details
) {
    public ActivityEvent(long id, String type, String message) {
        this(id, Instant.now(), type, message, null);
    }

    public ActivityEvent(long id, String type, String message, String details) {
        this(id, Instant.now(), type, message, details);
    }
}
