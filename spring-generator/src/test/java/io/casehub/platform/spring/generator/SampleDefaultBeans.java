package io.casehub.platform.spring.generator;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
class SampleDefaultBeans {

    @Produces
    @DefaultBean
    @ApplicationScoped
    public SampleNoOp noOpBean() {
        return new SampleNoOp();
    }
}
