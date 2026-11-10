package com.giaokhoa.verification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Ánh xạ chuyển tiếp của bảng {@code teaching_assignment} cho điểm kiểm tra nguồn giáo viên phía
 * verification. Entity đầy đủ thuộc module teaching; bản này sẽ bị xoá khi đợt JPA land.
 */
@Entity(name = "VerificationTeachingAssignment")
@Table(name = "teaching_assignment")
public class TeachingAssignment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "course_subject_id", nullable = false)
    private UUID courseSubjectId;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    protected TeachingAssignment() {}

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getCourseSubjectId() {
        return courseSubjectId;
    }

    public String getState() {
        return state;
    }
}
