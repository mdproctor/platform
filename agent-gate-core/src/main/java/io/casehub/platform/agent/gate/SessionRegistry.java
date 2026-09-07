package io.casehub.platform.agent.gate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SessionRegistry {

    private final AtomicLong idCounter = new AtomicLong();
    private final ConcurrentHashMap<Long, TrackedSession> sessions = new ConcurrentHashMap<>();

    public record TrackedSession(long id, GatedAgentSession session, Instant createdAt) {}

    public long nextId() {
        return idCounter.incrementAndGet();
    }

    public long register(GatedAgentSession session) {
        long id = nextId();
        sessions.put(id, new TrackedSession(id, session, Instant.now()));
        return id;
    }

    public void register(long id, GatedAgentSession session) {
        sessions.put(id, new TrackedSession(id, session, Instant.now()));
    }

    public void registerWithTimestamp(long id, GatedAgentSession session, Instant createdAt) {
        sessions.put(id, new TrackedSession(id, session, createdAt));
    }

    public void deregister(long id) {
        sessions.remove(id);
    }

    public Map<Long, TrackedSession> snapshot() {
        return Map.copyOf(sessions);
    }
}
