package io.casehub.platform.view;

import io.casehub.platform.api.view.SubjectViewSpec;
import io.casehub.platform.api.view.SubjectViewStore;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class NoOpSubjectViewStore implements SubjectViewStore {
    @Override public SubjectViewSpec save(SubjectViewSpec spec) { return spec; }
    @Override public Optional<SubjectViewSpec> findById(UUID id) { return Optional.empty(); }
    @Override public List<SubjectViewSpec> findByTenancy(String tenancyId) { return List.of(); }
    @Override public boolean delete(UUID id) { return false; }
}
