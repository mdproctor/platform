package io.casehub.platform.persistence.jpa;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceChanged;
import io.casehub.platform.api.preferences.PreferencePermissions;
import io.casehub.platform.api.preferences.PreferenceQuery;
import io.casehub.platform.api.preferences.PreferenceRecord;
import io.casehub.platform.api.preferences.PreferenceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class JpaPreferenceStore implements PreferenceStore {

    @Inject CurrentPrincipal principal;
    @Inject Event<PreferenceChanged> changedEvent;

    @Override
    @Transactional
    public void set(String tenancyId, Path scope, String namespace, String name, String subKey, String value) {
        PreferencePermissions.assertTenant(tenancyId, principal);
        String scopeValue = scope.value();
        PreferenceEntry existing = PreferenceEntry.find(
                "tenancyId = ?1 and scope = ?2 and namespace = ?3 and name = ?4 and subKey = ?5",
                tenancyId, scopeValue, namespace, name, subKey).firstResult();
        if (existing != null) {
            existing.value = value;
        } else {
            PreferenceEntry entry = new PreferenceEntry();
            entry.tenancyId = tenancyId;
            entry.scope = scopeValue;
            entry.namespace = namespace;
            entry.name = name;
            entry.subKey = subKey;
            entry.value = value;
            entry.persist();
        }
        changedEvent.fireAsync(new PreferenceChanged(tenancyId, scope, namespace));
    }

    @Override
    @Transactional
    public void delete(String tenancyId, Path scope, String namespace, String name, String subKey) {
        PreferencePermissions.assertTenant(tenancyId, principal);
        PreferenceEntry.delete(
                "tenancyId = ?1 and scope = ?2 and namespace = ?3 and name = ?4 and subKey = ?5",
                tenancyId, scope.value(), namespace, name, subKey);
        changedEvent.fireAsync(new PreferenceChanged(tenancyId, scope, namespace));
    }

    @Override
    public List<PreferenceRecord> list(PreferenceQuery query) {
        String scopeValue = query.scope() != null ? query.scope().value() : null;
        List<PreferenceEntry> entries;
        if (scopeValue != null && query.namespace() != null) {
            entries = PreferenceEntry.list("tenancyId = ?1 and scope = ?2 and namespace = ?3",
                    query.tenancyId(), scopeValue, query.namespace());
        } else if (scopeValue != null) {
            entries = PreferenceEntry.list("tenancyId = ?1 and scope = ?2",
                    query.tenancyId(), scopeValue);
        } else if (query.namespace() != null) {
            entries = PreferenceEntry.list("tenancyId = ?1 and namespace = ?2",
                    query.tenancyId(), query.namespace());
        } else {
            entries = PreferenceEntry.list("tenancyId = ?1", query.tenancyId());
        }
        return entries.stream()
                .map(e -> new PreferenceRecord(e.tenancyId, pathFromStored(e.scope), e.namespace, e.name, e.subKey, e.value))
                .toList();
    }

    @Override
    @Transactional
    public void deleteAll(String tenancyId, Path scope, String namespace) {
        PreferencePermissions.assertTenant(tenancyId, principal);
        PreferenceEntry.delete("tenancyId = ?1 and scope = ?2 and namespace = ?3",
                tenancyId, scope.value(), namespace);
        changedEvent.fireAsync(new PreferenceChanged(tenancyId, scope, namespace));
    }

    private static Path pathFromStored(String stored) {
        return stored.isEmpty() ? Path.root() : Path.parse(stored);
    }
}
