package io.casehub.platform.callback.inmem;

import io.casehub.platform.api.callback.CallbackRegistration;
import io.casehub.platform.api.callback.CallbackRegistry;
import io.casehub.platform.api.callback.CallbackRegistrationRequest;
import io.casehub.platform.api.util.UUIDv7;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCallbackRegistry implements CallbackRegistry {

    private final Map<String, CallbackRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<String, String> upsertKeys = new ConcurrentHashMap<>();

    @Override
    public CallbackRegistration register(CallbackRegistrationRequest request) {
        String upsertKey = request.spiName() + "|" + request.callbackUrl() + "|" + request.tenancyId();
        String existingId = upsertKeys.get(upsertKey);

        Instant now = Instant.now();
        String id = existingId != null ? existingId : UUIDv7.generate();

        var reg = new CallbackRegistration(
                id, request.spiName(), request.callbackUrl(),
                request.credentialRef(), request.tenancyId(),
                request.timeoutMs(), request.metadata(),
                now, now.plusSeconds(request.ttlSeconds()), now);

        registrations.put(id, reg);
        upsertKeys.put(upsertKey, id);
        return reg;
    }

    @Override
    public void deregister(String registrationId) {
        var removed = registrations.remove(registrationId);
        if (removed != null) {
            String key = removed.spiName() + "|" + removed.callbackUrl() + "|" + removed.tenancyId();
            upsertKeys.remove(key);
        }
    }

    @Override
    public void heartbeat(String registrationId) {
        registrations.computeIfPresent(registrationId, (id, reg) -> {
            long ttlSeconds = reg.expiresAt().getEpochSecond() - reg.registeredAt().getEpochSecond();
            Instant now = Instant.now();
            return new CallbackRegistration(
                    reg.id(), reg.spiName(), reg.callbackUrl(),
                    reg.credentialRef(), reg.tenancyId(),
                    reg.timeoutMs(), reg.metadata(),
                    reg.registeredAt(), now.plusSeconds(ttlSeconds), now);
        });
    }

    @Override
    public List<CallbackRegistration> findBySpi(String spiName, String tenancyId) {
        Instant now = Instant.now();
        return registrations.values().stream()
                .filter(r -> r.spiName().equals(spiName))
                .filter(r -> r.tenancyId().equals(tenancyId))
                .filter(r -> r.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(CallbackRegistration::registeredAt))
                .toList();
    }

    @Override
    public Optional<CallbackRegistration> findById(String registrationId) {
        return Optional.ofNullable(registrations.get(registrationId));
    }
}
