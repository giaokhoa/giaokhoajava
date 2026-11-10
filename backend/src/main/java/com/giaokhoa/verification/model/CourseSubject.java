package com.giaokhoa.verification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Ánh xạ chuyển tiếp của bảng {@code course_subject} cho các điểm đọc phía verification và
 * guardians. Entity đầy đủ thuộc module teaching; bản này chỉ mang các cột hai phía cần và sẽ bị
 * xoá, trỏ lại repository của teaching khi đợt JPA land.
 */
@Entity(name = "VerificationCourseSubject")
@Table(name = "course_subject")
public class CourseSubject {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    protected CourseSubject() {}

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getClassId() {
        return classId;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getState() {
        return state;
    }
}
