package com.giaokhoa.verification.dto;

import java.util.UUID;

/**
 * Trạng thái hiệu lực của một môn–khóa cùng lớp và khóa chứa nó, cho tiền kiểm tra giao bài: chiếu
 * DTO qua constructor expression thay vì nạp entity của module teaching.
 */
public record CourseSubjectStateView(
        UUID subjectId, String subjectState, String classState, String courseState) {}
