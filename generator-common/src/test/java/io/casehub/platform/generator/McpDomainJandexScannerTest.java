package io.casehub.platform.generator;

import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpDomainJandexScannerTest {

    private static Index index;

    @BeforeAll
    static void buildIndex() throws Exception {
        var indexer = new Indexer();
        indexer.indexClass(SampleMcpDomain.class);
        index = indexer.complete();
    }

    @Test
    void scansDomainName() {
        var scanner = new McpDomainJandexScanner();
        List<DomainScanResult> results = scanner.scan(index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).domainName()).isEqualTo("test-domain");
        assertThat(results.get(0).declaringTypeSimple()).isEqualTo("SampleMcpDomain");
    }

    @Test
    void scansQueryAndMutation() {
        var scanner = new McpDomainJandexScanner();
        DomainScanResult domain = scanner.scan(index).get(0);

        ResolvedOperation query = domain.operations().stream()
                .filter(o -> o.methodName().equals("listItems")).findFirst().orElseThrow();
        assertThat(query.type()).isEqualTo(OperationType.QUERY);
        assertThat(query.description()).isEqualTo("List all items");

        ResolvedOperation mutation = domain.operations().stream()
                .filter(o -> o.methodName().equals("createItem")).findFirst().orElseThrow();
        assertThat(mutation.type()).isEqualTo(OperationType.MUTATION);
    }

    @Test
    void scansStreamMethods() {
        var scanner = new McpDomainJandexScanner();
        DomainScanResult domain = scanner.scan(index).get(0);

        ResolvedOperation stream = domain.operations().stream()
                .filter(o -> o.methodName().equals("watchItems")).findFirst().orElseThrow();
        assertThat(stream.type()).isEqualTo(OperationType.STREAM);
        assertThat(stream.description()).isEqualTo("Watch item changes");
    }

    @Test
    void scansRolesAllowed() {
        var scanner = new McpDomainJandexScanner();
        DomainScanResult domain = scanner.scan(index).get(0);

        ResolvedOperation mutation = domain.operations().stream()
                .filter(o -> o.methodName().equals("createItem")).findFirst().orElseThrow();
        assertThat(mutation.rolesAllowed()).containsExactly("admin");
    }

    @Test
    void scansRestNameOnParams() {
        var scanner = new McpDomainJandexScanner();
        DomainScanResult domain = scanner.scan(index).get(0);

        ResolvedOperation query = domain.operations().stream()
                .filter(o -> o.methodName().equals("listItems")).findFirst().orElseThrow();
        ResolvedParam pageSize = query.params().stream()
                .filter(p -> p.name().equals("pageSize")).findFirst().orElseThrow();
        assertThat(pageSize.restName()).isEqualTo("page_size");
    }

    @Test
    void scansPathParam() {
        var scanner = new McpDomainJandexScanner();
        DomainScanResult domain = scanner.scan(index).get(0);

        ResolvedOperation getItem = domain.operations().stream()
                .filter(o -> o.methodName().equals("getItem")).findFirst().orElseThrow();
        ResolvedParam id = getItem.params().get(0);
        assertThat(id.isPathParam()).isTrue();
        assertThat(id.pathParamName()).isEqualTo("id");
    }

    @Test
    void scansRestStatusOverride() {
        var scanner = new McpDomainJandexScanner();
        DomainScanResult domain = scanner.scan(index).get(0);

        ResolvedOperation createItem = domain.operations().stream()
                .filter(o -> o.methodName().equals("createItem")).findFirst().orElseThrow();
        assertThat(createItem.restStatusOverride()).isEqualTo(201);
    }

    @Test
    void scansPaginatedResponse() {
        var scanner = new McpDomainJandexScanner();
        DomainScanResult domain = scanner.scan(index).get(0);

        ResolvedOperation listItems = domain.operations().stream()
                .filter(o -> o.methodName().equals("listItems")).findFirst().orElseThrow();
        assertThat(listItems.paginated()).isTrue();
    }

    @Test
    void extractsReturnTypes() {
        var scanner = new McpDomainJandexScanner();
        DomainScanResult domain = scanner.scan(index).get(0);

        ResolvedOperation listItems = domain.operations().stream()
                .filter(o -> o.methodName().equals("listItems")).findFirst().orElseThrow();
        assertThat(listItems.returnTypeName().toString()).isEqualTo("java.util.List<java.lang.String>");
    }
}
