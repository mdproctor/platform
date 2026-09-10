package io.casehub.platform.spring.generator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
class SampleBeans {

    @Produces
    @ApplicationScoped
    public SampleService simpleBean() {
        return new SampleService();
    }

    @Produces
    @ApplicationScoped
    public SampleOrchestrator beanWithDeps(SampleService service, SampleStore store) {
        return new SampleOrchestrator(service, store);
    }
}

class SampleService {}
class SampleOrchestrator {
    SampleOrchestrator(SampleService service, SampleStore store) {}
}
class SampleStore {}
class SampleNoOp {}
