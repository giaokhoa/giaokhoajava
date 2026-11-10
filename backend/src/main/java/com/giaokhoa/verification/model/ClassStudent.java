package com.giaokhoa.verification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Ánh xạ chuyển tiếp của bảng {@code class_student} cho các điểm đọc phía verification. Entity đầy
 * đủ thuộc module teaching; bản này sẽ bị xoá khi đợt JPA land.
 */
@Entity(name = "VerificationClassStudent")
@Table(name = "class_student")
public class ClassStudent {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "student_user_id", nullable = false)
    private UUID studentUserId;

    protected ClassStudent() {}

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getStudentUserId() {
        return studentUserId;
    }
}
