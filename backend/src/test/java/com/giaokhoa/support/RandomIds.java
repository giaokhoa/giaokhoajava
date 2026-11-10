package com.giaokhoa.support;

import java.util.UUID;

/**
 * Sinh định danh cho unit test: giá trị chỉ cần phân biệt giữa các thực thể, không mang nghĩa nên
 * sinh ngẫu nhiên thay vì viết tay literal. Định danh nào giá trị tự mang nghĩa (ví dụ phải khớp dữ
 * liệu seed ngoài test) thì giữ literal cố định kèm lý do tại chỗ.
 */
public final class RandomIds {
    private RandomIds() {}

    public static UUID randomId() {
        return UUID.randomUUID();
    }
}
