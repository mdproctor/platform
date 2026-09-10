package io.casehub.platform.view;

import io.casehub.platform.api.view.CrossTenantSubjectViewStore;

import java.util.List;

public class NoOpCrossTenantSubjectViewStore implements CrossTenantSubjectViewStore {
    @Override public List<String> findDistinctTenancyIds() { return List.of(); }
}
