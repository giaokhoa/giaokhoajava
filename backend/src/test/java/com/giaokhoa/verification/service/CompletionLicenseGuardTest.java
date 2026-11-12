package com.giaokhoa.verification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.resources.model.Resource;
import com.giaokhoa.resources.model.ResourceDependency;
import com.giaokhoa.resources.model.ShareGrant;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.verification.repository.VerificationResourceDependencyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CompletionLicenseGuardTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ShareGrantRepository grants = mock(ShareGrantRepository.class);
    private final VerificationResourceDependencyRepository dependencies =
            mock(VerificationResourceDependencyRepository.class);
    private final CompletionLicenseGuard guard =
            new CompletionLicenseGuard(resources, grants, dependencies);

    private void sourceOwnedBy(UUID resourceId, UUID owner) {
        Resource resource = mock(Resource.class);
        when(resource.getOwnerWorkspaceId()).thenReturn(owner);
        when(resources.findById(resourceId)).thenReturn(Optional.of(resource));
    }

    private ShareGrant grant(
            ShareGrant.Mode mode, @Nullable Instant expiresAt, boolean permitsUse) {
        ShareGrant grant = mock(ShareGrant.class);
        when(grant.getState()).thenReturn(ShareGrant.State.ACCEPTED);
        when(grant.getMode()).thenReturn(mode);
        when(grant.getExpiresAt()).thenReturn(expiresAt);
        when(grant.getValidFrom()).thenReturn(NOW.minusSeconds(3600));
        when(grant.getRecipientWorkspaceId()).thenReturn(WORKSPACE);
        when(grant.getAllowedWorkspaceIds()).thenReturn(List.of());
        when(grant.getActions()).thenReturn(List.of(permitsUse ? "use" : "content.read"));
        return grant;
    }

    private void sourceHasGrant(ShareGrant value) {
        when(grants
                        .findByRecipientWorkspaceIdAndSourceResourceIdAndSourceVersionIdAndStateOrderByCreatedAtAscIdAsc(
                                WORKSPACE, SOURCE, VERSION, ShareGrant.State.ACCEPTED))
                .thenReturn(List.of(value));
    }

    private void assertNotDurable() {
        HttpProblemException error =
                assertThrows(
                        HttpProblemException.class,
                        () -> guard.requireDurableSources(WORKSPACE, SOURCE, VERSION, NOW));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("COMPLETION_POLICY_SOURCE_NOT_DURABLE", error.getCode());
    }

    @Test
    @DisplayName("Nguồn do workspace sở hữu không phụ thuộc grant thì cho phép bảo đảm hoàn tất")
    void workspaceOwnerCanGuarantee() {
        sourceOwnedBy(SOURCE, WORKSPACE);
        assertDoesNotThrow(() -> guard.requireDurableSources(WORKSPACE, SOURCE, VERSION, NOW));
    }

    @Test
    @DisplayName("Nguồn REVOCABLE đang ACCEPTED vẫn không đủ để bảo đảm chấm/công bố")
    void revocableCannotGuarantee() {
        sourceOwnedBy(SOURCE, UUID.randomUUID());
        sourceHasGrant(grant(ShareGrant.Mode.REVOCABLE, null, true));
        assertNotDurable();
    }

    @Test
    @DisplayName("LONG_TERM hữu hạn bị từ chối dù ngày hết hạn nằm sau ngày giao và hạn nộp")
    void finiteLongTermCannotGuarantee() {
        sourceOwnedBy(SOURCE, UUID.randomUUID());
        sourceHasGrant(grant(ShareGrant.Mode.LONG_TERM, NOW.plusSeconds(365L * 86400), true));
        assertNotDurable();
    }

    @Test
    @DisplayName(
            "LONG_TERM vô hạn còn hiệu lực và có use được chấp nhận; chỉ content.read không đủ")
    void unboundedLongTermMustPermitUse() {
        sourceOwnedBy(SOURCE, UUID.randomUUID());
        sourceHasGrant(grant(ShareGrant.Mode.LONG_TERM, null, true));
        assertDoesNotThrow(() -> guard.requireDurableSources(WORKSPACE, SOURCE, VERSION, NOW));
        sourceHasGrant(grant(ShareGrant.Mode.LONG_TERM, null, false));
        assertNotDurable();
    }

    @Test
    @DisplayName("Nguồn sở hữu không hợp thức hóa thành phần closure được chia sẻ REVOCABLE")
    void componentRevocableRejectsWholeClosure() {
        UUID component = UUID.randomUUID();
        UUID componentVersion = UUID.randomUUID();
        sourceOwnedBy(SOURCE, WORKSPACE);
        sourceOwnedBy(component, UUID.randomUUID());
        when(dependencies.findByDerivedVersionIdIn(List.of(VERSION)))
                .thenReturn(
                        List.of(
                                new ResourceDependency(
                                        UUID.randomUUID(),
                                        SOURCE,
                                        VERSION,
                                        component,
                                        componentVersion,
                                        null,
                                        "{}")));
        assertNotDurable();
    }

    @Test
    @DisplayName("Bản sao qua grant REVOCABLE vẫn bị giới hạn, dù owner của bản sao là workspace")
    void derivedLocalCopyCannotBypassRevocableProvenance() {
        sourceOwnedBy(SOURCE, WORKSPACE);
        UUID grantId = UUID.randomUUID();
        when(dependencies.findByDerivedVersionIdIn(List.of(VERSION)))
                .thenReturn(
                        List.of(
                                new ResourceDependency(
                                        UUID.randomUUID(),
                                        SOURCE,
                                        VERSION,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        grantId,
                                        "{}")));
        ShareGrant revocable = grant(ShareGrant.Mode.REVOCABLE, null, true);
        when(grants.findById(grantId)).thenReturn(Optional.of(revocable));
        assertNotDurable();
    }
}
