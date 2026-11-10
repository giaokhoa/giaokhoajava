package com.giaokhoa.teaching;

import com.giaokhoa.teaching.repository.ClassStudentRepository;
import com.giaokhoa.teaching.repository.CourseRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.teaching.repository.HomeroomAssignmentRepository;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.SubjectRepository;
import com.giaokhoa.teaching.repository.TeachingAssignmentRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/**
 * Bộ repository JPA của module giảng dạy gắn với một DataSource riêng của test (thường là role
 * runtime không BYPASSRLS trên SingleConnectionDataSource), thay cho việc truyền JdbcTemplate vào
 * tầng repository như trước đây. EntityManager là bản dùng chung theo transaction của thread nên
 * các repository tham gia đúng transaction mà test mở bằng {@link #transactionManager}; transaction
 * manager được gắn cùng DataSource để các đường JDBC còn lại trong cùng test (set_config của
 * WorkspaceTransaction, projector của module khác) dùng chung một connection và một transaction.
 */
final class RuntimeJpaRepositories implements AutoCloseable {
    final EntityManagerFactory entityManagerFactory;
    final JpaTransactionManager transactionManager;
    final SchoolClassRepository schoolClasses;
    final ClassStudentRepository classStudents;
    final CourseRepository courses;
    final CourseSubjectRepository courseSubjects;
    final SubjectRepository subjects;
    final EnrollmentRepository enrollments;
    final TeachingAssignmentRepository teachingAssignments;
    final HomeroomAssignmentRepository homeroomAssignments;
    final TeachingPageRepository pages;

    RuntimeJpaRepositories(DataSource dataSource) {
        var emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(dataSource);
        emfBean.setPersistenceUnitName("teaching-runtime");
        emfBean.setPackagesToScan("com.giaokhoa.teaching.model");
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.getJpaPropertyMap().put("hibernate.hbm2ddl.auto", "validate");
        emfBean.afterPropertiesSet();
        this.entityManagerFactory = Objects.requireNonNull(emfBean.getObject());
        this.transactionManager = new JpaTransactionManager(entityManagerFactory);
        transactionManager.setDataSource(dataSource);
        var factory =
                new JpaRepositoryFactory(
                        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
        this.schoolClasses = factory.getRepository(SchoolClassRepository.class);
        this.classStudents = factory.getRepository(ClassStudentRepository.class);
        this.courses = factory.getRepository(CourseRepository.class);
        this.courseSubjects = factory.getRepository(CourseSubjectRepository.class);
        this.subjects = factory.getRepository(SubjectRepository.class);
        this.enrollments = factory.getRepository(EnrollmentRepository.class);
        this.teachingAssignments = factory.getRepository(TeachingAssignmentRepository.class);
        this.homeroomAssignments = factory.getRepository(HomeroomAssignmentRepository.class);
        this.pages =
                new TeachingPageRepository(
                        schoolClasses,
                        classStudents,
                        courses,
                        enrollments,
                        teachingAssignments,
                        homeroomAssignments);
    }

    @Override
    public void close() {
        entityManagerFactory.close();
    }
}
