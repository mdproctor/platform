package io.casehub.platform.persistence.jpa;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA-backed {@link PreferenceProvider}.
 *
 * <p>Resolves preferences by walking the scope hierarchy from root to most specific —
 * child scopes override parent scopes. {@code effectiveAt} from {@link SettingsScope}
 * is intentionally ignored: all rows are considered current. Time-travel support
 * requires a versioned write model (preferences-editor, issue #8).
 *
 * <p>{@code @ApplicationScoped} (no {@code @DefaultBean}) displaces {@code MockPreferenceProvider}
 * automatically when {@code casehub-platform-persistence-jpa} is on the classpath.
 *
 * <p>Consumers must add {@code classpath:db/platform/migration} to
 * {@code quarkus.flyway.locations}.
 */
@ApplicationScoped
public class JpaPreferenceProvider implements PreferenceProvider {

    /**
     * Returns ancestor scope strings shortest-first (root first), ending with the target scope.
     */
    private static List<String> ancestors(final Path path) {
        final List<String> result  = new ArrayList<>();
        Path               current = path;
        while (current != null) {
            result.add(0, current.value());
            current = current.parent();
        }
        // Root scope ("") is always the base ancestor — preferences stored there apply universally.
        // Path.parent() returns null for single-segment paths (not root), so root is not naturally
        // reached by the walk above for non-root paths.
        if (path.depth() > 0) {
            result.add(0, Path.root().value());
        }
        return result;
    }

    @Override
    @Transactional(TxType.SUPPORTS)
    public Preferences resolve(final SettingsScope scope) {
        final List<String>          ancestors = ancestors(scope.scope());
        final List<PreferenceEntry> rows      = PreferenceEntry.findByScopes(scope.tenancyId(), ancestors);

        final Map<String, Integer> scopeOrder = new HashMap<>();
        for (int i = 0; i < ancestors.size(); i++) {
            scopeOrder.put(ancestors.get(i), i);
        }
        rows.sort((a, b) -> Integer.compare(
                scopeOrder.getOrDefault(a.scope, -1),
                scopeOrder.getOrDefault(b.scope, -1)));

        final Map<String, Object> merged = new HashMap<>();
        for (final PreferenceEntry row : rows) {
            final String mapKey = row.subKey.isEmpty()
                                  ? row.namespace + "." + row.name
                                  : row.namespace + "." + row.name + "." + row.subKey;
            merged.put(mapKey, row.value);
        }

        return new MapPreferences(merged);
    }
}
