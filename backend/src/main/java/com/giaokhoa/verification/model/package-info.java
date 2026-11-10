// Entity JPA được Hibernate khởi tạo qua constructor không tham số và gán field bằng
// reflection, nên mặc định non-null theo package không mô hình hoá được vòng đời này;
// nullness của từng thành viên được khai báo tường minh tại chính thành viên đó.
// Bốn entity mẫu là aggregate thật của module; các entity chuyển tiếp (SubscriptionPeriod,
// CourseSubject, SchoolClass, Course, Subject, Enrollment, ClassStudent, TeachingAssignment)
// chỉ mang các cột phía verification/guardians cần đọc và sẽ bị xoá khi repository của module
// sở tại (tenancy/teaching) land trong cùng đợt JPA.
@NullUnmarked
package com.giaokhoa.verification.model;

import org.jspecify.annotations.NullUnmarked;
