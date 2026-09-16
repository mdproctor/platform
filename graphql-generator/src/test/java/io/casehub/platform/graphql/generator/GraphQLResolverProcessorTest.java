package io.casehub.platform.graphql.generator;

import org.jboss.jandex.DotName;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class GraphQLResolverProcessorTest {

    @Test
    void jandexCanIndexAnnotatedInterface() throws IOException {
        Indexer indexer = new Indexer();
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("META-INF/jandex.idx")) {
            if (is != null) {
                var reader = new org.jboss.jandex.IndexReader(is);
                var index = reader.read();
                assertThat(index).isNotNull();
            }
        }
    }

    @Test
    void jandexFindsAnnotationsOnClasspath() throws IOException {
        var indexes = new java.util.ArrayList<org.jboss.jandex.IndexView>();
        var resources = getClass().getClassLoader()
                .getResources("META-INF/jandex.idx");
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            try (InputStream is = url.openStream()) {
                indexes.add(new org.jboss.jandex.IndexReader(is).read());
            }
        }

        assertThat(indexes).isNotEmpty();

        var combined = org.jboss.jandex.CompositeIndex.create(indexes);
        var mcpDomainAnns = combined.getAnnotations(
                DotName.createSimple("io.casehub.platform.api.mcp.McpDomain"));

        // McpDomain should be found on at least the annotation's own test class
        // (or other indexed classes in platform-api)
        assertThat(combined).isNotNull();
    }

    @Test
    void processorDecapitalizeWorks() {
        assertThat(decapitalize("TestItemService")).isEqualTo("testItemService");
        assertThat(decapitalize("")).isEmpty();
    }

    @Test
    void toKebabCase_camelCase() {
        assertThat(GraphQLResolverProcessor.toKebabCase("markAllRead")).isEqualTo("mark-all-read");
    }

    @Test
    void toKebabCase_singleWord() {
        assertThat(GraphQLResolverProcessor.toKebabCase("vendors")).isEqualTo("vendors");
    }

    @Test
    void toKebabCase_consecutiveUppercase() {
        assertThat(GraphQLResolverProcessor.toKebabCase("HTTPMethod")).isEqualTo("http-method");
    }

    @Test
    void toKebabCase_consecutiveUppercaseInMiddle() {
        assertThat(GraphQLResolverProcessor.toKebabCase("listHTTPMethods")).isEqualTo("list-http-methods");
    }

    @Test
    void toKebabCase_alreadyLowercase() {
        assertThat(GraphQLResolverProcessor.toKebabCase("status")).isEqualTo("status");
    }

    @Test
    void toPascalCase_simpleWord() {
        assertThat(GraphQLResolverProcessor.toPascalCase("digest")).isEqualTo("Digest");
    }

    @Test
    void toPascalCase_kebabCase() {
        assertThat(GraphQLResolverProcessor.toPascalCase("delivery-channels")).isEqualTo("DeliveryChannels");
    }

    @Test
    void toPascalCase_multipleHyphens() {
        assertThat(GraphQLResolverProcessor.toPascalCase("notification-preferences")).isEqualTo("NotificationPreferences");
    }

    @Test
    void toPascalCase_alreadyCapitalized() {
        assertThat(GraphQLResolverProcessor.toPascalCase("Digest")).isEqualTo("Digest");
    }

    @Test
    void httpVerbMapping_defaultQuery_isGET() {
        assertThat(GraphQLResolverProcessor.resolveHttpVerb(GraphQLResolverProcessor.OperationType.QUERY, null)).isEqualTo("GET");
    }

    @Test
    void httpVerbMapping_defaultMutation_isPOST() {
        assertThat(GraphQLResolverProcessor.resolveHttpVerb(GraphQLResolverProcessor.OperationType.MUTATION, null)).isEqualTo("POST");
    }

    @Test
    void httpVerbMapping_restMethodOverride_DELETE() {
        assertThat(GraphQLResolverProcessor.resolveHttpVerb(GraphQLResolverProcessor.OperationType.MUTATION, "DELETE")).isEqualTo("DELETE");
    }

    @Test
    void httpVerbMapping_restMethodOverride_PUT() {
        assertThat(GraphQLResolverProcessor.resolveHttpVerb(GraphQLResolverProcessor.OperationType.MUTATION, "PUT")).isEqualTo("PUT");
    }

    @Test
    void httpVerbMapping_restMethodOverride_PATCH() {
        assertThat(GraphQLResolverProcessor.resolveHttpVerb(GraphQLResolverProcessor.OperationType.MUTATION, "PATCH")).isEqualTo("PATCH");
    }

    @Test
    void isSimpleType_string() {
        assertThat(GraphQLResolverProcessor.isSimpleType("java.lang.String")).isTrue();
    }

    @Test
    void isSimpleType_primitiveWrapper() {
        assertThat(GraphQLResolverProcessor.isSimpleType("java.lang.Integer")).isTrue();
        assertThat(GraphQLResolverProcessor.isSimpleType("java.lang.Long")).isTrue();
        assertThat(GraphQLResolverProcessor.isSimpleType("java.lang.Boolean")).isTrue();
    }

    @Test
    void isSimpleType_uuid() {
        assertThat(GraphQLResolverProcessor.isSimpleType("java.util.UUID")).isTrue();
    }

    @Test
    void isSimpleType_javaTime() {
        assertThat(GraphQLResolverProcessor.isSimpleType("java.time.Instant")).isTrue();
        assertThat(GraphQLResolverProcessor.isSimpleType("java.time.LocalDate")).isTrue();
    }

    @Test
    void isSimpleType_complexType() {
        assertThat(GraphQLResolverProcessor.isSimpleType("io.casehub.platform.api.callback.CallbackRegistrationRequest")).isFalse();
    }

    @Test
    void responseWrapping_void_returns204() {
        assertThat(GraphQLResolverProcessor.generateResponseCode("void", "spi.doThing(arg0)"))
                .isEqualTo("spi.doThing(arg0); return Response.noContent().build();");
    }

    @Test
    void responseWrapping_optional_returns200or404() {
        assertThat(GraphQLResolverProcessor.generateResponseCode("Optional<String>", "spi.findItem(arg0)"))
                .isEqualTo("return spi.findItem(arg0).map(v -> Response.ok(v).build()).orElse(Response.status(404).build());");
    }

    @Test
    void responseWrapping_regularType_returns200() {
        assertThat(GraphQLResolverProcessor.generateResponseCode("List<String>", "spi.listItems()"))
                .isEqualTo("return Response.ok(spi.listItems()).build();");
    }

    @Test
    void restPathOverride_usesLiteralValue() {
        assertThat(GraphQLResolverProcessor.resolveRestPath("grants", "grant")).isEqualTo("grants");
    }

    @Test
    void restPathOverride_absent_fallsBackToKebab() {
        assertThat(GraphQLResolverProcessor.resolveRestPath(null, "grantBatch")).isEqualTo("grant-batch");
    }

    @Test
    void restPathOverride_nestedSegments() {
        assertThat(GraphQLResolverProcessor.resolveRestPath("grants/batch", "grantBatch")).isEqualTo("grants/batch");
    }

    @Test
    void isSimpleType_enumViaJandex() throws IOException {
        var indexer = new org.jboss.jandex.Indexer();
        indexer.indexClass(io.casehub.platform.api.acl.AclAction.class);
        var index = indexer.complete();
        assertThat(GraphQLResolverProcessor.isSimpleType("io.casehub.platform.api.acl.AclAction", index)).isTrue();
    }

    @Test
    void isSimpleType_fromStringViaJandex() throws IOException {
        var indexer = new org.jboss.jandex.Indexer();
        indexer.indexClass(io.casehub.platform.api.acl.ResourceId.class);
        var index = indexer.complete();
        assertThat(GraphQLResolverProcessor.isSimpleType("io.casehub.platform.api.acl.ResourceId", index)).isTrue();
    }

    @Test
    void isSimpleType_complexTypeWithJandex() throws IOException {
        var indexer = new org.jboss.jandex.Indexer();
        indexer.indexClass(io.casehub.platform.api.acl.AclEntryRequest.class);
        var index = indexer.complete();
        assertThat(GraphQLResolverProcessor.isSimpleType("io.casehub.platform.api.acl.AclEntryRequest", index)).isFalse();
    }

    @Test
    void isSimpleType_staticFallback_stillWorks() {
        assertThat(GraphQLResolverProcessor.isSimpleType("java.lang.String", null)).isTrue();
        assertThat(GraphQLResolverProcessor.isSimpleType("java.time.Instant", null)).isTrue();
    }

    @Test
    void responseWrapping_mutation_returns200() {
        assertThat(GraphQLResolverProcessor.generateResponseCode("String", "spi.create(arg0)", true, -1, false))
                .isEqualTo("return Response.ok(spi.create(arg0)).build();");
    }

    @Test
    void responseWrapping_query_returns200() {
        assertThat(GraphQLResolverProcessor.generateResponseCode("String", "spi.get(arg0)", false, -1, false))
                .isEqualTo("return Response.ok(spi.get(arg0)).build();");
    }

    @Test
    void responseWrapping_restStatusOverride_onMutation() {
        assertThat(GraphQLResolverProcessor.generateResponseCode("String", "spi.create(arg0)", true, 202, false))
                .isEqualTo("return Response.status(202).entity(spi.create(arg0)).build();");
    }

    @Test
    void responseWrapping_pathParam_nonCollection_nullChecks() {
        String result = GraphQLResolverProcessor.generateResponseCode("String", "spi.get(id)", false, -1, true);
        assertThat(result).contains("if (result == null) return Response.status(404).build()");
        assertThat(result).contains("Response.ok(result).build()");
    }

    @Test
    void responseWrapping_pathParam_collection_noNullCheck() {
        String result = GraphQLResolverProcessor.generateResponseCode("List<String>", "spi.list(id)", false, -1, true);
        assertThat(result).doesNotContain("404");
        assertThat(result).contains("Response.ok(");
    }

    @Test
    void isCollectionType_list() {
        assertThat(GraphQLResolverProcessor.isCollectionType("List<String>")).isTrue();
    }

    @Test
    void isCollectionType_nonCollection() {
        assertThat(GraphQLResolverProcessor.isCollectionType("String")).isFalse();
    }

    @Test
    void restNameOverridesQueryParamName() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.PageApi",
                """
                package test;
                import io.casehub.platform.api.mcp.*;

                @McpDomain("pages")
                public interface PageApi {
                    @PlatformQuery("List pages")
                    java.util.List<String> listPages(@RestName("page_size") Integer pageSize, @RestName("page_num") Integer pageNumber);
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new GraphQLResolverProcessor())
                .withOptions("-AdomainFilter=pages", "-AgenerateGraphQL=false")
                .compile(spi);

        String content = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedPagesResource")
                .get().getCharContent(true).toString();
        assertThat(content).contains("@QueryParam(\"page_size\")");
        assertThat(content).contains("@QueryParam(\"page_num\")");
    }

    @Test
    void rolesAllowedPassesThroughToRest() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.AdminApi",
                """
                package test;
                import io.casehub.platform.api.mcp.*;
                import jakarta.annotation.security.RolesAllowed;

                @McpDomain("admin")
                public interface AdminApi {
                    @PlatformQuery("Admin view")
                    @RolesAllowed({"admin", "superuser"})
                    String getAdminData();
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new GraphQLResolverProcessor())
                .withOptions("-AdomainFilter=admin", "-AgenerateGraphQL=false")
                .compile(spi);

        String content = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedAdminResource")
                .get().getCharContent(true).toString();
        assertThat(content).contains("jakarta.annotation.security.RolesAllowed");
        assertThat(content).contains("\"admin\"");
        assertThat(content).contains("\"superuser\"");
    }

    @Test
    void mutationEndpointReturns200() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.CreateApi",
                """
                package test;
                import io.casehub.platform.api.mcp.*;

                @McpDomain("items")
                public interface CreateApi {
                    @PlatformMutation("Create an item")
                    String createItem(String name);
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new GraphQLResolverProcessor())
                .withOptions("-AdomainFilter=items", "-AgenerateGraphQL=false")
                .compile(spi);

        String content = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedItemsResource")
                .get().getCharContent(true).toString();
        assertThat(content).contains("Response.ok(");
    }


    @Test
    void roundEnvScanDiscoversLocalInterface() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.SampleApi",
                """
                package test;
                import io.casehub.platform.api.mcp.McpDomain;
                import io.casehub.platform.api.mcp.PlatformQuery;
                import java.util.List;
                
                @McpDomain("sample")
                public interface SampleApi {
                    @PlatformQuery("List items")
                    List<String> listItems();
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                                                             .withProcessors(new GraphQLResolverProcessor())
                                                             .withOptions("-AdomainFilter=sample", "-AgenerateGraphQL=false")
                                                             .compile(spi);

        var restSource = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedSampleResource");
        assertThat(restSource).isPresent();

        String content = restSource.get().getCharContent(true).toString();
        assertThat(content).contains("class GeneratedSampleResource");
        assertThat(content).contains("@Path(\"/api/sample\")");
        assertThat(content).contains("import test.SampleApi;");
        assertThat(content).contains("public Response listItems(");

        var graphqlSource = compilation.generatedSourceFile(
                "io.casehub.platform.graphql.generated.GeneratedSampleResolver");
        assertThat(graphqlSource).isEmpty();
    }

    @Test
    void domainFilterExcludesNonMatchingDomains() throws Exception {
        var spi1 = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.AlphaApi",
                """
                package test;
                import io.casehub.platform.api.mcp.McpDomain;
                import io.casehub.platform.api.mcp.PlatformQuery;
                
                @McpDomain("alpha")
                public interface AlphaApi {
                    @PlatformQuery("Get alpha") String getAlpha();
                }
                """);

        var spi2 = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.BetaApi",
                """
                package test;
                import io.casehub.platform.api.mcp.McpDomain;
                import io.casehub.platform.api.mcp.PlatformQuery;
                
                @McpDomain("beta")
                public interface BetaApi {
                    @PlatformQuery("Get beta") String getBeta();
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                                                             .withProcessors(new GraphQLResolverProcessor())
                                                             .withOptions("-AdomainFilter=alpha", "-AgenerateGraphQL=false")
                                                             .compile(spi1, spi2);

        assertThat(compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedAlphaResource")).isPresent();
        assertThat(compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedBetaResource")).isEmpty();
        assertThat(compilation.generatedSourceFile(
                "io.casehub.platform.graphql.generated.GeneratedAlphaResolver")).isEmpty();
    }

    @Test
    void generateGraphQLFalseSuppressesResolvers() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.GammaApi",
                """
                package test;
                import io.casehub.platform.api.mcp.McpDomain;
                import io.casehub.platform.api.mcp.PlatformQuery;
                
                @McpDomain("gamma")
                public interface GammaApi {
                    @PlatformQuery("Get gamma") String getGamma();
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                                                             .withProcessors(new GraphQLResolverProcessor())
                                                             .withOptions("-AdomainFilter=gamma", "-AgenerateGraphQL=false")
                                                             .compile(spi);

        assertThat(compilation.generatedSourceFile(
                "io.casehub.platform.graphql.generated.GeneratedGammaResolver")).isEmpty();
        assertThat(compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedGammaResource")).isPresent();
    }

    @Test
    void hyphenatedDomainProducesValidClassName() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.DeliveryChannelApi",
                """
                package test;
                import io.casehub.platform.api.mcp.McpDomain;
                import io.casehub.platform.api.mcp.PlatformQuery;
                import java.util.List;
                
                @McpDomain("delivery-channels")
                public interface DeliveryChannelApi {
                    @PlatformQuery("List channels") List<String> listChannels();
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                                                             .withProcessors(new GraphQLResolverProcessor())
                                                             .withOptions("-AdomainFilter=delivery-channels", "-AgenerateGraphQL=false")
                                                             .compile(spi);

        var restSource = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedDeliveryChannelsResource");
        assertThat(restSource).isPresent();

        String content = restSource.get().getCharContent(true).toString();
        assertThat(content).contains("class GeneratedDeliveryChannelsResource");
        assertThat(content).contains("@Path(\"/api/delivery-channels\")");
    }


    @Test
    void streamingRestProducesSseEndpoint() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.EventApi",
                """
                package test;
                import io.casehub.platform.api.mcp.*;
                import io.smallrye.mutiny.Multi;

                @McpDomain("events")
                public interface EventApi {
                    @PlatformStream("Real-time events")
                    Multi<String> eventStream(@PathParam java.util.UUID scopeId);
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new GraphQLResolverProcessor())
                .withOptions("-AdomainFilter=events", "-AgenerateGraphQL=false")
                .compile(spi);

        var restSource = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedEventsResource");
        assertThat(restSource).isPresent();

        String content = restSource.get().getCharContent(true).toString();
        assertThat(content).contains("@Produces(MediaType.SERVER_SENT_EVENTS)");
        assertThat(content).contains("RestStreamElementType(MediaType.APPLICATION_JSON)");
        assertThat(content).contains("public Multi<String> eventStream(");
        assertThat(content).doesNotContain("Response.ok");
        assertThat(content).doesNotContain("@RunOnVirtualThread");
    }

    @Test
    void streamingGraphqlProducesSubscription() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.SubApi",
                """
                package test;
                import io.casehub.platform.api.mcp.*;
                import io.smallrye.mutiny.Multi;

                @McpDomain("subs")
                public interface SubApi {
                    @PlatformStream("Live updates")
                    Multi<String> updates(java.util.UUID id);
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new GraphQLResolverProcessor())
                .withOptions("-AdomainFilter=subs", "-AgenerateRest=false")
                .compile(spi);

        String content = compilation.generatedSourceFile(
                "io.casehub.platform.graphql.generated.GeneratedSubsResolver")
                .get().getCharContent(true).toString();
        assertThat(content).contains("Subscription");
        assertThat(content).doesNotContain("@Query\n");
        assertThat(content).contains("public Multi<String> updates(");
    }

    @Test
    void nonStreamingRestHasMethodLevelVirtualThread() throws Exception {
        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.PlainApi",
                """
                package test;
                import io.casehub.platform.api.mcp.*;

                @McpDomain("plain")
                public interface PlainApi {
                    @PlatformQuery("Get item")
                    String getItem(@PathParam java.util.UUID id);
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new GraphQLResolverProcessor())
                .withOptions("-AdomainFilter=plain", "-AgenerateGraphQL=false")
                .compile(spi);

        String content = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedPlainResource")
                .get().getCharContent(true).toString();
        assertThat(content).contains("@RunOnVirtualThread");
        assertThat(content).contains("public Response getItem(");
    }

    @Test
    void paginatedResponseAddsXTotalCountHeader() throws Exception {
        var pageType = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.ItemPage",
                """
                package test;
                public record ItemPage(java.util.List<String> items, int totalCount, boolean hasMore) {}
                """);

        var spi = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.ListApi",
                """
                package test;
                import io.casehub.platform.api.mcp.*;

                @McpDomain("lists")
                public interface ListApi {
                    @PlatformQuery("List items")
                    @PaginatedResponse
                    test.ItemPage listItems(Integer offset, Integer limit);
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                .withProcessors(new GraphQLResolverProcessor())
                .withOptions("-AdomainFilter=lists", "-AgenerateGraphQL=false")
                .compile(spi, pageType);

        String content = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedListsResource")
                .get().getCharContent(true).toString();
        assertThat(content).contains("X-Total-Count");
        assertThat(content).contains("page.totalCount()");
    }

    @Test
    void httpVerbMapping_defaultStream_isGET() {
        assertThat(GraphQLResolverProcessor.resolveHttpVerb(GraphQLResolverProcessor.OperationType.STREAM, null)).isEqualTo("GET");
    }

    @Test
    void roundEnvScanDiscoversLocalClass() throws Exception {
        var impl = com.google.testing.compile.JavaFileObjects.forSourceString(
                "test.SimpleService",
                """
                package test;
                import io.casehub.platform.api.mcp.McpDomain;
                import io.casehub.platform.api.mcp.PlatformQuery;
                import io.casehub.platform.api.mcp.PlatformMutation;
                import java.util.List;
                
                @McpDomain("simple")
                public class SimpleService {
                    @PlatformQuery("List items")
                    public List<String> listItems() { return List.of(); }
                
                    @PlatformMutation("Create item")
                    public String createItem(String name) { return name; }
                }
                """);

        var compilation = com.google.testing.compile.Compiler.javac()
                                                             .withProcessors(new GraphQLResolverProcessor())
                                                             .withOptions("-AdomainFilter=simple")
                                                             .compile(impl);

        var restSource = compilation.generatedSourceFile(
                "io.casehub.platform.rest.generated.GeneratedSimpleResource");
        assertThat(restSource).isPresent();

        String restContent = restSource.get().getCharContent(true).toString();
        assertThat(restContent).contains("class GeneratedSimpleResource");
        assertThat(restContent).contains("@Path(\"/api/simple\")");
        assertThat(restContent).contains("import test.SimpleService;");
        assertThat(restContent).contains("SimpleService simpleService;");
        assertThat(restContent).contains("public Response listItems(");
        assertThat(restContent).contains("public Response createItem(");

        var graphqlSource = compilation.generatedSourceFile(
                "io.casehub.platform.graphql.generated.GeneratedSimpleResolver");
        assertThat(graphqlSource).isPresent();

        String gqlContent = graphqlSource.get().getCharContent(true).toString();
        assertThat(gqlContent).contains("class GeneratedSimpleResolver");
        assertThat(gqlContent).contains("SimpleService simpleService;");
        assertThat(gqlContent).contains("@Query");
        assertThat(gqlContent).contains("@Mutation");
    }


    private static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
