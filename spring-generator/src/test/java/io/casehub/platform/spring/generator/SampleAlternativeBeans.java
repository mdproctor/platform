package io.casehub.platform.spring.generator;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
class SampleAlternativeBeans {

    @Produces
    @Alternative
    @Priority(100)
    @ApplicationScoped
    public SampleService alternativeBean() {
        return new SampleService();
    }
}
