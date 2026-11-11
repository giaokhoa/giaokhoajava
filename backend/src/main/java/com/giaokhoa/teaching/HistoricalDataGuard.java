package com.giaokhoa.teaching;

import com.giaokhoa.shared.http.ErrorCodes;
import com.giaokhoa.shared.http.HttpProblemException;
import com.giaokhoa.teaching.repository.TeachingSampleActivityRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Guard bảo lưu lịch sử của module giảng dạy: lớp, khóa hoặc môn–khóa đã phát sinh hoạt động được
 * giao thì không được xóa. Các phương thức {@code require*Deletable} chặn yêu cầu xóa cứng đối
 * tượng đã có lịch sử bằng 409 TEACHING_OBJECT_HAS_HISTORY. Dữ liệu của đối tượng được giữ nguyên ở
 * trạng thái chỉ đọc theo các nguồn quyền còn hiệu lực. Đối tượng chưa có lịch sử thì cho qua.
 *
 * <p>Các đường thu hồi của {@link ClassService} và {@link CourseService} KHÔNG qua guard này. Thu
 * hồi là chuyển trạng thái giữ lịch sử, không phải xóa. Nó vẫn là đường hợp lệ kể cả sau khi gói
 * hết hạn, và việc thu hồi không xóa dòng nào.
 *
 * <p>Các quan hệ chưa phát sinh lịch sử học tập (roster, ghi danh, phân công) không qua guard này.
 * Bài nộp và kết quả luôn treo dưới một hoạt động, nên kiểm tra tồn tại hoạt động là đủ cho cả
 * chuỗi lịch sử.
 */
@Component
public class HistoricalDataGuard {

    private final TeachingSampleActivityRepository activities;

    public HistoricalDataGuard(TeachingSampleActivityRepository activities) {
        this.activities = activities;
    }

    public boolean classHasHistory(UUID workspaceId, UUID classId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(classId, "classId");
        return activities.existsByWorkspaceIdAndClassId(workspaceId, classId);
    }

    public boolean courseHasHistory(UUID workspaceId, UUID courseId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseId, "courseId");
        return activities.existsByWorkspaceIdAndCourseId(workspaceId, courseId);
    }

    public boolean courseSubjectHasHistory(UUID workspaceId, UUID courseSubjectId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(courseSubjectId, "courseSubjectId");
        return activities.existsByWorkspaceIdAndCourseSubjectId(workspaceId, courseSubjectId);
    }

    public void requireClassDeletable(UUID workspaceId, UUID classId) {
        if (classHasHistory(workspaceId, classId)) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.TEACHING_OBJECT_HAS_HISTORY,
                    "Lớp đã có bài được giao nên không xóa được; dữ liệu và lịch sử của lớp"
                            + " được giữ nguyên.");
        }
    }

    public void requireCourseDeletable(UUID workspaceId, UUID courseId) {
        if (courseHasHistory(workspaceId, courseId)) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.TEACHING_OBJECT_HAS_HISTORY,
                    "Khóa đã có bài được giao nên không xóa được; dữ liệu và lịch sử của khóa"
                            + " được giữ nguyên.");
        }
    }

    public void requireCourseSubjectDeletable(UUID workspaceId, UUID courseSubjectId) {
        if (courseSubjectHasHistory(workspaceId, courseSubjectId)) {
            throw new HttpProblemException(
                    HttpStatus.CONFLICT,
                    ErrorCodes.TEACHING_OBJECT_HAS_HISTORY,
                    "Môn trong khóa đã có bài được giao nên không gỡ được; dữ liệu và lịch sử"
                            + " của môn được giữ nguyên.");
        }
    }
}
