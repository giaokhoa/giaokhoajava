// Entity JPA được Hibernate khởi tạo qua constructor không tham số và gán field bằng
// reflection, nên mặc định non-null theo package không mô hình hoá được vòng đời này;
// nullness của từng thành viên được khai báo tường minh tại chính thành viên đó.
// Bốn entity của gói hiện chưa có caller phía main; vai trò duy nhất của chúng là kiểm chứng
// mapping/schema ở tầng test tích hợp (SamplePrimitivesSchemaIT, SystemPracticeHistoryIT).
@NullUnmarked
package com.giaokhoa.verification.model;

import org.jspecify.annotations.NullUnmarked;
