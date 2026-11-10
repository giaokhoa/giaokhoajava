package testinfra;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Đăng ký repository của module authorization (kèm fragment riêng của chúng) cho một context tối
 * thiểu do test tự dựng, dùng lại EntityManagerFactory/JdbcTemplate/transaction manager của làn
 * runtime. Class này phải ở ngoài package {@code com.giaokhoa} và không được lồng trong class test:
 * component scan của ứng dụng quét toàn bộ {@code com.giaokhoa} kể cả test-classes, nên một
 * {@code @EnableJpaRepositories} hẹp ở đó sẽ đăng ký chỉ ba repository authorization vào mọi
 * context test rồi làm auto-configuration lùi bước (mọi repository khác không được đăng ký); còn
 * lồng trong class test thì nó thay luôn configuration chính của test đó.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = "com.giaokhoa.authorization.repository")
public class AuthorizationFragmentRepositories {}
