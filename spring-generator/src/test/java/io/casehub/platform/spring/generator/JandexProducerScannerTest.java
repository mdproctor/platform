package io.casehub.platform.spring.generator;

import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JandexProducerScannerTest {

    @Test
    void findsProducesMethodsWithReturnTypesAndParameters() throws IOException {
        Index index = indexClass(SampleBeans.class);
        var scanner = new JandexProducerScanner();

        List<ProducerDescriptor> descriptors = scanner.scan(index);

        assertThat(descriptors).hasSize(2);

        var simple = descriptors.stream()
                .filter(d -> d.methodName().equals("simpleBean"))
                .findFirst().orElseThrow();
        assertThat(simple.returnType()).endsWith("SampleService");
        assertThat(simple.parameters()).isEmpty();
        assertThat(simple.defaultBean()).isFalse();

        var withDeps = descriptors.stream()
                .filter(d -> d.methodName().equals("beanWithDeps"))
                .findFirst().orElseThrow();
        assertThat(withDeps.returnType()).endsWith("SampleOrchestrator");
        assertThat(withDeps.parameters()).hasSize(2);
        assertThat(withDeps.parameters().get(0).type()).endsWith("SampleService");
    }

    @Test
    void detectsDefaultBeanAnnotation() throws IOException {
        Index index = indexClass(SampleDefaultBeans.class);
        var scanner = new JandexProducerScanner();

        List<ProducerDescriptor> descriptors = scanner.scan(index);

        assertThat(descriptors).hasSize(1);
        assertThat(descriptors.get(0).defaultBean()).isTrue();
        assertThat(descriptors.get(0).returnType()).endsWith("SampleNoOp");
    }

    @Test
    void detectsAlternativeWithPriority() throws IOException {
        Index index = indexClass(SampleAlternativeBeans.class);
        var scanner = new JandexProducerScanner();

        List<ProducerDescriptor> descriptors = scanner.scan(index);

        assertThat(descriptors).hasSize(1);
        assertThat(descriptors.get(0).alternative()).isTrue();
        assertThat(descriptors.get(0).priority()).isEqualTo(100);
    }

    private Index indexClass(Class<?> clazz) throws IOException {
        Indexer indexer = new Indexer();
        String resourceName = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            indexer.index(is);
        }
        return indexer.complete();
    }
}
