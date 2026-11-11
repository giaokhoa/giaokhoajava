package com.giaokhoa.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Đăng ký các kho truy cập dữ liệu (repository) của phân hệ authorization, kèm phần cài đặt riêng
 * (fragment) của chúng, cho một ngữ cảnh (context) tối thiểu do kiểm thử tự dựng.
 *
 * <p>Ngữ cảnh tối thiểu dùng lại EntityManagerFactory, JdbcTemplate và trình quản lý giao dịch
 * (transaction manager) của làn chạy kiểm thử (runtime).
 *
 * <p>Lớp mang {@code @TestConfiguration} ở cấp ngoài cùng (top-level) nên không bị nhặt ngoài ý
 * muốn:
 *
 * <ul>
 *   <li>Việc quét thành phần (component scan) bỏ qua {@code @TestConfiguration} ở cấp ngoài cùng.
 *       Đây là cơ chế tài liệu Spring Boot Reference mô tả ở mục "Excluding Test Configuration".
 *   <li>Nếu để {@code @Configuration} thường, việc quét thành phần của ứng dụng quét cả thư mục lớp
 *       kiểm thử (test-classes). Khi đó chỉ ba kho truy cập dữ liệu của phân hệ authorization được
 *       đăng ký vào mọi ngữ cảnh kiểm thử. Cấu hình tự động (auto-configuration) của kho truy cập
 *       dữ liệu vì thế lùi bước, và mọi kho truy cập dữ liệu khác không được đăng ký.
 *   <li>Gói (package) {@code testsupport} là gói anh em của các gói kiểm thử. Nó không chứa lớp
 *       kiểm thử nào và không phải gói cha của gói kiểm thử nào. Cơ chế tự phát hiện
 *       {@code @TestConfiguration} của kiểm thử Spring Boot vì vậy cũng không nhặt lớp này.
 * </ul>
 *
 * <p>Cả hai nơi dùng đều tự dựng AnnotationConfigApplicationContext và đăng ký tường minh lớp này.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = "com.giaokhoa.authorization.repository")
public class AuthorizationFragmentRepositories {}
