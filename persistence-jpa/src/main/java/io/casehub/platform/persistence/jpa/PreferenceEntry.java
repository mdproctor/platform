package io.casehub.platform.persistence.jpa;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Collections;
import java.util.List;

@Entity
@Table(
        name = "platform_preference",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_platform_preference",
                columnNames = {"tenancy_id", "scope", "namespace", "pref_name", "sub_key"}),
        indexes = @Index(name = "idx_platform_preference_scope", columnList = "scope")
)
public class PreferenceEntry extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "platform_preference_seq")
    @SequenceGenerator(name = "platform_preference_seq", sequenceName = "platform_preference_seq",
                       allocationSize = 50)
    public Long id;

    @Column(name = "tenancy_id", nullable = false, length = 100)
    public String tenancyId;

    @Column(nullable = false, length = 500)
    public String scope;

    @Column(nullable = false, length = 100)
    public String namespace;

    @Column(name = "pref_name", nullable = false, length = 100)
    public String name;

    /**
     * Empty string for single-value preferences; sub-key for multi-value.
     */
    @Column(name = "sub_key", nullable = false, length = 100)
    public String subKey = "";

    @Column(name = "pref_value", nullable = false, length = 4000)
    public String value;

    static List<PreferenceEntry> findByScopes(final String tenancyId, final List<String> scopes) {
        if (scopes.isEmpty()) {return Collections.emptyList();}
        return list("tenancyId = ?1 and scope in ?2", tenancyId, scopes);
    }
}
