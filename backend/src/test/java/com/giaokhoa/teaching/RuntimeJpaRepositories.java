package com.giaokhoa.teaching;

import com.giaokhoa.authorization.repository.AuthorizationOperationRepository;
import com.giaokhoa.authorization.repository.AuthzPartitionRepository;
import com.giaokhoa.authorization.repository.AuthzProjectionRepository;
import com.giaokhoa.guardians.repository.GuardianLinkRepository;
import com.giaokhoa.identity.repository.UserAccountRepository;
import com.giaokhoa.resources.repository.LibraryItemRepository;
import com.giaokhoa.resources.repository.ResourceAssignmentRepository;
import com.giaokhoa.resources.repository.ResourceDependencyRepository;
import com.giaokhoa.resources.repository.ResourceRepository;
import com.giaokhoa.resources.repository.ResourceVersionRepository;
import com.giaokhoa.resources.repository.ResourcesAuthorizationPartitionRepository;
import com.giaokhoa.resources.repository.ResourcesSampleActivityRepository;
import com.giaokhoa.resources.repository.ResourcesUserAccountRepository;
import com.giaokhoa.resources.repository.ResourcesWorkspaceRepository;
import com.giaokhoa.resources.repository.ShareGrantRepository;
import com.giaokhoa.subscriptions.repository.SubscriptionPeriodRepository;
import com.giaokhoa.teaching.repository.ClassStudentRepository;
import com.giaokhoa.teaching.repository.CourseRepository;
import com.giaokhoa.teaching.repository.CourseSubjectRepository;
import com.giaokhoa.teaching.repository.EnrollmentRepository;
import com.giaokhoa.teaching.repository.HomeroomAssignmentRepository;
import com.giaokhoa.teaching.repository.SchoolClassRepository;
import com.giaokhoa.teaching.repository.SubjectRepository;
import com.giaokhoa.teaching.repository.TeachingAssignmentRepository;
import com.giaokhoa.teaching.repository.TeachingPageRepository;
import com.giaokhoa.tenancy.repository.CenterMembershipRepository;
import com.giaokhoa.tenancy.repository.WorkspaceRepository;
import com.giaokhoa.verification.repository.SampleResultRepository;
import com.giaokhoa.verification.repository.VerificationAuthorizationPartitionRepository;
import com.giaokhoa.verification.repository.VerificationCenterMembershipRepository;
import com.giaokhoa.verification.repository.VerificationUserAccountRepository;
import com.giaokhoa.verification.repository.VerificationWorkspaceRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import testinfra.AuthorizationFragmentRepositories;

/**
 * Bộ repository JPA gắn với một DataSource riêng của test (thường là một role không phải chủ sở hữu
 * bảng trên SingleConnectionDataSource), thay cho việc truyền JdbcTemplate vào tầng repository như
 * trước đây. EntityManager là bản dùng chung theo transaction của thread nên các repository tham
 * gia đúng transaction mà test mở bằng {@link #transactionManager}; transaction manager được gắn
 * cùng DataSource để các đường JDBC còn lại trong cùng test (search_path của WorkspaceTransaction,
 * projector của module khác) dùng chung một connection và một transaction.
 *
 * <p>Repository thuần giao diện lấy thẳng từ {@link JpaRepositoryFactory}; riêng các repository có
 * fragment cài đặt riêng của module authorization phải đi qua một context tối thiểu có {@code
 * EnableJpaRepositories} vì fragment chỉ được đăng ký ở tầng cấu hình đó (factory trần không tự
 * phát hiện fragment, đã kiểm chứng bằng thực nghiệm khi nối các đợt JPA). Cấu hình đó là {@link
 * AuthorizationFragmentRepositories}, đặt ngoài package {@code com.giaokhoa} để component scan của
 * ứng dụng không nhặt nó vào context chính.
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
    final WorkspaceRepository workspaces;
    final CenterMembershipRepository memberships;
    final UserAccountRepository accounts;
    final SubscriptionPeriodRepository periods;
    final AuthorizationOperationRepository operations;
    final AuthzPartitionRepository authzPartitions;
    final AuthzProjectionRepository authzProjections;
    final SampleResultRepository sampleResults;
    final com.giaokhoa.verification.repository.VerificationTeachingAssignmentRepository
            verificationTeachingAssignments;
    final com.giaokhoa.verification.repository.VerificationEnrollmentRepository
            verificationEnrollments;
    final VerificationAuthorizationPartitionRepository verificationPartitions;
    final VerificationCenterMembershipRepository verificationMemberships;
    final VerificationWorkspaceRepository verificationWorkspaces;
    final VerificationUserAccountRepository verificationAccounts;
    final GuardianLinkRepository guardianLinks;
    final ResourceVersionRepository resourceVersions;
    final ResourceRepository resources;
    final LibraryItemRepository libraryItems;
    final ShareGrantRepository shareGrants;
    final ResourceDependencyRepository resourceDependencies;
    final ResourceAssignmentRepository resourceAssignments;
    final ResourcesWorkspaceRepository resourcesWorkspaces;
    final ResourcesUserAccountRepository resourcesAccounts;
    final ResourcesSampleActivityRepository resourcesActivities;
    final ResourcesAuthorizationPartitionRepository resourcesPartitions;
    private final AnnotationConfigApplicationContext fragmentContext;

    RuntimeJpaRepositories(DataSource dataSource) {
        var emfBean = new LocalContainerEntityManagerFactoryBean();
        emfBean.setDataSource(dataSource);
        emfBean.setPersistenceUnitName("teaching-runtime");
        emfBean.setPackagesToScan("com.giaokhoa");
        emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        emfBean.getJpaPropertyMap().put("hibernate.hbm2ddl.auto", "validate");
        emfBean.getJpaPropertyMap()
                .put(
                        org.hibernate.cfg.AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER,
                        new com.giaokhoa.infrastructure.tenancy.TenantConnectionProvider(
                                dataSource));
        emfBean.getJpaPropertyMap()
                .put(
                        org.hibernate.cfg.AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER,
                        new com.giaokhoa.infrastructure.tenancy.WorkspaceTenantResolver());
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
        this.workspaces = factory.getRepository(WorkspaceRepository.class);
        this.memberships = factory.getRepository(CenterMembershipRepository.class);
        this.accounts = factory.getRepository(UserAccountRepository.class);
        this.periods = factory.getRepository(SubscriptionPeriodRepository.class);
        this.sampleResults = factory.getRepository(SampleResultRepository.class);
        this.verificationTeachingAssignments =
                factory.getRepository(
                        com.giaokhoa.verification.repository
                                .VerificationTeachingAssignmentRepository.class);
        this.verificationEnrollments =
                factory.getRepository(
                        com.giaokhoa.verification.repository.VerificationEnrollmentRepository
                                .class);
        this.verificationPartitions =
                factory.getRepository(VerificationAuthorizationPartitionRepository.class);
        this.verificationMemberships =
                factory.getRepository(VerificationCenterMembershipRepository.class);
        this.verificationWorkspaces = factory.getRepository(VerificationWorkspaceRepository.class);
        this.verificationAccounts = factory.getRepository(VerificationUserAccountRepository.class);
        this.guardianLinks = factory.getRepository(GuardianLinkRepository.class);
        this.resourceVersions = factory.getRepository(ResourceVersionRepository.class);
        this.resources = factory.getRepository(ResourceRepository.class);
        this.libraryItems = factory.getRepository(LibraryItemRepository.class);
        this.shareGrants = factory.getRepository(ShareGrantRepository.class);
        this.resourceDependencies = factory.getRepository(ResourceDependencyRepository.class);
        this.resourceAssignments = factory.getRepository(ResourceAssignmentRepository.class);
        this.resourcesWorkspaces = factory.getRepository(ResourcesWorkspaceRepository.class);
        this.resourcesAccounts = factory.getRepository(ResourcesUserAccountRepository.class);
        this.resourcesActivities = factory.getRepository(ResourcesSampleActivityRepository.class);
        this.resourcesPartitions =
                factory.getRepository(ResourcesAuthorizationPartitionRepository.class);
        // Repository có fragment của module authorization: đăng ký qua context tối thiểu dùng
        // lại đúng EntityManagerFactory, JdbcTemplate và transaction manager của bộ này.
        this.fragmentContext = new AnnotationConfigApplicationContext();
        fragmentContext.register(AuthorizationFragmentRepositories.class);
        fragmentContext
                .getBeanFactory()
                .registerSingleton("entityManagerFactory", entityManagerFactory);
        fragmentContext
                .getBeanFactory()
                .registerSingleton("jdbcTemplate", new JdbcTemplate(dataSource));
        fragmentContext
                .getBeanFactory()
                .registerSingleton("transactionManager", transactionManager);
        fragmentContext.refresh();
        this.operations = fragmentContext.getBean(AuthorizationOperationRepository.class);
        this.authzPartitions = fragmentContext.getBean(AuthzPartitionRepository.class);
        this.authzProjections = fragmentContext.getBean(AuthzProjectionRepository.class);
    }

    @Override
    public void close() {
        fragmentContext.close();
        entityManagerFactory.close();
    }
}
