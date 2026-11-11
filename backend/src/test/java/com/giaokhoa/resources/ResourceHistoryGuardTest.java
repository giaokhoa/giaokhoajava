package com.giaokhoa.resources;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.resources.repository.CorrectionNoticeRepository;
import com.giaokhoa.resources.repository.LibraryItemRepository;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.ResourcesSampleActivityRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Khóa hợp đồng của {@link ResourceHistoryGuard}: một version có lịch sử khi đã được lưu vào kho,
 * đã giao làm bài, đã làm nguồn phái sinh hoặc đã dính đính chính; một resource có lịch sử khi đã
 * từng được chia sẻ hoặc có ít nhất một version có lịch sử.
 */
class ResourceHistoryGuardTest {

    private static final UUID RESOURCE_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();

    private final ResourcesSampleActivityRepository activities =
            mock(ResourcesSampleActivityRepository.class);
    private final LibraryItemRepository libraryItems = mock(LibraryItemRepository.class);
    private final ResourceDependencyRepository dependencies =
            mock(ResourceDependencyRepository.class);
    private final CorrectionNoticeRepository corrections = mock(CorrectionNoticeRepository.class);
    private final ShareGrantRepository grants = mock(ShareGrantRepository.class);
    private final ResourceVersionRepository versions = mock(ResourceVersionRepository.class);
    private final ResourceHistoryGuard guard =
            new ResourceHistoryGuard(
                    activities, libraryItems, dependencies, corrections, grants, versions);

    @Test
    @DisplayName("Version chưa được lưu, giao, phái sinh hay đính chính thì chưa có lịch sử")
    void versionWithoutAnyReferenceHasNoHistory() {
        assertFalse(guard.versionHasHistory(RESOURCE_ID, VERSION_ID));
        assertDoesNotThrow(() -> guard.requireVersionDeletable(RESOURCE_ID, VERSION_ID));
    }

    @Test
    @DisplayName("Version đã được lưu vào kho thì có lịch sử và yêu cầu xóa bị từ chối 409")
    void savedVersionHasHistory() {
        when(libraryItems.existsByResourceIdAndVersionId(RESOURCE_ID, VERSION_ID)).thenReturn(true);

        assertTrue(guard.versionHasHistory(RESOURCE_ID, VERSION_ID));
        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> guard.requireVersionDeletable(RESOURCE_ID, VERSION_ID));
        assertEquals(HttpStatus.CONFLICT, problem.getStatus());
        assertEquals(ErrorCodes.RESOURCE_VERSION_HAS_HISTORY, problem.getCode());
    }

    @Test
    @DisplayName(
            "Version đã giao làm bài, đã làm nguồn phái sinh hoặc đã dính đính chính đều có lịch sử")
    void assignedDerivedOrCorrectedVersionHasHistory() {
        when(activities.existsByResourceIdAndVersionId(RESOURCE_ID, VERSION_ID)).thenReturn(true);
        assertTrue(guard.versionHasHistory(RESOURCE_ID, VERSION_ID));

        ResourceHistoryGuard derivedGuard = guardWithOnlyDependency();
        assertTrue(derivedGuard.versionHasHistory(RESOURCE_ID, VERSION_ID));

        ResourceHistoryGuard correctedGuard = guardWithOnlyCorrection();
        assertTrue(correctedGuard.versionHasHistory(RESOURCE_ID, VERSION_ID));
    }

    private ResourceHistoryGuard guardWithOnlyDependency() {
        ResourceDependencyRepository onlyDependencies = mock(ResourceDependencyRepository.class);
        when(onlyDependencies.existsBySourceVersionId(VERSION_ID)).thenReturn(true);
        return new ResourceHistoryGuard(
                mock(ResourcesSampleActivityRepository.class),
                mock(LibraryItemRepository.class),
                onlyDependencies,
                mock(CorrectionNoticeRepository.class),
                mock(ShareGrantRepository.class),
                mock(ResourceVersionRepository.class));
    }

    private ResourceHistoryGuard guardWithOnlyCorrection() {
        CorrectionNoticeRepository onlyCorrections = mock(CorrectionNoticeRepository.class);
        when(onlyCorrections.existsByAffectedVersionId(VERSION_ID)).thenReturn(true);
        return new ResourceHistoryGuard(
                mock(ResourcesSampleActivityRepository.class),
                mock(LibraryItemRepository.class),
                mock(ResourceDependencyRepository.class),
                onlyCorrections,
                mock(ShareGrantRepository.class),
                mock(ResourceVersionRepository.class));
    }

    @Test
    @DisplayName("Resource đã từng được chia sẻ thì có lịch sử dù mọi version đều chưa có lịch sử")
    void sharedResourceHasHistory() {
        when(grants.existsBySourceResourceId(RESOURCE_ID)).thenReturn(true);

        assertTrue(guard.resourceHasHistory(RESOURCE_ID));
        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> guard.requireResourceDeletable(RESOURCE_ID));
        assertEquals(HttpStatus.CONFLICT, problem.getStatus());
        assertEquals(ErrorCodes.RESOURCE_HAS_HISTORY, problem.getCode());
    }

    @Test
    @DisplayName("Resource có một version đã có lịch sử thì chính resource cũng có lịch sử")
    void resourceWithHistoricalVersionHasHistory() {
        when(grants.existsBySourceResourceId(RESOURCE_ID)).thenReturn(false);
        when(versions.findIdsByResourceId(RESOURCE_ID)).thenReturn(List.of(VERSION_ID));
        when(activities.existsByResourceIdAndVersionId(RESOURCE_ID, VERSION_ID)).thenReturn(true);

        assertTrue(guard.resourceHasHistory(RESOURCE_ID));
    }

    @Test
    @DisplayName("Resource chưa chia sẻ và mọi version đều chưa có lịch sử thì chưa có lịch sử")
    void untouchedResourceHasNoHistory() {
        when(grants.existsBySourceResourceId(RESOURCE_ID)).thenReturn(false);
        when(versions.findIdsByResourceId(RESOURCE_ID)).thenReturn(List.of(VERSION_ID));

        assertFalse(guard.resourceHasHistory(RESOURCE_ID));
        assertDoesNotThrow(() -> guard.requireResourceDeletable(RESOURCE_ID));
    }
}
