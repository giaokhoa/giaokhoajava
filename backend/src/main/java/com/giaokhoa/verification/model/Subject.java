package com.giaokhoa.verification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Ánh xạ chuyển tiếp của bảng {@code subject} (mặt phẳng hệ thống) cho điểm đọc tên môn phía
 * guardians. Entity đầy đủ thuộc module teaching; bản này sẽ bị xoá khi đợt JPA land.
 */
@Entity(name = "VerificationSubject")
@Table(name = "subject")
public class Subject {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    protected Subject() {}

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
