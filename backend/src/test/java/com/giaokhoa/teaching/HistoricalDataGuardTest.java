package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.repository.TeachingSampleActivityRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Khóa hợp đồng của {@link HistoricalDataGuard}: lịch sử của một phạm vi là tồn tại hoạt động được
 * giao trong đúng phạm vi đó, và đường yêu cầu xóa chỉ bị chặn khi lịch sử tồn tại.
 */
class HistoricalDataGuardTest {

    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID COURSE_ID = UUID.randomUUID();
    private static final UUID COURSE_SUBJECT_ID = UUID.randomUUID();

    private final TeachingSampleActivityRepository activities =
            mock(TeachingSampleActivityRepository.class);
    private final HistoricalDataGuard guard = new HistoricalDataGuard(activities);

    @Test
    @DisplayName("Môn–khóa có hoạt động được giao thì có lịch sử, chưa có hoạt động thì không")
    void courseSubjectHistoryFollowsAssignedActivities() {
        when(activities.existsByWorkspaceIdAndCourseSubjectId(WORKSPACE, COURSE_SUBJECT_ID))
                .thenReturn(true, false);

        assertTrue(guard.courseSubjectHasHistory(WORKSPACE, COURSE_SUBJECT_ID));
        assertFalse(guard.courseSubjectHasHistory(WORKSPACE, COURSE_SUBJECT_ID));
    }

    @Test
    @DisplayName("Lịch sử của khóa và lớp suy từ hoạt động trên mọi môn–khóa thuộc phạm vi đó")
    void courseAndClassHistoryFollowAssignedActivities() {
        when(activities.existsByWorkspaceIdAndCourseId(WORKSPACE, COURSE_ID)).thenReturn(true);
        when(activities.existsByWorkspaceIdAndClassId(WORKSPACE, CLASS_ID)).thenReturn(false);

        assertTrue(guard.courseHasHistory(WORKSPACE, COURSE_ID));
        assertFalse(guard.classHasHistory(WORKSPACE, CLASS_ID));
    }

    @Test
    @DisplayName("Yêu cầu xóa đối tượng đã có lịch sử bị từ chối 409 TEACHING_OBJECT_HAS_HISTORY")
    void requireDeletableRejectsObjectsWithHistory() {
        when(activities.existsByWorkspaceIdAndCourseSubjectId(WORKSPACE, COURSE_SUBJECT_ID))
                .thenReturn(true);

        HttpProblemException problem =
                assertThrows(
                        HttpProblemException.class,
                        () -> guard.requireCourseSubjectDeletable(WORKSPACE, COURSE_SUBJECT_ID));

        assertEquals(HttpStatus.CONFLICT, problem.getStatus());
        assertEquals(ErrorCodes.TEACHING_OBJECT_HAS_HISTORY, problem.getCode());
    }

    @Test
    @DisplayName("Yêu cầu xóa đối tượng chưa có lịch sử được đi tiếp không ném lỗi")
    void requireDeletableAllowsObjectsWithoutHistory() {
        when(activities.existsByWorkspaceIdAndClassId(WORKSPACE, CLASS_ID)).thenReturn(false);
        when(activities.existsByWorkspaceIdAndCourseId(WORKSPACE, COURSE_ID)).thenReturn(false);

        assertDoesNotThrow(() -> guard.requireClassDeletable(WORKSPACE, CLASS_ID));
        assertDoesNotThrow(() -> guard.requireCourseDeletable(WORKSPACE, COURSE_ID));
    }
}
