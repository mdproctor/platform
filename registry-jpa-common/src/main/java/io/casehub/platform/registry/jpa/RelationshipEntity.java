package io.casehub.platform.registry.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "registry_relationship",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_reg_rel",
               columnNames = {"source_id", "target_id"}),
       indexes = {
               @Index(name = "idx_rel_source", columnList = "source_id"),
               @Index(name = "idx_rel_target", columnList = "target_id")
       })
public class RelationshipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reg_rel_seq")
    @SequenceGenerator(name = "reg_rel_seq", sequenceName = "registry_relationship_seq", allocationSize = 50)
    public Long id;

    @Column(name = "source_id", nullable = false)
    public String sourceId;

    @Column(name = "target_id", nullable = false)
    public String targetId;

    @Column(nullable = false)
    public String type;
}
