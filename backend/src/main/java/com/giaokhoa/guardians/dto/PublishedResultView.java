package com.giaokhoa.guardians.dto;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * DTO duy nhất của đường đọc kết quả đã công bố, đúng hình dạng hợp đồng {@code
 * PublishedResultView}: chỉ các trường hiển thị của kết quả đã công bố — không submission, không
 * nội dung bài làm/đáp án, không điểm/nhận xét dạng bản nháp nội bộ, không người chấm và không
 * workspace. Tên hiển thị của học sinh là username của directory, cùng quy ước với view thành viên.
 */
public record PublishedResultView(
        UUID id,
        Student student,
        String className,
        String courseName,
        String subjectName,
        @Nullable String score,
        @Nullable String comment,
        @Nullable Instant publishedAt) {

    /** Tham chiếu học sinh tối thiểu của một kết quả: định danh và tên hiển thị. */
    public record Student(UUID id, String displayName) {}
}
