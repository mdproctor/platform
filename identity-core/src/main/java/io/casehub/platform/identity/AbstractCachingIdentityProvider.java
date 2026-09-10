package io.casehub.platform.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractCachingIdentityProvider<C> {

    private record CacheEntry<C>(Optional<C> value, Instant expiresAt) {
        boolean isExpired(Instant now) { return now.isAfter(expiresAt); }
    }

    private final ConcurrentHashMap<String, CacheEntry<C>> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    protected AbstractCachingIdentityProvider(Duration ttl) {
        this.ttl = ttl;
    }

    public final Optional<C> get(String key) {
        Instant now = now();
        CacheEntry<C> existing = cache.get(key);
        if (existing != null && existing.isExpired(now)) {
            cache.remove(key, existing);
            existing = null;
        }
        if (existing == null) {
            Optional<C> loaded = loadContext(key);
            CacheEntry<C> fresh = new CacheEntry<>(loaded, now.plus(ttl));
            CacheEntry<C> racing = cache.putIfAbsent(key, fresh);
            existing = racing != null ? racing : fresh;
        }
        return existing.value();
    }

    public final void put(String key, Optional<C> value) {
        cache.put(key, new CacheEntry<>(value, now().plus(ttl)));
    }

    protected abstract Optional<C> loadContext(String key);

    protected Instant now() { return Instant.now(); }

    public void invalidate(String key) { cache.remove(key); }
    public void invalidateAll() { cache.clear(); }
}
