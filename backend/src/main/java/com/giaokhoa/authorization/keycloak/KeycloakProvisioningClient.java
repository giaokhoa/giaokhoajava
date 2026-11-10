package com.giaokhoa.authorization.keycloak;

import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.shared.http.KeycloakRequestFactory;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Client projection directory và realm group chỉ dùng phía server. ID của Keycloak là mờ (opaque);
 * ID workspace nghiệp vụ vẫn là UUID.
 */
@Component
public class KeycloakProvisioningClient {
    private static final int GROUP_PAGE_SIZE = 100;

    private final RestClient http;
    private final String baseUrl;
    private final String realm;
    private final OAuth2AuthorizedClientManager manager;

    @Autowired
    public KeycloakProvisioningClient(
            @Value("${giaokhoa.keycloak.admin-base-url}") String baseUrl,
            @Value("${spring.security.oauth2.client.provider.keycloak.issuer-uri}")
                    String issuerUri,
            @Value("${giaokhoa.keycloak.provisioner.client-id}") String clientId,
            @Value("${giaokhoa.keycloak.provisioner.client-secret}") String clientSecret) {
        this(
                RestClient.builder().requestFactory(KeycloakRequestFactory.create()),
                baseUrl,
                KeycloakProvisionerFactory.realmOf(issuerUri),
                KeycloakProvisionerFactory.clientManager(
                        baseUrl.replaceAll("/+$", ""),
                        KeycloakProvisionerFactory.realmOf(issuerUri),
                        clientId,
                        clientSecret));
    }

    public KeycloakProvisioningClient(
            RestClient.Builder builder, String baseUrl, String clientId, String clientSecret) {
        this(builder, baseUrl, "giaokhoa", clientId, clientSecret);
    }

    public KeycloakProvisioningClient(
            RestClient.Builder builder,
            String baseUrl,
            String realm,
            String clientId,
            String clientSecret) {
        this(
                builder,
                baseUrl,
                realm,
                KeycloakProvisionerFactory.clientManager(
                        baseUrl.replaceAll("/+$", ""), realm, clientId, clientSecret));
    }

    KeycloakProvisioningClient(
            RestClient.Builder builder, String baseUrl, OAuth2AuthorizedClientManager manager) {
        this(builder, baseUrl, "giaokhoa", manager);
    }

    KeycloakProvisioningClient(
            RestClient.Builder builder,
            String baseUrl,
            String realm,
            OAuth2AuthorizedClientManager manager) {
        this.http = builder.build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.realm = realm;
        this.manager = manager;
    }

    /** Mỗi center một Organization; tên ổn định ngăn tạo trùng organization khi retry. */
    public String ensureOrganization(@Nullable UUID workspaceId, @Nullable String displayName) {
        if (workspaceId == null || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Workspace ID and organization name are required");
        }
        String name = "center-" + workspaceId;
        Organization existing = findOrganization(name);
        if (existing != null) {
            return existing.id();
        }
        try {
            post(
                    endpoint("organizations"),
                    Map.of(
                            "name",
                            name,
                            "alias",
                            name,
                            "description",
                            displayName,
                            "enabled",
                            true));
        } catch (RestClientResponseException ex) {
            if (!ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw ex;
            }
            // Một retry đồng thời có thể đã tạo cùng organization ổn định đó.
        }
        Organization created = findOrganization(name);
        if (created == null) {
            throw new IllegalStateException("Keycloak organization not visible after creation");
        }
        return created.id();
    }

    /** Template được phép do hợp đồng authorization cố định, không phải do center chọn. */
    public String ensureRealmGroup(String path) {
        if (!NativePolicyTemplates.isGroupSource(path)) {
            throw new IllegalArgumentException("Unrecognized authorization group source");
        }
        String parentId = null;
        StringBuilder pathBuilder = new StringBuilder();
        for (String segment : path.substring(1).split("/")) {
            String currentPath = pathBuilder.append('/').append(segment).toString();
            Group group = findGroup(currentPath);
            if (group == null) {
                URI destination =
                        parentId == null
                                ? endpoint("groups")
                                : endpoint("groups", parentId, "children");
                try {
                    post(destination, Map.of("name", segment));
                } catch (RestClientResponseException ex) {
                    if (!ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                        throw ex;
                    }
                }
                group = findGroup(currentPath);
                if (group == null) {
                    throw new IllegalStateException(
                            "Keycloak group not visible after creation: " + currentPath);
                }
            }
            parentId = group.id();
        }
        return Objects.requireNonNull(parentId);
    }

    /** Đọc các realm group mà user là thành viên trực tiếp và đối chiếu path cố định. */
    public boolean isRealmGroupMember(String keycloakUserId, String groupPath) {
        if (!NativePolicyTemplates.isGroupSource(groupPath)) {
            throw new IllegalArgumentException("Unrecognized authorization group source");
        }
        return realmGroupPaths(keycloakUserId).contains(groupPath);
    }

    /** Snapshot trực tiếp trong lần gọi này; đọc đủ trang và không lưu quyền giữa các request. */
    public Set<String> realmGroupPaths(String keycloakUserId) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            throw new IllegalArgumentException("Keycloak user ID is required");
        }
        Set<String> paths = new HashSet<>();
        for (int first = 0; ; first += GROUP_PAGE_SIZE) {
            URI uri =
                    UriComponentsBuilder.fromUri(endpoint("users", keycloakUserId, "groups"))
                            .queryParam("first", first)
                            .queryParam("max", GROUP_PAGE_SIZE)
                            .build(true)
                            .toUri();
            Group[] groups = get(uri, Group[].class);
            for (Group group : groups) {
                if (group.path() == null || group.path().isBlank()) {
                    throw new IllegalStateException("Keycloak group has no path");
                }
                paths.add(group.path());
            }
            if (groups.length < GROUP_PAGE_SIZE) {
                return Set.copyOf(paths);
            }
        }
    }

    /** Membership độc lập với group của Organization; không ngụ ý policy kế thừa nào. */
    public void setRealmGroupMembership(String keycloakUserId, String groupPath, boolean member) {
        if (keycloakUserId == null || keycloakUserId.isBlank()) {
            throw new IllegalArgumentException("Keycloak user ID is required");
        }
        String groupId = member ? ensureRealmGroup(groupPath) : findExistingSourceGroup(groupPath);
        if (groupId == null) {
            // Không bao giờ báo một lần thu hồi là đã đồng bộ khi chưa xác định được group nguồn
            // của nó.
            throw new IllegalStateException(
                    "Cannot revoke membership: realm group does not exist: " + groupPath);
        }
        URI destination = endpoint("users", keycloakUserId, "groups", groupId);
        if (member) {
            put(destination);
        } else {
            delete(destination);
        }
    }

    private @Nullable String findExistingSourceGroup(String path) {
        if (!NativePolicyTemplates.isGroupSource(path)) {
            throw new IllegalArgumentException("Unrecognized authorization group source");
        }
        Group group = findGroup(path);
        return group == null ? null : group.id();
    }

    private @Nullable Organization findOrganization(String name) {
        // Keycloak "search" mặc định là substring; exact + paging tránh trượt tên khi
        // nhiều organization có cùng tiền tố (và không bao giờ chọn nhầm tên gần giống).
        final int pageSize = 100;
        for (int first = 0; ; first += pageSize) {
            URI uri =
                    UriComponentsBuilder.fromUri(endpoint("organizations"))
                            .queryParam("search", name)
                            .queryParam("exact", true)
                            .queryParam("first", first)
                            .queryParam("max", pageSize)
                            .build()
                            .encode()
                            .toUri();
            Organization[] organizations = get(uri, Organization[].class);
            Organization matched =
                    Arrays.stream(organizations)
                            .filter(org -> name.equals(org.name()) || name.equals(org.alias()))
                            .findFirst()
                            .orElse(null);
            if (matched != null) {
                return matched;
            }
            if (organizations.length < pageSize) {
                return null;
            }
        }
    }

    private @Nullable Group findGroup(String path) {
        // Tra cứu theo path chính xác tránh phân trang và đụng tên giữa các workspace.
        URI uri = URI.create(endpoint("group-by-path") + path);
        try {
            Group group = get(uri, Group.class);
            if (!path.equals(group.path()) || group.id() == null || group.id().isBlank()) {
                throw new IllegalStateException("Keycloak returned a group for an unexpected path");
            }
            return group;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return null;
            }
            throw ex;
        }
    }

    private URI endpoint(String... segments) {
        UriComponentsBuilder uri =
                UriComponentsBuilder.fromUriString(baseUrl).pathSegment("admin", "realms", realm);
        for (String segment : segments) {
            uri.pathSegment(segment);
        }
        return uri.build().encode().toUri();
    }

    private String accessToken() {
        var client =
                manager.authorize(
                        OAuth2AuthorizeRequest.withClientRegistrationId("giaokhoa-provisioner")
                                .principal("giaokhoa-provisioner")
                                .build());
        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException("Keycloak provisioner cannot obtain an access token");
        }
        return client.getAccessToken().getTokenValue();
    }

    private <T> T get(URI uri, Class<T> type) {
        T body =
                http.get()
                        .uri(uri)
                        .headers(headers -> headers.setBearerAuth(accessToken()))
                        .retrieve()
                        .body(type);
        if (body == null) {
            throw new IllegalStateException("Empty Keycloak directory response");
        }
        return body;
    }

    private void post(URI uri, Object payload) {
        http.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private void put(URI uri) {
        http.put()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private void delete(URI uri) {
        http.delete()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve()
                .toBodilessEntity();
    }

    private record Group(String id, String name, String path) {}

    private record Organization(String id, String name, String alias) {}
}
