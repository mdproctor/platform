package io.casehub.platform.registry.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "registry_entry",
       indexes = {
               @Index(name = "idx_reg_type", columnList = "type"),
               @Index(name = "idx_reg_namespace", columnList = "namespace"),
               @Index(name = "idx_reg_tenancy", columnList = "tenancy_id"),
               @Index(name = "idx_reg_health", columnList = "health")
       })
public class RegistryEntryEntity {

    @Id
    public String id;

    @Column(nullable = false)
    public String type;

    @Column(nullable = false)
    public String namespace;

    @Column(name = "tenancy_id", nullable = false)
    public String tenancyId;

    @Column(columnDefinition = "TEXT")
    public String metadata;

    @Column(name = "registered_at", nullable = false)
    public Instant registeredAt;

    @Column(name = "last_heartbeat")
    public Instant lastHeartbeat;

    @Column(name = "ttl_seconds", nullable = false)
    public long ttlSeconds;

    @Column(nullable = false, length = 20)
    public String health;
}
