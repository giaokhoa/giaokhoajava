package com.giaokhoa.authorization.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.giaokhoa.authorization.policy.PolicyGraph.Gate;
import com.giaokhoa.authorization.policy.PolicyGraph.Source;
import com.giaokhoa.support.RandomIds;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PolicyGraphWriterTest {
    private static final String HOST = "http://keycloak.test";
    private static final String CLIENTS =
            HOST + "/admin/realms/giaokhoa/clients?clientId=giaokhoa-api";
    private static final String ROOT =
            HOST + "/admin/realms/giaokhoa/clients/api-uuid/authz/resource-server";
    private static final String LIST = "?first=0&max=1000";
    private static final String PAGE2 = "?first=1000&max=1000";
    private static final UUID WORKSPACE = RandomIds.randomId();
    private static final String TARGET = "workspace:" + WORKSPACE;
    private static final String ELIGIBLE = "/gk/identity/eligible";
    private static final String OWNERS = "/gk/workspaces/" + WORKSPACE + "/owners";
    private static final String MANAGERS = "/gk/workspaces/" + WORKSPACE + "/managers";
    private static final Instant FROM = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2030-02-01T00:00:00Z");
    private static final Source MANAGER_SOURCE =
            Source.of(new Gate.GroupMembership(MANAGERS), new Gate.Validity(FROM, UNTIL));
    private static final String SOURCE_NAME = "gk-source:" + MANAGER_SOURCE.descriptorHash();
    private static final String ANY_NAME = "gk-any:" + TARGET + "#enter";
    private static final String ROOT_NAME = "gk-root:" + TARGET + "#enter";

    /** Eligibility gate nằm ngoài OR; owner một mình, hoặc manager trong một validity window. */
    private static PolicyGraph graph() {
        return PolicyGraph.forEligibleActor(
                TARGET,
                "enter",
                List.of(Source.of(new Gate.GroupMembership(OWNERS)), MANAGER_SOURCE));
    }

    @Test
    void writesTheWholeGraphAndReadsItBack() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);

        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, "[]");
        expectCreate(server, ROOT + "/scope", "{\"id\":\"scope-enter\"}");
        expectGet(server, ROOT + "/resource" + LIST, "[]");
        expectCreate(
                server,
                ROOT + "/resource",
                "{\"_id\":\"res-1\"}",
                "{\"name\":\""
                        + TARGET
                        + "\",\"scopes\":[{\"id\":\"scope-enter\",\"name\":\"enter\"}]}");
        expectGet(server, ROOT + "/policy" + LIST, "[]");
        expectGroup(server, ELIGIBLE, "g-elig");
        expectCreate(
                server,
                ROOT + "/policy/group",
                "{\"id\":\"pol-elig\"}",
                "{\"name\":\"gk-group:"
                        + ELIGIBLE
                        + "\",\"groups\":[{\"id\":\"g-elig\",\"extendChildren\":false}]}");
        expectGroup(server, MANAGERS, "g-mgr");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-mgr\"}");
        expectCreate(
                server,
                ROOT + "/policy/time",
                "{\"id\":\"pol-from\"}",
                "{\"name\":\"gk-valid-from:"
                        + FROM
                        + "\",\"logic\":\"POSITIVE\",\"notBefore\":\"2030-01-01 00:00:00\"}");
        expectCreate(
                server,
                ROOT + "/policy/time",
                "{\"id\":\"pol-until\"}",
                "{\"name\":\"gk-valid-until:"
                        + UNTIL
                        + "\",\"logic\":\"NEGATIVE\",\"notBefore\":\"2030-02-01 00:00:00\"}");
        expectCreate(
                server,
                ROOT + "/policy/aggregate",
                "{\"id\":\"pol-src\"}",
                "{\"name\":\""
                        + SOURCE_NAME
                        + "\",\"decisionStrategy\":\"UNANIMOUS\","
                        + "\"policies\":[\"pol-mgr\",\"pol-from\",\"pol-until\"]}");
        expectGroup(server, OWNERS, "g-own");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-own\"}");
        expectCreate(
                server,
                ROOT + "/policy/aggregate",
                "{\"id\":\"pol-any\"}",
                "{\"name\":\""
                        + ANY_NAME
                        + "\",\"decisionStrategy\":\"AFFIRMATIVE\","
                        + "\"policies\":[\"pol-src\",\"pol-own\"]}");
        expectGet(server, ROOT + "/permission/scope" + LIST, "[]");
        expectCreate(
                server,
                ROOT + "/permission/scope",
                "{\"id\":\"perm-1\"}",
                "{\"name\":\""
                        + ROOT_NAME
                        + "\",\"decisionStrategy\":\"UNANIMOUS\","
                        + "\"resources\":[\"res-1\"],\"scopes\":[\"scope-enter\"],"
                        + "\"policies\":[\"pol-elig\",\"pol-any\"]}");
        expectReadBack(server, "perm-1", List.of("pol-elig", "pol-any"));

        PolicyGraphWriter.AppliedGraph applied = writer.apply(graph());

        assertEquals("res-1", applied.resourceId());
        assertEquals(
                List.of(
                        ANY_NAME,
                        "gk-group:" + ELIGIBLE,
                        "gk-group:" + MANAGERS,
                        "gk-group:" + OWNERS,
                        SOURCE_NAME,
                        "gk-valid-from:" + FROM,
                        "gk-valid-until:" + UNTIL),
                applied.policyNames());
        server.verify();
    }

    @DisplayName("Áp lại graph đã có: chỉ sửa đúng chỗ đã stale, không tạo mới thừa")
    @ParameterizedTest(name = "{0}")
    @MethodSource("reapplyScenarios")
    void reapplyingAnExistingGraphRepairsOnlyTheStalePart(
            String scenario,
            boolean staleRoot,
            boolean staleOr,
            boolean staleSource,
            boolean staleLeaves) {
        assertReapply(staleRoot, staleOr, staleSource, staleLeaves);
    }

    private static Stream<Arguments> reapplyScenarios() {
        return Stream.of(
                Arguments.of("graph đã đúng thì không tạo hay sửa gì", false, false, false, false),
                Arguments.of(
                        "root permission logic/strategy sai thì sửa root",
                        true,
                        false,
                        false,
                        false),
                Arguments.of(
                        "aggregate OR logic/strategy sai thì sửa aggregate OR",
                        false,
                        true,
                        false,
                        false),
                Arguments.of(
                        "aggregate của source logic/strategy sai thì sửa aggregate source",
                        false,
                        false,
                        true,
                        false),
                Arguments.of("leaf group/time hỏng thì sửa leaf", false, false, false, true));
    }

    @Test
    void failsClosedWhenLeafStillHasWrongConfigurationAfterUpdate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);
        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, "[]");
        expectCreate(server, ROOT + "/scope", "{\"id\":\"scope-enter\"}");
        expectGet(server, ROOT + "/resource" + LIST, "[]");
        expectCreate(server, ROOT + "/resource", "{\"_id\":\"res-1\"}");
        expectGet(
                server,
                ROOT + "/policy" + LIST,
                "[{\"id\":\"pol-elig\",\"name\":\"gk-group:"
                        + ELIGIBLE
                        + "\",\"type\":\"group\"}]");
        expectGroup(server, ELIGIBLE, "g-elig");
        String wrong =
                "{\"id\":\"pol-elig\",\"name\":\"gk-group:"
                        + ELIGIBLE
                        + "\",\"type\":\"group\",\"logic\":\"NEGATIVE\","
                        + "\"groups\":[{\"id\":\"g-elig\",\"extendChildren\":false}]}";
        expectGet(server, ROOT + "/policy/group/pol-elig", wrong);
        server.expect(requestTo(ROOT + "/policy/group/pol-elig"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withNoContent());
        expectGet(server, ROOT + "/policy/group/pol-elig", wrong);
        assertThrows(IllegalStateException.class, () -> writer.apply(graph()));
        server.verify();
    }

    private void assertReapply(
            boolean staleRoot, boolean staleOr, boolean staleSource, boolean staleLeaves) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);

        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, "[{\"id\":\"scope-enter\",\"name\":\"enter\"}]");
        expectGet(
                server,
                ROOT + "/resource" + LIST,
                "[{\"_id\":\"res-1\",\"name\":\"" + TARGET + "\"}]");
        expectGet(
                server,
                ROOT + "/resource/res-1",
                "{\"_id\":\"res-1\",\"name\":\""
                        + TARGET
                        + "\",\"scopes\":[{\"id\":\"scope-enter\",\"name\":\"enter\"}]}");
        expectGet(
                server,
                ROOT + "/policy" + LIST,
                "[{\"id\":\"pol-elig\",\"name\":\"gk-group:"
                        + ELIGIBLE
                        + "\",\"type\":\"group\"},"
                        + "{\"id\":\"pol-mgr\",\"name\":\"gk-group:"
                        + MANAGERS
                        + "\",\"type\":\"group\"},"
                        + "{\"id\":\"pol-own\",\"name\":\"gk-group:"
                        + OWNERS
                        + "\",\"type\":\"group\"},"
                        + "{\"id\":\"pol-from\",\"name\":\"gk-valid-from:"
                        + FROM
                        + "\",\"type\":\"time\"},"
                        + "{\"id\":\"pol-until\",\"name\":\"gk-valid-until:"
                        + UNTIL
                        + "\",\"type\":\"time\"},"
                        + "{\"id\":\"pol-src\",\"name\":\""
                        + SOURCE_NAME
                        + "\",\"type\":\"aggregate\"},"
                        + "{\"id\":\"pol-any\",\"name\":\""
                        + ANY_NAME
                        + "\",\"type\":\"aggregate\"}]");
        expectGroup(server, ELIGIBLE, "g-elig");
        if (staleLeaves) {
            expectGet(
                    server,
                    ROOT + "/policy/group/pol-elig",
                    "{\"id\":\"pol-elig\",\"name\":\"gk-group:"
                            + ELIGIBLE
                            + "\",\"type\":\"group\",\"logic\":\"NEGATIVE\","
                            + "\"groups\":[{\"id\":\"wrong-group\",\"extendChildren\":true}]}");
            expectLeafRepair(
                    server,
                    "group",
                    "pol-elig",
                    "\"groups\":[{\"id\":\"g-elig\",\"extendChildren\":false}]");
        } else {
            expectLeaf(server, "pol-elig");
        }
        expectGroup(server, MANAGERS, "g-mgr");
        expectLeaf(server, "pol-mgr");
        expectLeaf(server, "pol-from");
        if (staleLeaves) {
            expectGet(
                    server,
                    ROOT + "/policy/time/pol-until",
                    "{\"id\":\"pol-until\",\"name\":\"gk-valid-until:"
                            + UNTIL
                            + "\",\"type\":\"time\",\"logic\":\"POSITIVE\","
                            + "\"notBefore\":\"2030-03-01 00:00:00\","
                            + "\"notOnOrAfter\":\"2040-01-01 00:00:00\"}");
            expectLeafRepair(server, "time", "pol-until", "\"notBefore\":\"2030-02-01 00:00:00\"");
        } else {
            expectLeaf(server, "pol-until");
        }
        expectGet(
                server,
                ROOT + "/policy/aggregate/pol-src",
                "{\"id\":\"pol-src\",\"logic\":\""
                        + (staleSource ? "NEGATIVE" : "POSITIVE")
                        + "\",\"decisionStrategy\":\""
                        + (staleSource ? "AFFIRMATIVE" : "UNANIMOUS")
                        + "\",\"policies\":[\"pol-mgr\",\"pol-from\",\"pol-until\"]}");
        if (staleSource) {
            server.expect(requestTo(ROOT + "/policy/aggregate/pol-src"))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(
                            content()
                                    .json(
                                            "{\"logic\":\"POSITIVE\",\"decisionStrategy\":\"UNANIMOUS\"}"))
                    .andRespond(withNoContent());
            expectGet(
                    server,
                    ROOT + "/policy/aggregate/pol-src",
                    "{\"id\":\"pol-src\",\"logic\":\"POSITIVE\",\"decisionStrategy\":\"UNANIMOUS\","
                            + "\"policies\":[\"pol-mgr\",\"pol-from\",\"pol-until\"]}");
        }
        expectGroup(server, OWNERS, "g-own");
        expectLeaf(server, "pol-own");
        expectGet(
                server,
                ROOT + "/policy/aggregate/pol-any",
                "{\"id\":\"pol-any\",\"logic\":\""
                        + (staleOr ? "NEGATIVE" : "POSITIVE")
                        + "\",\"decisionStrategy\":\""
                        + (staleOr ? "UNANIMOUS" : "AFFIRMATIVE")
                        + "\",\"policies\":[\"pol-src\",\"pol-own\"]}");
        if (staleOr) {
            server.expect(requestTo(ROOT + "/policy/aggregate/pol-any"))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(
                            content()
                                    .json(
                                            "{\"logic\":\"POSITIVE\",\"decisionStrategy\":\"AFFIRMATIVE\"}"))
                    .andRespond(withNoContent());
            expectGet(
                    server,
                    ROOT + "/policy/aggregate/pol-any",
                    "{\"id\":\"pol-any\",\"logic\":\"POSITIVE\",\"decisionStrategy\":\"AFFIRMATIVE\","
                            + "\"policies\":[\"pol-src\",\"pol-own\"]}");
        }
        String rootState =
                "[{\"id\":\"perm-1\",\"name\":\""
                        + ROOT_NAME
                        + "\",\"policies\":[\"pol-elig\",\"pol-any\"],"
                        + "\"resources\":[\"res-1\"],\"scopes\":[\"scope-enter\"]}]";
        if (staleRoot) {
            rootState =
                    rootState.replace(
                            "\"policies\"",
                            "\"logic\":\"NEGATIVE\",\"decisionStrategy\":\"AFFIRMATIVE\",\"policies\"");
        }
        expectGet(server, ROOT + "/permission/scope" + LIST, rootState);
        expectGet(
                server,
                ROOT + "/permission/scope/perm-1",
                rootState.substring(1, rootState.length() - 1));
        if (staleRoot) {
            server.expect(requestTo(ROOT + "/permission/scope/perm-1"))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(
                            content()
                                    .json(
                                            "{\"logic\":\"POSITIVE\",\"decisionStrategy\":\"UNANIMOUS\"}"))
                    .andRespond(withNoContent());
        }
        expectGet(
                server,
                ROOT + "/permission/scope/perm-1",
                "{\"id\":\"perm-1\",\"policies\":[\"pol-elig\",\"pol-any\"],"
                        + "\"resources\":[\"res-1\"],\"scopes\":[\"scope-enter\"]}");
        expectGet(
                server,
                ROOT + "/resource/res-1",
                "{\"_id\":\"res-1\",\"name\":\""
                        + TARGET
                        + "\",\"scopes\":[{\"id\":\"scope-enter\",\"name\":\"enter\"}]}");

        PolicyGraphWriter.AppliedGraph applied = writer.apply(graph());
        assertEquals("res-1", applied.resourceId());
        assertEquals(7, applied.policyNames().size());
        server.verify();
    }

    @Test
    void updatesResourceScopesAndStaleRootPoliciesForANewRevision() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);
        PolicyGraph graph =
                new PolicyGraph(
                        TARGET, "enter", List.of(Source.of(new Gate.GroupMembership(OWNERS))));

        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, "[{\"id\":\"scope-enter\",\"name\":\"enter\"}]");
        expectGet(
                server,
                ROOT + "/resource" + LIST,
                "[{\"_id\":\"res-1\",\"name\":\"" + TARGET + "\"}]");
        expectGet(
                server,
                ROOT + "/resource/res-1",
                "{\"_id\":\"res-1\",\"name\":\""
                        + TARGET
                        + "\",\"scopes\":[{\"id\":\"scope-read\",\"name\":\"read\"}]}");
        server.expect(requestTo(ROOT + "/resource/res-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(
                        content()
                                .json(
                                        "{\"_id\":\"res-1\",\"name\":\""
                                                + TARGET
                                                + "\",\"scopes\":[{\"id\":\"scope-read\",\"name\":\"read\"},"
                                                + "{\"id\":\"scope-enter\",\"name\":\"enter\"}]}"))
                .andRespond(withNoContent());
        expectGet(
                server,
                ROOT + "/policy" + LIST,
                "[{\"id\":\"pol-own\",\"name\":\"gk-group:" + OWNERS + "\",\"type\":\"group\"}]");
        expectGroup(server, ELIGIBLE, "g-elig");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-elig\"}");
        expectGroup(server, OWNERS, "g-own");
        expectLeaf(server, "pol-own");
        expectGet(
                server,
                ROOT + "/permission/scope" + LIST,
                "[{\"id\":\"perm-1\",\"name\":\""
                        + ROOT_NAME
                        + "\",\"policies\":[\"pol-stale\"],"
                        + "\"resources\":[\"res-1\"],\"scopes\":[\"scope-enter\"]}]");
        expectGet(
                server,
                ROOT + "/permission/scope/perm-1",
                "{\"id\":\"perm-1\",\"name\":\""
                        + ROOT_NAME
                        + "\",\"policies\":[\"pol-stale\"],\"resources\":[\"res-1\"],\"scopes\":[\"scope-enter\"]}");
        server.expect(requestTo(ROOT + "/permission/scope/perm-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(
                        content()
                                .json(
                                        "{\"id\":\"perm-1\",\"name\":\""
                                                + ROOT_NAME
                                                + "\",\"policies\":[\"pol-elig\",\"pol-own\"]}"))
                .andRespond(withNoContent());
        expectGet(
                server,
                ROOT + "/permission/scope/perm-1",
                "{\"id\":\"perm-1\",\"policies\":[\"pol-elig\",\"pol-own\"],"
                        + "\"resources\":[\"res-1\"],\"scopes\":[\"scope-enter\"]}");
        expectGet(
                server,
                ROOT + "/resource/res-1",
                "{\"_id\":\"res-1\",\"name\":\""
                        + TARGET
                        + "\",\"scopes\":[{\"id\":\"scope-read\",\"name\":\"read\"},"
                        + "{\"id\":\"scope-enter\",\"name\":\"enter\"}]}");

        PolicyGraphWriter.AppliedGraph applied = writer.apply(graph);
        assertEquals(List.of("gk-group:" + ELIGIBLE, "gk-group:" + OWNERS), applied.policyNames());
        server.verify();
    }

    @Test
    void retractRemovesOnlyTheRootPermissionAndItsOrAggregate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);

        expectClients(server);
        expectGet(
                server,
                ROOT + "/permission/scope" + LIST,
                "[{\"id\":\"perm-1\",\"name\":\"" + ROOT_NAME + "\"}]");
        server.expect(requestTo(ROOT + "/permission/scope/perm-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        expectGet(
                server,
                ROOT + "/policy" + LIST,
                "[{\"id\":\"pol-any\",\"name\":\""
                        + ANY_NAME
                        + "\",\"type\":\"aggregate\"},"
                        + "{\"id\":\"pol-own\",\"name\":\"gk-group:"
                        + OWNERS
                        + "\",\"type\":\"group\"}]");
        server.expect(requestTo(ROOT + "/policy/pol-any"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        writer.retractRoot(TARGET, "enter");
        server.verify();

        assertThrows(
                IllegalArgumentException.class, () -> writer.retractRoot(TARGET, "content.read"));
    }

    @Test
    void failsWhenARealmGroupIsNotProvisioned() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);
        PolicyGraph graph =
                new PolicyGraph(
                        TARGET, "enter", List.of(Source.of(new Gate.GroupMembership(OWNERS))));

        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, "[]");
        expectCreate(server, ROOT + "/scope", "{\"id\":\"scope-enter\"}");
        expectGet(server, ROOT + "/resource" + LIST, "[]");
        expectCreate(server, ROOT + "/resource", "{\"_id\":\"res-1\"}");
        expectGet(server, ROOT + "/policy" + LIST, "[]");
        expectGroup(server, ELIGIBLE, "g-elig");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-elig\"}");
        server.expect(requestTo(HOST + "/admin/realms/giaokhoa/group-by-path" + OWNERS))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(IllegalStateException.class, () -> writer.apply(graph));
        server.verify();
    }

    @DisplayName("Apply đồng thời tạo trùng thì dùng lại thực thể đã có thay vì tạo mới")
    @ParameterizedTest(name = "{0}")
    @MethodSource("concurrentCreateConflicts")
    void usesTheExistingEntityWhenAConcurrentCreateConflicts(
            String scenario, boolean aggregateConflict, List<String> expectedPolicyNames) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);
        PolicyGraph graph =
                aggregateConflict
                        ? new PolicyGraph(
                                TARGET,
                                "enter",
                                List.of(
                                        Source.of(new Gate.GroupMembership(OWNERS)),
                                        Source.of(new Gate.GroupMembership(MANAGERS))))
                        : PolicyGraph.forEligibleActor(
                                TARGET,
                                "enter",
                                List.of(Source.of(new Gate.GroupMembership(OWNERS))));

        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, "[]");
        expectCreate(server, ROOT + "/scope", "{\"id\":\"scope-enter\"}");
        expectGet(server, ROOT + "/resource" + LIST, "[]");
        expectCreate(server, ROOT + "/resource", "{\"_id\":\"res-1\"}");
        expectGet(server, ROOT + "/policy" + LIST, "[]");
        expectGroup(server, ELIGIBLE, "g-elig");
        List<String> rootPolicies;
        if (aggregateConflict) {
            expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-elig\"}");
            expectGroup(server, MANAGERS, "g-mgr");
            expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-mgr\"}");
            expectGroup(server, OWNERS, "g-own");
            expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-own\"}");
            // Một apply đồng thời đã tạo OR aggregate của cặp này trước.
            server.expect(requestTo(ROOT + "/policy/aggregate"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.CONFLICT));
            expectGet(
                    server,
                    ROOT + "/policy" + LIST,
                    "[{\"id\":\"pol-mgr\",\"name\":\"gk-group:"
                            + MANAGERS
                            + "\",\"type\":\"group\"},"
                            + "{\"id\":\"pol-own\",\"name\":\"gk-group:"
                            + OWNERS
                            + "\",\"type\":\"group\"},"
                            + "{\"id\":\"pol-any\",\"name\":\""
                            + ANY_NAME
                            + "\",\"type\":\"aggregate\"}]");
            expectGet(
                    server,
                    ROOT + "/policy/aggregate/pol-any",
                    "{\"id\":\"pol-any\",\"logic\":\"POSITIVE\","
                            + "\"decisionStrategy\":\"AFFIRMATIVE\","
                            + "\"policies\":[\"pol-mgr\",\"pol-own\"]}");
            rootPolicies = List.of("pol-elig", "pol-any");
        } else {
            // Một apply đồng thời đã tạo shared eligibility policy trước.
            server.expect(requestTo(ROOT + "/policy/group"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.CONFLICT));
            expectGet(
                    server,
                    ROOT + "/policy" + LIST,
                    "[{\"id\":\"pol-elig\",\"name\":\"gk-group:"
                            + ELIGIBLE
                            + "\",\"type\":\"group\"}]");
            expectLeaf(server, "pol-elig");
            expectGroup(server, OWNERS, "g-own");
            expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-own\"}");
            rootPolicies = List.of("pol-elig", "pol-own");
        }
        expectGet(server, ROOT + "/permission/scope" + LIST, "[]");
        expectCreate(
                server,
                ROOT + "/permission/scope",
                "{\"id\":\"perm-1\"}",
                "{\"name\":\""
                        + ROOT_NAME
                        + "\",\"policies\":[\""
                        + String.join("\",\"", rootPolicies)
                        + "\"]}");
        expectReadBack(server, "perm-1", rootPolicies);

        PolicyGraphWriter.AppliedGraph applied = writer.apply(graph);
        assertEquals(expectedPolicyNames, applied.policyNames());
        server.verify();
    }

    private static Stream<Arguments> concurrentCreateConflicts() {
        return Stream.of(
                Arguments.of(
                        "policy group đã có thì dùng lại policy đó",
                        false,
                        List.of("gk-group:" + ELIGIBLE, "gk-group:" + OWNERS)),
                Arguments.of(
                        "aggregate OR đã có thì dùng lại aggregate đó",
                        true,
                        List.of(
                                ANY_NAME,
                                "gk-group:" + ELIGIBLE,
                                "gk-group:" + MANAGERS,
                                "gk-group:" + OWNERS)));
    }

    @Test
    void findsTheScopeOnTheSecondPageWhenApplying() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);
        PolicyGraph graph =
                new PolicyGraph(
                        TARGET, "enter", List.of(Source.of(new Gate.GroupMembership(OWNERS))));

        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, dummyPage("scope", 1000));
        expectGet(server, ROOT + "/scope" + PAGE2, "[{\"id\":\"scope-enter\",\"name\":\"enter\"}]");
        expectGet(server, ROOT + "/resource" + LIST, "[]");
        expectCreate(server, ROOT + "/resource", "{\"_id\":\"res-1\"}");
        expectGet(server, ROOT + "/policy" + LIST, "[]");
        expectGroup(server, ELIGIBLE, "g-elig");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-elig\"}");
        expectGroup(server, OWNERS, "g-own");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-own\"}");
        expectGet(server, ROOT + "/permission/scope" + LIST, "[]");
        expectCreate(server, ROOT + "/permission/scope", "{\"id\":\"perm-1\"}");
        expectGet(
                server,
                ROOT + "/permission/scope/perm-1",
                "{\"id\":\"perm-1\",\"policies\":[\"pol-elig\",\"pol-own\"],"
                        + "\"resources\":[\"res-1\"],\"scopes\":[\"scope-enter\"]}");
        expectGet(
                server,
                ROOT + "/resource/res-1",
                "{\"_id\":\"res-1\",\"name\":\""
                        + TARGET
                        + "\",\"scopes\":[{\"id\":\"scope-enter\",\"name\":\"enter\"}]}");

        PolicyGraphWriter.AppliedGraph applied = writer.apply(graph);
        assertEquals(List.of("gk-group:" + ELIGIBLE, "gk-group:" + OWNERS), applied.policyNames());
        server.verify();
    }

    @Test
    void findsTheRootPermissionAndAggregateOnSecondPagesWhenRetracting() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);

        expectClients(server);
        expectGet(server, ROOT + "/permission/scope" + LIST, dummyPage("perm", 1000));
        expectGet(
                server,
                ROOT + "/permission/scope" + PAGE2,
                "[{\"id\":\"perm-1\",\"name\":\"" + ROOT_NAME + "\"}]");
        server.expect(requestTo(ROOT + "/permission/scope/perm-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        expectGet(server, ROOT + "/policy" + LIST, dummyPage("pol", 1000));
        expectGet(
                server,
                ROOT + "/policy" + PAGE2,
                "[{\"id\":\"pol-any\",\"name\":\"" + ANY_NAME + "\",\"type\":\"aggregate\"}]");
        server.expect(requestTo(ROOT + "/policy/pol-any"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        writer.retractRoot(TARGET, "enter");
        server.verify();
    }

    @Test
    void writesAnActorSourceAsAUserPolicy() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);
        PolicyGraph graph =
                new PolicyGraph(TARGET, "enter", List.of(Source.of(new Gate.Actor("user-123"))));

        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, "[]");
        expectCreate(server, ROOT + "/scope", "{\"id\":\"scope-enter\"}");
        expectGet(server, ROOT + "/resource" + LIST, "[]");
        expectCreate(server, ROOT + "/resource", "{\"_id\":\"res-1\"}");
        expectGet(server, ROOT + "/policy" + LIST, "[]");
        expectGroup(server, ELIGIBLE, "g-elig");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-elig\"}");
        expectCreate(
                server,
                ROOT + "/policy/user",
                "{\"id\":\"pol-user\"}",
                "{\"name\":\"gk-user:user-123\",\"type\":\"user\"," + "\"users\":[\"user-123\"]}");
        expectGet(server, ROOT + "/permission/scope" + LIST, "[]");
        expectCreate(
                server,
                ROOT + "/permission/scope",
                "{\"id\":\"perm-1\"}",
                "{\"name\":\"" + ROOT_NAME + "\",\"policies\":[\"pol-elig\",\"pol-user\"]}");
        expectGet(
                server,
                ROOT + "/permission/scope/perm-1",
                "{\"id\":\"perm-1\",\"policies\":[\"pol-elig\",\"pol-user\"],"
                        + "\"resources\":[\"res-1\"],\"scopes\":[\"scope-enter\"]}");
        expectGet(
                server,
                ROOT + "/resource/res-1",
                "{\"_id\":\"res-1\",\"name\":\""
                        + TARGET
                        + "\",\"scopes\":[{\"id\":\"scope-enter\",\"name\":\"enter\"}]}");

        PolicyGraphWriter.AppliedGraph applied = writer.apply(graph);
        assertEquals(List.of("gk-group:" + ELIGIBLE, "gk-user:user-123"), applied.policyNames());
        server.verify();
    }

    @Test
    void failsWhenTheReadBackPermissionMissesAPolicy() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);
        PolicyGraph graph =
                new PolicyGraph(
                        TARGET, "enter", List.of(Source.of(new Gate.GroupMembership(OWNERS))));

        expectClients(server);
        expectGet(server, ROOT + "/scope" + LIST, "[]");
        expectCreate(server, ROOT + "/scope", "{\"id\":\"scope-enter\"}");
        expectGet(server, ROOT + "/resource" + LIST, "[]");
        expectCreate(server, ROOT + "/resource", "{\"_id\":\"res-1\"}");
        expectGet(server, ROOT + "/policy" + LIST, "[]");
        expectGroup(server, ELIGIBLE, "g-elig");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-elig\"}");
        expectGroup(server, OWNERS, "g-own");
        expectCreate(server, ROOT + "/policy/group", "{\"id\":\"pol-own\"}");
        expectGet(server, ROOT + "/permission/scope" + LIST, "[]");
        expectCreate(server, ROOT + "/permission/scope", "{\"id\":\"perm-1\"}");
        expectReadBack(server, "perm-1", List.of());

        assertThrows(IllegalStateException.class, () -> writer.apply(graph));
        server.verify();
    }

    @DisplayName("Graph còn nguyên vẹn chỉ khi root permission liên kết đúng các policy đã ghi")
    @ParameterizedTest(name = "{0}")
    @MethodSource("graphIntactnessCases")
    void graphIntactnessDependsOnTheExactRootPermissionLinks(
            String scenario,
            List<String> permissionPolicyIds,
            boolean extraPolicy,
            boolean expected) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PolicyGraphWriter writer = writer(builder);

        expectClients(server);
        expectStoredGraph(server, permissionPolicyIds, extraPolicy);

        assertEquals(
                expected,
                writer.isGraphIntact(TARGET, "enter", "res-1", List.of("gk-group:" + OWNERS)));
        server.verify();
    }

    private static Stream<Arguments> graphIntactnessCases() {
        return Stream.of(
                Arguments.of(
                        "root permission liên kết đúng các policy đã ghi thì còn nguyên vẹn",
                        List.of("pol-own"),
                        false,
                        true),
                // Bản thân policy vẫn tồn tại; chỉ liên kết từ root permission đã bị gỡ.
                Arguments.of(
                        "policy bị gỡ khỏi root permission thì không còn nguyên vẹn",
                        List.of(),
                        false,
                        false),
                Arguments.of(
                        "root permission liên kết thêm policy thì không còn nguyên vẹn",
                        List.of("pol-own", "pol-extra"),
                        true,
                        false));
    }

    private void expectStoredGraph(
            MockRestServiceServer server, List<String> permissionPolicyIds, boolean extraPolicy) {
        expectGet(
                server,
                ROOT + "/resource" + LIST,
                "[{\"_id\":\"res-1\",\"name\":\"" + TARGET + "\"}]");
        String policies =
                "[{\"id\":\"pol-own\",\"name\":\"gk-group:"
                        + OWNERS
                        + "\",\"type\":\"group\"}"
                        + (extraPolicy
                                ? ",{\"id\":\"pol-extra\",\"name\":\"gk-extra\",\"type\":\"group\"}"
                                : "")
                        + "]";
        expectGet(server, ROOT + "/policy" + LIST, policies);
        expectGet(
                server,
                ROOT + "/permission/scope" + LIST,
                "[{\"id\":\"perm-1\",\"name\":\"" + ROOT_NAME + "\"}]");
        expectAssociated(server, "perm-1", "resources", List.of("res-1"), "_id");
        expectAssociated(server, "perm-1", "associatedPolicies", permissionPolicyIds, "id");
    }

    /** Một tập association của một policy hoặc permission, như Keycloak phục vụ nó. */
    private void expectAssociated(
            MockRestServiceServer server,
            String policyId,
            String association,
            List<String> ids,
            String idKey) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"").append(idKey).append("\":\"").append(ids.get(i)).append("\"}");
        }
        expectGet(
                server,
                ROOT + "/policy/" + policyId + "/" + association,
                json.append(']').toString());
    }

    /** Các endpoint detail và association native phải được đọc trước khi báo một graph là đã áp. */
    private void expectReadBack(
            MockRestServiceServer server, String permissionId, List<String> policyIds) {
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var state = new java.util.LinkedHashMap<String, Object>();
        state.put("id", permissionId);
        state.put("name", ROOT_NAME);
        state.put("logic", "POSITIVE");
        state.put("decisionStrategy", "UNANIMOUS");
        state.put("policies", policyIds);
        state.put("resources", List.of("res-1"));
        state.put("scopes", List.of("scope-enter"));
        expectGet(
                server,
                ROOT + "/permission/scope/" + permissionId,
                mapper.writeValueAsString(state));
        expectGet(
                server,
                ROOT + "/resource/res-1",
                "{\"_id\":\"res-1\",\"name\":\""
                        + TARGET
                        + "\",\"scopes\":[{\"id\":\"scope-enter\",\"name\":\"enter\"}]}");
    }

    /** Một trang đầy các mục không liên quan, nên chỉ trang sau mới có thể giữ tên cần tìm. */
    private static String dummyPage(String kind, int count) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"")
                    .append(kind)
                    .append('-')
                    .append(i)
                    .append("\",\"name\":\"")
                    .append(kind)
                    .append('-')
                    .append(i)
                    .append("\"}");
        }
        return json.append(']').toString();
    }

    private void expectClients(MockRestServiceServer server) {
        server.expect(requestTo(CLIENTS))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "[{\"id\":\"api-uuid\",\"clientId\":\"giaokhoa-api\"}]",
                                MediaType.APPLICATION_JSON));
    }

    private void expectGet(MockRestServiceServer server, String url, String body) {
        String responseBody = body;
        if (url.startsWith(ROOT + "/permission/scope")
                && body.contains("\"policies\"")
                && !body.contains("\"logic\"")) {
            responseBody =
                    responseBody.replace(
                            "\"policies\"",
                            "\"logic\":\"POSITIVE\",\"decisionStrategy\":\"UNANIMOUS\",\"policies\"");
        }
        if (url.startsWith(ROOT + "/policy/aggregate/")
                && body.contains("\"policies\"")
                && !body.contains("\"logic\"")) {
            String strategy = url.endsWith("/pol-any") ? "AFFIRMATIVE" : "UNANIMOUS";
            responseBody =
                    responseBody.replace(
                            "\"policies\"",
                            "\"logic\":\"POSITIVE\",\"decisionStrategy\":\""
                                    + strategy
                                    + "\",\"policies\"");
        }
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
        if ((url.contains("/policy/aggregate/") || url.contains("/permission/scope/"))
                && !url.endsWith("/associatedPolicies")) {
            var mapper = new tools.jackson.databind.json.JsonMapper();
            var policies = mapper.readTree(responseBody).get("policies");
            if (policies != null) {
                List<java.util.Map<String, String>> associated = new java.util.ArrayList<>();
                for (var policy : policies) {
                    associated.add(java.util.Map.of("id", policy.stringValue()));
                }
                expectGet(
                        server, url + "/associatedPolicies", mapper.writeValueAsString(associated));
                if (url.contains("/permission/scope/")) {
                    for (String relation : List.of("resources", "scopes")) {
                        var values = mapper.readTree(responseBody).get(relation);
                        if (values != null) {
                            List<java.util.Map<String, String>> linked =
                                    new java.util.ArrayList<>();
                            for (var value : values) {
                                linked.add(java.util.Map.of("id", value.stringValue()));
                            }
                            expectGet(
                                    server,
                                    url + "/" + relation,
                                    mapper.writeValueAsString(linked));
                        }
                    }
                }
            }
        }
    }

    private void expectGroup(MockRestServiceServer server, String path, String id) {
        expectGet(
                server,
                HOST + "/admin/realms/giaokhoa/group-by-path" + path,
                "{\"id\":\"" + id + "\",\"path\":\"" + path + "\"}");
    }

    private void expectCreate(MockRestServiceServer server, String url, String response) {
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED).body(response));
        expectCreatedLeaf(server, url, response);
    }

    private void expectCreate(
            MockRestServiceServer server, String url, String response, String expectedJson) {
        server.expect(requestTo(url))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(expectedJson))
                .andRespond(withStatus(HttpStatus.CREATED).body(response));
        expectCreatedLeaf(server, url, response);
        if ((ROOT + "/policy/aggregate").equals(url)) {
            var json = new tools.jackson.databind.json.JsonMapper();
            var desired = json.readTree(expectedJson);
            var id = json.readTree(response).get("id").stringValue();
            expectGet(
                    server,
                    ROOT + "/policy/aggregate/" + id,
                    "{\"id\":\""
                            + id
                            + "\",\"logic\":\"POSITIVE\",\"decisionStrategy\":\""
                            + desired.get("decisionStrategy").stringValue()
                            + "\",\"policies\":"
                            + desired.get("policies")
                            + "}");
        }
    }

    private void expectLeafRepair(
            MockRestServiceServer server, String type, String id, String expectedField) {
        server.expect(requestTo(ROOT + "/policy/" + type + "/" + id))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{" + expectedField + "}"))
                .andRespond(withNoContent());
        expectLeaf(server, id);
    }

    private void expectCreatedLeaf(MockRestServiceServer server, String url, String response) {
        if (url.startsWith(ROOT + "/policy/") && !url.endsWith("/aggregate")) {
            var created = new tools.jackson.databind.json.JsonMapper().readTree(response);
            expectLeaf(server, created.get("id").stringValue());
        }
    }

    private void expectLeaf(MockRestServiceServer server, String id) {
        String type;
        String body;
        switch (id) {
            case "pol-elig", "pol-mgr", "pol-own" -> {
                type = "group";
                String path =
                        switch (id) {
                            case "pol-elig" -> ELIGIBLE;
                            case "pol-mgr" -> MANAGERS;
                            default -> OWNERS;
                        };
                String group =
                        switch (id) {
                            case "pol-elig" -> "g-elig";
                            case "pol-mgr" -> "g-mgr";
                            default -> "g-own";
                        };
                body =
                        "{\"id\":\""
                                + id
                                + "\",\"name\":\"gk-group:"
                                + path
                                + "\",\"type\":\"group\",\"logic\":\"POSITIVE\","
                                + "\"groups\":[{\"id\":\""
                                + group
                                + "\",\"extendChildren\":false}]}";
            }
            case "pol-from", "pol-until" -> {
                type = "time";
                boolean until = "pol-until".equals(id);
                String boundary = until ? UNTIL.toString() : FROM.toString();
                String date = until ? "2030-02-01 00:00:00" : "2030-01-01 00:00:00";
                body =
                        "{\"id\":\""
                                + id
                                + "\",\"name\":\"gk-valid-"
                                + (until ? "until:" : "from:")
                                + boundary
                                + "\",\"type\":\"time\",\"logic\":\""
                                + (until ? "NEGATIVE" : "POSITIVE")
                                + "\",\"notBefore\":\""
                                + date
                                + "\"}";
            }
            case "pol-user" -> {
                type = "user";
                body =
                        "{\"id\":\"pol-user\",\"name\":\"gk-user:user-123\","
                                + "\"type\":\"user\",\"logic\":\"POSITIVE\",\"users\":[\"user-123\"]}";
            }
            default -> throw new IllegalArgumentException("Unexpected leaf policy: " + id);
        }
        expectGet(server, ROOT + "/policy/" + type + "/" + id, body);
    }

    private PolicyGraphWriter writer(RestClient.Builder builder) {
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("giaokhoa-provisioner")
                        .clientId("test-provisioner")
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri(HOST + "/realms/giaokhoa/protocol/openid-connect/token")
                        .build();
        OAuth2AccessToken token =
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "server-token",
                        Instant.now(),
                        Instant.now().plusSeconds(300));
        OAuth2AuthorizedClient authorized =
                new OAuth2AuthorizedClient(registration, "giaokhoa-provisioner", token);
        OAuth2AuthorizedClientManager manager = _ -> authorized;
        return new PolicyGraphWriter(builder, HOST, "giaokhoa", manager, "giaokhoa-api");
    }
}
