// Entity JPA được Hibernate khởi tạo qua constructor không tham số và gán field bằng
// reflection, nên mặc định non-null theo package không mô hình hoá được vòng đời này;
// nullness của từng thành viên được khai báo tường minh tại chính thành viên đó.
@NullUnmarked
package com.giaokhoa.subscriptions.model;

import org.jspecify.annotations.NullUnmarked;
