package io.casehub.platform.persistence.mongodb;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceChanged;
import io.casehub.platform.api.preferences.PreferencePermissions;
import io.casehub.platform.api.preferences.PreferenceQuery;
import io.casehub.platform.api.preferences.PreferenceRecord;
import io.casehub.platform.api.preferences.PreferenceStore;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
@Alternative
@Priority(1)
public class MongoPreferenceStore implements PreferenceStore {

    @Inject CurrentPrincipal principal;
    @Inject Event<PreferenceChanged> changedEvent;

    @Override
    public void set(String tenancyId, Path scope, String namespace, String name, String subKey, String value) {
        PreferencePermissions.assertTenant(tenancyId, principal);
        String id = MongoPreferenceDocument.compoundId(tenancyId, scope.value(), namespace, name, subKey);
        MongoPreferenceDocument existing = MongoPreferenceDocument.findById(id);
        if (existing != null) {
            existing.value = value;
            existing.update();
        } else {
            MongoPreferenceDocument doc = new MongoPreferenceDocument();
            doc.id = id;
            doc.tenancyId = tenancyId;
            doc.scope = scope.value();
            doc.namespace = namespace;
            doc.name = name;
            doc.subKey = subKey;
            doc.value = value;
            doc.persist();
        }
        changedEvent.fireAsync(new PreferenceChanged(tenancyId, scope, namespace));
    }

    @Override
    public void delete(String tenancyId, Path scope, String namespace, String name, String subKey) {
        PreferencePermissions.assertTenant(tenancyId, principal);
        String id = MongoPreferenceDocument.compoundId(tenancyId, scope.value(), namespace, name, subKey);
        MongoPreferenceDocument.deleteById(id);
        changedEvent.fireAsync(new PreferenceChanged(tenancyId, scope, namespace));
    }

    @Override
    public List<PreferenceRecord> list(PreferenceQuery query) {
        List<MongoPreferenceDocument> docs;
        if (query.scope() != null && query.namespace() != null) {
            docs = MongoPreferenceDocument.list("tenancyId = ?1 and scope = ?2 and namespace = ?3",
                    query.tenancyId(), query.scope().value(), query.namespace());
        } else if (query.scope() != null) {
            docs = MongoPreferenceDocument.list("tenancyId = ?1 and scope = ?2",
                    query.tenancyId(), query.scope().value());
        } else {
            docs = MongoPreferenceDocument.list("tenancyId = ?1", query.tenancyId());
        }
        return docs.stream()
                .map(d -> new PreferenceRecord(d.tenancyId, pathFromStored(d.scope), d.namespace, d.name, d.subKey, d.value))
                .toList();
    }

    @Override
    public void deleteAll(String tenancyId, Path scope, String namespace) {
        PreferencePermissions.assertTenant(tenancyId, principal);
        MongoPreferenceDocument.delete("tenancyId = ?1 and scope = ?2 and namespace = ?3",
                tenancyId, scope.value(), namespace);
        changedEvent.fireAsync(new PreferenceChanged(tenancyId, scope, namespace));
    }

    private static Path pathFromStored(String stored) {
        return stored.isEmpty() ? Path.root() : Path.parse(stored);
    }
}
