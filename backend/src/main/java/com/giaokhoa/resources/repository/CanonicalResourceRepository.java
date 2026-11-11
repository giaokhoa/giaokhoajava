package com.giaokhoa.resources.repository;

import com.giaokhoa.resources.model.ResourceVersion;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Repository canonical dạng Spring Data của mặt phẳng tài nguyên (K16): thay bản JDBC cũ ở package
 * data. Cấu trúc theo mẫu fragment của Spring Data — interface này chỉ hợp thành, phần truy vấn tự
 * cài nằm ở fragment {@link CanonicalResourceQueries}.
 *
 * <p>ĐIỂM GHIM NGỮ CẢNH HỆ THỐNG: khi hạ tầng schema-per-tenant của Wave 0 bật, workspace đã cấp
 * schema thì các đường đọc tài nguyên phân giải sang schema tenant trước schema công khai. Mặt
 * phẳng tài nguyên của hệ thống là tài nguyên dùng chung DUY NHẤT theo quyết định tách tenant, nên
 * mọi caller của repository này phải ghim ngữ cảnh hệ thống tường minh (search_path về schema công
 * khai/không tenant) trước khi đọc — nếu không, bản ghi hệ thống sẽ vô hình với workspace đã cấp
 * schema. Điểm ghim thuộc hạ tầng tenancy của Wave 0; repository này không tự đổi ngữ cảnh.
 */
public interface CanonicalResourceRepository
        extends Repository<ResourceVersion, UUID>, CanonicalResourceQueries {}
