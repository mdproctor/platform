package io.casehub.platform.registry.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "registry_cascade_rule")
public class CascadeRuleEntity {

    @Id
    @Column(name = "relationship_type")
    public String relationshipType;

    @Column(name = "on_source_deregister", nullable = false, length = 30)
    public String onSourceDeregister;
}
