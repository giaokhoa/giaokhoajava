package com.giaokhoa.verification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Ánh xạ chuyển tiếp của bảng {@code course} cho các điểm đọc phía verification và guardians.
 * Entity đầy đủ thuộc module teaching; bản này sẽ bị xoá khi đợt JPA land.
 */
@Entity(name = "VerificationCourse")
@Table(name = "course")
public class Course {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    protected Course() {}

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getClassId() {
        return classId;
    }

    public String getName() {
        return name;
    }

    public String getState() {
        return state;
    }
}
