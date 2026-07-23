package io.casehub.platform.persistence.jpa;

import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.PreferenceQuery;
import io.casehub.platform.api.preferences.PreferenceRecord;
import io.casehub.platform.api.preferences.PreferenceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class JpaPreferenceStoreTest {

    private static final String TENANT = TenancyConstants.DEFAULT_TENANT_ID;

    @Inject PreferenceStore store;

    @BeforeEach
    @Transactional
    void clear() {
        PreferenceEntry.deleteAll();
    }

    @Test
    @Transactional
    void set_inserts_new_preference() {
        store.set(TENANT, Path.of("casehubio"), "test", "count", "", "42");

        List<PreferenceRecord> records = store.list(new PreferenceQuery(TENANT, Path.of("casehubio"), "test"));
        assertEquals(1, records.size());
        assertEquals("42", records.get(0).value());
        assertEquals("count", records.get(0).name());
    }

    @Test
    @Transactional
    void set_upserts_existing_preference() {
        store.set(TENANT, Path.of("casehubio"), "test", "count", "", "42");
        store.set(TENANT, Path.of("casehubio"), "test", "count", "", "99");

        List<PreferenceRecord> records = store.list(new PreferenceQuery(TENANT, Path.of("casehubio"), "test"));
        assertEquals(1, records.size());
        assertEquals("99", records.get(0).value());
    }

    @Test
    @Transactional
    void delete_removes_specific_preference() {
        store.set(TENANT, Path.of("casehubio"), "test", "count", "", "42");
        store.set(TENANT, Path.of("casehubio"), "test", "other", "", "7");

        store.delete(TENANT, Path.of("casehubio"), "test", "count", "");

        List<PreferenceRecord> records = store.list(new PreferenceQuery(TENANT, Path.of("casehubio"), "test"));
        assertEquals(1, records.size());
        assertEquals("other", records.get(0).name());
    }

    @Test
    @Transactional
    void deleteAll_removes_all_in_namespace_at_scope() {
        store.set(TENANT, Path.of("casehubio"), "test", "a", "", "1");
        store.set(TENANT, Path.of("casehubio"), "test", "b", "", "2");
        store.set(TENANT, Path.of("casehubio"), "other", "c", "", "3");

        store.deleteAll(TENANT, Path.of("casehubio"), "test");

        assertEquals(0, store.list(new PreferenceQuery(TENANT, Path.of("casehubio"), "test")).size());
        assertEquals(1, store.list(new PreferenceQuery(TENANT, Path.of("casehubio"), "other")).size());
    }

    @Test
    @Transactional
    void deleteAll_does_not_cascade_to_child_scopes() {
        store.set(TENANT, Path.of("casehubio"), "test", "a", "", "1");
        store.set(TENANT, Path.of("casehubio", "devtown"), "test", "a", "", "2");

        store.deleteAll(TENANT, Path.of("casehubio"), "test");

        assertEquals(0, store.list(new PreferenceQuery(TENANT, Path.of("casehubio"), "test")).size());
        assertEquals(1, store.list(new PreferenceQuery(TENANT, Path.of("casehubio", "devtown"), "test")).size());
    }

    @Test
    @Transactional
    void list_returns_empty_for_unknown_scope() {
        List<PreferenceRecord> records = store.list(new PreferenceQuery(TENANT, Path.of("nonexistent"), "test"));
        assertTrue(records.isEmpty());
    }

    @Test
    @Transactional
    void tenant_isolation_prevents_cross_tenant_reads() {
        store.set(TENANT, Path.of("casehubio"), "test", "count", "", "42");

        List<PreferenceRecord> records = store.list(new PreferenceQuery("other-tenant", Path.of("casehubio"), "test"));
        assertTrue(records.isEmpty());
    }

    @Test
    @Transactional
    void set_with_subkey_for_multi_value_preferences() {
        store.set(TENANT, Path.of("casehubio"), "test", "multi", "key1", "val1");
        store.set(TENANT, Path.of("casehubio"), "test", "multi", "key2", "val2");

        List<PreferenceRecord> records = store.list(new PreferenceQuery(TENANT, Path.of("casehubio"), "test"));
        assertEquals(2, records.size());
    }
}
