package com.giaokhoa.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Đăng ký repository của module authorization (kèm fragment riêng của chúng) cho một context tối
 * thiểu do test tự dựng.
 *
 * <p>Context tối thiểu dùng lại EntityManagerFactory, JdbcTemplate và transaction manager của làn
 * runtime.
 *
 * <p>Class mang {@code @TestConfiguration} ở top-level nên không bị nhặt ngoài ý muốn:
 *
 * <ul>
 *   <li>Component scan bỏ qua {@code @TestConfiguration} ở top-level. Đây là cơ chế Spring Boot
 *       Reference mô tả ở mục "Excluding Test Configuration".
 *   <li>Nếu để {@code @Configuration} thường, component scan của ứng dụng quét cả test-classes. Khi
 *       đó chỉ ba repository authorization được đăng ký vào mọi context test. Auto-configuration
 *       repository vì thế lùi bước, và mọi repository khác không được đăng ký.
 *   <li>Package {@code testsupport} là package anh em của các package test. Nó không chứa class
 *       test nào và không phải package cha của package test nào. Cơ chế tự phát hiện
 *       {@code @TestConfiguration} của Spring Boot test vì vậy cũng không nhặt class này.
 * </ul>
 *
 * <p>Cả hai nơi dùng đều tự dựng AnnotationConfigApplicationContext và đăng ký tường minh class
 * này.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackages = "com.giaokhoa.authorization.repository")
public class AuthorizationFragmentRepositories {}
