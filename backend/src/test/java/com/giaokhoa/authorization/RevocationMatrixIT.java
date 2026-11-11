package com.giaokhoa.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.giaokhoa.authorization.model.AuthorizationOperation;
import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.NativePolicyTemplates;
import com.giaokhoa.support.KeycloakSessionFixture;
import com.giaokhoa.verification.ResultPolicyProjector;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Ma trận thu hồi: sáu scenario khóa hành vi thu hồi ở tầng HTTP với phiên thật và worker thật.
 *
 * <ul>
 *   <li>Gỡ phân công giáo viên: cùng phiên cũ đọc roster môn đó phải 403, giáo viên còn lại vẫn
 *       200.
 *   <li>Gỡ membership tại một center: cùng phiên cũ đọc roster center đó phải 403, danh sách
 *       workspace còn center kia mà không còn center này, thành viên khác không ảnh hưởng.
 *   <li>Gỡ ghi danh: học sinh đọc kết quả công bố của mình chuyển từ 1 mục thành 0 mục (HTTP 200
 *       với danh sách rỗng: chính học sinh nhận tập được phép, có thể rỗng, không phải 403), và
 *       biến mất khỏi roster theo góc nhìn của giáo viên (1 mục thành 0 mục). Giáo viên còn thẩm
 *       quyền vẫn đọc kết quả 200 (lịch sử giữ). Học sinh không bao giờ đọc được danh sách roster
 *       môn.
 *   <li>Header {@code X-Tenant-Id} giả không ghi đè route. Id môn của center khác nhúng vào route
 *       này phải 404.
 *   <li>Nguồn quyền OR độc lập: gỡ một môn thì môn còn lại vẫn 200.
 *   <li>DELETE trả 202 PENDING với operationId. Yêu cầu được bảo vệ ngay sau 202 không bao giờ 200
 *       (403 hoặc 503 fail-closed). Operation view nhất quán. Chờ SUCCEEDED rồi roster 403.
 * </ul>
 *
 * <p>Không bao phủ ở đây:
 *
 * <ul>
 *   <li>cạnh tranh thu hồi đồng thời nhiều partition (đã có {@code ConcurrentRevocationE2EIT}, tái
 *       dùng thay vì viết lại)
 *   <li>khôi phục sau lỗi ở biên worker (đã có {@code ProjectionRecoveryIT})
 *   <li>lỗi directory/graph/commit ép buộc (không lái được qua route HTTP công khai)
 *   <li>action chấm/công bố mẫu và case share
 * </ul>
 *
 * <p>Phần nối Keycloak/phiên dùng chung ở {@code KeycloakSessionFixture} (package support), cùng
 * mẫu với fixture của các nhóm test khác.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RevocationMatrixIT extends KeycloakSessionFixture {
    @Autowired private ResultPolicyProjector resultProjector;
    @Autowired private IdempotencyService idempotency;

    private SharedIdentities identities;

    private record SharedIdentities(
            KeycloakLogin owner,
            UUID ownerId,
            KeycloakLogin manager,
            UUID managerId,
            KeycloakLogin teacherA,
            UUID teacherAId,
            KeycloakLogin teacherB,
            UUID teacherBId,
            KeycloakLogin parent,
            UUID parentId,
            KeycloakLogin otherParent,
            UUID otherParentId,
            KeycloakLogin studentX,
            UUID studentXId,
            KeycloakLogin studentY,
            UUID studentYId) {}

    private record CenterSetup(UUID workspaceId, @Nullable UUID teacherAMembership) {}

    private record TeachingSetup(
            UUID classId, UUID courseId, List<UUID> courseSubjectIds, UUID rosterId) {
        UUID courseSubjectId() {
            return courseSubjectIds.get(0);
        }

        UUID courseSubjectId(int index) {
            return courseSubjectIds.get(index);
        }
    }

    private record SubjectRef(UUID id, String name) {}

    @BeforeAll
    void seedSharedIdentities() {
        KeycloakLogin ownerLogin = createVerifiedUser("revocation-owner");
        KeycloakLogin managerLogin = createVerifiedUser("revocation-manager");
        KeycloakLogin teacherALogin = createVerifiedUser("revocation-teacher-a");
        KeycloakLogin teacherBLogin = createVerifiedUser("revocation-teacher-b");
        KeycloakLogin parentLogin = createVerifiedUser("revocation-parent");
        KeycloakLogin otherParentLogin = createVerifiedUser("revocation-parent-b");
        KeycloakLogin studentXLogin = createVerifiedUser("revocation-student-x");
        KeycloakLogin studentYLogin = createVerifiedUser("revocation-student-y");
        identities =
                new SharedIdentities(
                        ownerLogin,
                        seedAccount(ownerLogin),
                        managerLogin,
                        seedAccount(managerLogin),
                        teacherALogin,
                        seedAccount(teacherALogin),
                        teacherBLogin,
                        seedAccount(teacherBLogin),
                        parentLogin,
                        seedAccount(parentLogin),
                        otherParentLogin,
                        seedAccount(otherParentLogin),
                        studentXLogin,
                        seedAccount(studentXLogin),
                        studentYLogin,
                        seedAccount(studentYLogin));
    }

    @AfterAll
    void deleteSharedIdentities() {
        deleteUser(identities.owner().subject());
        deleteUser(identities.manager().subject());
        deleteUser(identities.teacherA().subject());
        deleteUser(identities.teacherB().subject());
        deleteUser(identities.parent().subject());
        deleteUser(identities.otherParent().subject());
        deleteUser(identities.studentX().subject());
        deleteUser(identities.studentY().subject());
    }

    // ---------- Gỡ phân công giáo viên ----------

    @DisplayName("Giáo viên bị revoke mất quyền đọc roster ngay trong cùng session")
    @Test
    void revokedTeacherLosesRosterRead() throws Exception {
        CenterSetup center = seedCenter(true);
        try (var owner = openSession(identities.owner());
                var teacherA = openSession(identities.teacherA());
                var teacherB = openSession(identities.teacherB())) {
            SubjectRef subject = subjects(owner, 1).get(0);
            TeachingSetup teaching =
                    prepareTeaching(
                            owner,
                            center,
                            identities.teacherAId(),
                            identities.studentXId(),
                            List.of(subject.id()));
            assignTeacher(
                    owner,
                    center.workspaceId(),
                    teaching.courseSubjectId(),
                    identities.teacherBId());
            UUID publishedId =
                    seedPublishedResult(
                            center.workspaceId(),
                            teaching.courseSubjectId(),
                            subject,
                            identities.studentXId());
            recordResults(center.workspaceId(), publishedId);
            String results = resultsPath(center.workspaceId(), identities.studentXId());
            String roster = rosterPath(center.workspaceId(), teaching.courseSubjectId());
            assertEquals(
                    1,
                    getJson(teacherA, results, 200).get("items").size(),
                    "Giáo viên còn phân công đọc được kết quả công bố của học sinh");
            assertEquals(
                    1,
                    getJson(teacherA, roster, 200).get("items").size(),
                    "Giáo viên còn phân công đọc được roster có đúng học sinh");
            assertEquals(
                    200,
                    getRaw(teacherB, roster).getStatus(),
                    "Giáo viên còn lại đọc được roster trước khi gỡ");
            UUID assignment =
                    teachingAssignmentId(
                            center.workspaceId(),
                            teaching.courseSubjectId(),
                            identities.teacherAId());
            UUID operationId =
                    deleteOperation(
                            owner,
                            "/api/workspaces/"
                                    + center.workspaceId()
                                    + "/course-subjects/"
                                    + teaching.courseSubjectId()
                                    + "/teachers/"
                                    + assignment);
            awaitOperationSucceeded(jdbc, operationId);
            assertEquals(
                    403,
                    getRaw(teacherA, roster).getStatus(),
                    "Cùng phiên cũ sau khi gỡ phân công phải bị chặn đọc roster ngay");
            assertEquals(
                    403,
                    getRaw(teacherA, results).getStatus(),
                    "Cùng phiên cũ sau khi gỡ phân công, giáo viên không còn đọc được kết quả"
                            + " của học sinh (actor khác mà tập được phép rỗng thì 403)");
            assertEquals(
                    1,
                    getJson(teacherB, results, 200).get("items").size(),
                    "Giáo viên còn phân công vẫn đọc được kết quả công bố của học sinh");
            assertEquals(
                    200,
                    getRaw(teacherB, roster).getStatus(),
                    "Giáo viên còn phân công không bị ảnh hưởng khi đồng nghiệp bị gỡ");
        }
    }

    // ---------- Gỡ membership tại một center ----------

    @DisplayName("Thành viên bị gỡ mất cả workspace và roster ngay trong cùng session")
    @Test
    void removedMemberLosesWorkspaceAccess() throws Exception {
        CenterSetup centerA = seedCenter(true);
        CenterSetup centerB = seedCenter(true);
        try (var owner = openSession(identities.owner());
                var teacherA = openSession(identities.teacherA());
                var teacherB = openSession(identities.teacherB())) {
            SubjectRef subject = subjects(owner, 1).get(0);
            TeachingSetup teaching =
                    prepareTeaching(
                            owner,
                            centerA,
                            identities.teacherAId(),
                            identities.studentXId(),
                            List.of(subject.id()));
            assignTeacher(
                    owner,
                    centerA.workspaceId(),
                    teaching.courseSubjectId(),
                    identities.teacherBId());
            UUID publishedId =
                    seedPublishedResult(
                            centerA.workspaceId(),
                            teaching.courseSubjectId(),
                            subject,
                            identities.studentXId());
            recordResults(centerA.workspaceId(), publishedId);
            String resultsA = resultsPath(centerA.workspaceId(), identities.studentXId());
            String rosterA = rosterPath(centerA.workspaceId(), teaching.courseSubjectId());
            assertEquals(
                    200,
                    getRaw(teacherA, rosterA).getStatus(),
                    "Giáo viên còn là thành viên đọc được roster");
            assertEquals(
                    1,
                    getJson(teacherA, resultsA, 200).get("items").size(),
                    "Giáo viên còn là thành viên đọc được kết quả công bố của học sinh");
            UUID membership =
                    Objects.requireNonNull(
                            centerA.teacherAMembership(), "Giáo viên A phải có membership tại A");
            UUID operationId =
                    deleteOperation(
                            owner,
                            "/api/workspaces/"
                                    + centerA.workspaceId()
                                    + "/memberships/"
                                    + membership);
            awaitOperationSucceeded(jdbc, operationId);
            assertEquals(
                    403,
                    getRaw(teacherA, rosterA).getStatus(),
                    "Cùng phiên cũ sau khi gỡ membership phải bị chặn đọc roster ngay");
            assertEquals(
                    403,
                    getRaw(teacherA, resultsA).getStatus(),
                    "Cùng phiên cũ sau khi gỡ membership, giáo viên không còn đọc được kết quả"
                            + " của học sinh (actor khác mà tập được phép rỗng thì 403)");
            assertEquals(
                    1,
                    getJson(teacherB, resultsA, 200).get("items").size(),
                    "Giáo viên còn là thành viên vẫn đọc được kết quả công bố của học sinh");
            List<UUID> visible = workspaceIds(teacherA);
            assertTrue(
                    visible.contains(centerB.workspaceId()),
                    "Danh sách workspace vẫn còn center B sau khi bị gỡ khỏi center A");
            assertFalse(
                    visible.contains(centerA.workspaceId()),
                    "Danh sách workspace không còn center A sau khi bị gỡ membership");
            assertEquals(
                    200,
                    getRaw(teacherB, rosterA).getStatus(),
                    "Giáo viên còn là thành viên không bị ảnh hưởng khi đồng nghiệp bị gỡ");
        }
    }

    // ---------- Gỡ ghi danh ----------

    @DisplayName("Học sinh bị hủy ghi danh mất quyền đọc kết quả và biến mất khỏi roster")
    @Test
    void unenrolledStudentLosesResultRead() throws Exception {
        CenterSetup center = seedCenter(true);
        try (var owner = openSession(identities.owner());
                var teacherA = openSession(identities.teacherA());
                var studentX = openSession(identities.studentX())) {
            SubjectRef subject = subjects(owner, 1).get(0);
            TeachingSetup teaching =
                    prepareTeaching(
                            owner,
                            center,
                            identities.teacherAId(),
                            identities.studentXId(),
                            List.of(subject.id()));
            UUID publishedId =
                    seedPublishedResult(
                            center.workspaceId(),
                            teaching.courseSubjectId(),
                            subject,
                            identities.studentXId());
            recordResults(center.workspaceId(), publishedId);
            String results = resultsPath(center.workspaceId(), identities.studentXId());
            String roster = rosterPath(center.workspaceId(), teaching.courseSubjectId());
            assertEquals(
                    1,
                    getJson(studentX, results, 200).get("items").size(),
                    "Học sinh còn ghi danh đọc được kết quả công bố của mình");
            assertEquals(
                    1,
                    getJson(teacherA, roster, 200).get("items").size(),
                    "Giáo viên đọc được roster có đúng học sinh trước khi gỡ ghi danh");
            UUID enrollment =
                    enrollmentId(
                            center.workspaceId(), teaching.rosterId(), teaching.courseSubjectId());
            UUID operationId =
                    deleteOperation(
                            owner,
                            "/api/workspaces/"
                                    + center.workspaceId()
                                    + "/enrollments/"
                                    + enrollment);
            awaitOperationSucceeded(jdbc, operationId);
            assertEquals(
                    0,
                    getJson(studentX, results, 200).get("items").size(),
                    "Cùng phiên cũ sau khi gỡ ghi danh, học sinh không còn thấy kết quả của mình"
                            + " (chính học sinh nhận tập được phép, có thể rỗng,"
                            + " HTTP 200 — không phải 403)");
            assertEquals(
                    0,
                    getJson(teacherA, roster, 200).get("items").size(),
                    "Học sinh đã gỡ ghi danh biến mất khỏi roster theo góc nhìn của giáo viên");
            assertEquals(
                    200,
                    getRaw(teacherA, results).getStatus(),
                    "Giáo viên còn thẩm quyền vẫn đọc được kết quả đã công bố (lịch sử giữ)");
        }
    }

    // ---------- Tenant giả và id ngoài phạm vi ----------

    @DisplayName("Tenant header giả mạo và subject của tenant khác đều bị từ chối")
    @Test
    void forgedTenantIdentityIsRejected() throws Exception {
        CenterSetup centerA = seedCenter(false);
        CenterSetup centerB = seedCenter(true);
        try (var owner = openSession(identities.owner());
                var teacherA = openSession(identities.teacherA())) {
            SubjectRef subject = subjects(owner, 1).get(0);
            TeachingSetup teachingA =
                    prepareTeaching(
                            owner,
                            centerA,
                            identities.teacherBId(),
                            identities.studentXId(),
                            List.of(subject.id()));
            TeachingSetup teachingB =
                    prepareTeaching(
                            owner,
                            centerB,
                            identities.teacherAId(),
                            identities.studentXId(),
                            List.of(subject.id()));
            String rosterB = rosterPath(centerB.workspaceId(), teachingB.courseSubjectId());
            assertEquals(
                    200,
                    getRaw(teacherA, rosterB).getStatus(),
                    "Giáo viên có thẩm quyền tại center B đọc được roster của B");
            String rosterA = rosterPath(centerA.workspaceId(), teachingA.courseSubjectId());
            assertEquals(
                    200,
                    getRaw(owner, rosterA).getStatus(),
                    "Route center A vẫn hoạt động cho chủ sở hữu");
            assertEquals(
                    403,
                    getRaw(teacherA, rosterA).getStatus(),
                    "Giáo viên không phải thành viên center A bị chặn trên route A");
            MockHttpServletResponse forged =
                    getWithHeader(
                            teacherA, rosterA, "X-Tenant-Id", centerB.workspaceId().toString());
            assertEquals(
                    403,
                    forged.getStatus(),
                    "Header X-Tenant-Id giả sang center B không được ghi đè route center A");
            assertEquals(
                    404,
                    getRaw(teacherA, rosterPath(centerA.workspaceId(), teachingB.courseSubjectId()))
                            .getStatus(),
                    "Id môn của center khác nhúng vào route A phải 404, không tiết lộ tồn tại");
        }
    }

    // ---------- Nguồn quyền OR độc lập ----------

    @Test
    void revokingOneSubjectKeepsOtherSubject() throws Exception {
        CenterSetup center = seedCenter(true);
        try (var owner = openSession(identities.owner());
                var teacherA = openSession(identities.teacherA())) {
            List<SubjectRef> two = subjects(owner, 2);
            TeachingSetup teaching =
                    prepareTeaching(
                            owner,
                            center,
                            identities.teacherAId(),
                            identities.studentXId(),
                            List.of(two.get(0).id(), two.get(1).id()));
            String rosterFirst = rosterPath(center.workspaceId(), teaching.courseSubjectId(0));
            String rosterSecond = rosterPath(center.workspaceId(), teaching.courseSubjectId(1));
            assertEquals(
                    200,
                    getRaw(teacherA, rosterFirst).getStatus(),
                    "Giáo viên đọc được roster môn thứ nhất trước khi gỡ");
            assertEquals(
                    200,
                    getRaw(teacherA, rosterSecond).getStatus(),
                    "Giáo viên đọc được roster môn thứ hai trước khi gỡ");
            UUID assignment =
                    teachingAssignmentId(
                            center.workspaceId(),
                            teaching.courseSubjectId(0),
                            identities.teacherAId());
            UUID operationId =
                    deleteOperation(
                            owner,
                            "/api/workspaces/"
                                    + center.workspaceId()
                                    + "/course-subjects/"
                                    + teaching.courseSubjectId(0)
                                    + "/teachers/"
                                    + assignment);
            awaitOperationSucceeded(jdbc, operationId);
            assertEquals(
                    403,
                    getRaw(teacherA, rosterFirst).getStatus(),
                    "Môn đã gỡ phân công phải bị chặn đọc roster");
            assertEquals(
                    200,
                    getRaw(teacherA, rosterSecond).getStatus(),
                    "Môn còn phân công vẫn đọc được roster (nguồn OR độc lập)");
        }
    }

    // ---------- 202 và fail-closed trước khi hoàn tất ----------

    @DisplayName(
            "Revoke trả 202 và request tiếp theo không bao giờ thành công trước khi việc thu hồi hoàn tất")
    @Test
    void revokeReturns202UntilCompletion() throws Exception {
        CenterSetup center = seedCenter(true);
        try (var owner = openSession(identities.owner());
                var teacherA = openSession(identities.teacherA())) {
            SubjectRef subject = subjects(owner, 1).get(0);
            TeachingSetup teaching =
                    prepareTeaching(
                            owner,
                            center,
                            identities.teacherAId(),
                            identities.studentXId(),
                            List.of(subject.id()));
            String roster = rosterPath(center.workspaceId(), teaching.courseSubjectId());
            assertEquals(
                    200,
                    getRaw(teacherA, roster).getStatus(),
                    "Giáo viên đọc được roster trước khi gỡ");
            UUID assignment =
                    teachingAssignmentId(
                            center.workspaceId(),
                            teaching.courseSubjectId(),
                            identities.teacherAId());
            // deleteOperation đã chốt 202 + PENDING + completed=false + statusUrl + operationId.
            UUID operationId =
                    deleteOperation(
                            owner,
                            "/api/workspaces/"
                                    + center.workspaceId()
                                    + "/course-subjects/"
                                    + teaching.courseSubjectId()
                                    + "/teachers/"
                                    + assignment);
            // Yêu cầu được bảo vệ ngay sau 202, chưa chờ SUCCEEDED: không bao giờ 200.
            int immediate = getRaw(teacherA, roster).getStatus();
            assertTrue(
                    immediate == 403 || immediate == 503,
                    "Ngay sau 202, yêu cầu được bảo vệ phải 403 hoặc 503 fail-closed, không"
                            + " bao giờ 200; thực tế: "
                            + immediate);
            JsonNode operation = getJson(owner, "/api/me/operations/" + operationId, 200);
            String state = operation.get("state").stringValue();
            assertTrue(
                    "PENDING".equals(state) || "SUCCEEDED".equals(state),
                    "Operation sau DELETE thu hồi phải ở PENDING hoặc SUCCEEDED; thực tế: "
                            + state);
            assertEquals(
                    "SUCCEEDED".equals(state),
                    operation.get("completed").booleanValue(),
                    "completed phải nhất quán với state của operation");
            awaitOperationSucceeded(jdbc, operationId);
            JsonNode done = getJson(owner, "/api/me/operations/" + operationId, 200);
            assertEquals("SUCCEEDED", done.get("state").stringValue());
            assertTrue(done.get("completed").booleanValue());
            assertEquals(
                    403,
                    getRaw(teacherA, roster).getStatus(),
                    "Sau khi operation SUCCEEDED, roster của phân công đã gỡ phải 403");
        }
    }

    // ---------- Chuẩn bị: center, lớp/khóa/ghi danh/phân công ----------

    private CenterSetup seedCenter(boolean withTeacherA) {
        UUID workspaceId = seedCenter(identities.ownerId());
        addMembership(workspaceId, identities.managerId(), "MANAGER");
        UUID teacherAMembership = null;
        if (withTeacherA) {
            teacherAMembership = addMembership(workspaceId, identities.teacherAId(), "TEACHER");
        }
        addMembership(workspaceId, identities.teacherBId(), "TEACHER");
        addMembership(workspaceId, identities.parentId(), "PARENT");
        addMembership(workspaceId, identities.otherParentId(), "PARENT");
        addMembership(workspaceId, identities.studentXId(), "STUDENT");
        addMembership(workspaceId, identities.studentYId(), "STUDENT");
        seedSubscription(workspaceId, identities.ownerId());
        return new CenterSetup(workspaceId, teacherAMembership);
    }

    /** Lớp/khóa/môn với học sinh đã ghi danh và giáo viên đã phân công, sẵn sàng để thu hồi. */
    private TeachingSetup prepareTeaching(
            RealSession owner,
            CenterSetup center,
            UUID teacherUserId,
            UUID studentUserId,
            List<UUID> subjectIds)
            throws Exception {
        UUID classId = createClass(owner, center.workspaceId(), "Lớp ma trận " + UUID.randomUUID());
        UUID rosterId = addRoster(owner, center.workspaceId(), classId, studentUserId);
        UUID courseId =
                createCourse(
                        owner,
                        center.workspaceId(),
                        classId,
                        "Khóa ma trận " + UUID.randomUUID(),
                        subjectIds);
        List<UUID> courseSubjectIds = new ArrayList<>();
        for (UUID subjectId : subjectIds) {
            UUID courseSubjectId = courseSubjectId(courseId, subjectId);
            enroll(owner, center.workspaceId(), rosterId, courseSubjectId);
            assignTeacher(owner, center.workspaceId(), courseSubjectId, teacherUserId);
            courseSubjectIds.add(courseSubjectId);
        }
        return new TeachingSetup(classId, courseId, courseSubjectIds, rosterId);
    }

    private List<SubjectRef> subjects(RealSession session, int count) throws Exception {
        JsonNode body = getJson(session, "/api/subjects", 200);
        JsonNode items = body.get("items");
        assertNotNull(items, "Danh mục môn trả dạng {items}");
        assertTrue(
                items.isArray() && items.size() >= count,
                "Danh mục môn phải có ít nhất " + count + " môn");
        List<SubjectRef> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JsonNode item = items.get(i);
            result.add(
                    new SubjectRef(
                            UUID.fromString(item.get("id").stringValue()),
                            item.get("name").stringValue()));
        }
        return result;
    }

    /** Seed một kết quả PUBLISHED của học sinh; trả về id của sample_result. */
    private UUID seedPublishedResult(
            UUID workspaceId, UUID courseSubjectId, SubjectRef subject, UUID studentUserId) {
        UUID activityId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID subscriptionPeriodId =
                jdbc.queryForObject(
                        "SELECT id FROM subscription_period WHERE workspace_id = ?",
                        UUID.class,
                        workspaceId);
        tx(() -> {
            jdbc.update(
                    "INSERT INTO resource(id,owner_workspace_id,created_by_user_id,kind,"
                            + "subject_id,state) VALUES (?,?,?,'EXAM',?,'ACTIVE')",
                    resourceId,
                    workspaceId,
                    identities.ownerId(),
                    subject.id());
            jdbc.update(
                    "INSERT INTO resource_version(id,resource_id,version_no,label,body_stub,"
                            + "answer_stub,review_state)"
                            + " VALUES (?,?,1,'Bản mẫu','Nội dung','Đáp án','APPROVED')",
                    versionId,
                    resourceId);
            jdbc.update(
                    "INSERT INTO sample_activity(id,workspace_id,course_subject_id,resource_id,"
                            + "version_id,assigned_by,assigned_at,due_at,attempt_limit,"
                            + "subscription_period_id,state)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,'ASSIGNED')",
                    activityId,
                    workspaceId,
                    courseSubjectId,
                    resourceId,
                    versionId,
                    identities.ownerId(),
                    now,
                    now.plusDays(7),
                    1,
                    subscriptionPeriodId);
            jdbc.update(
                    "INSERT INTO sample_submission(id,workspace_id,activity_id,student_user_id,"
                            + "attempt_no,submitted_at,content_stub,state)"
                            + " VALUES (?,?,?,?,1,?,?,'SUBMITTED')",
                    submissionId,
                    workspaceId,
                    activityId,
                    studentUserId,
                    now,
                    "Bài làm mẫu");
            jdbc.update(
                    "INSERT INTO sample_result(id,workspace_id,submission_id,student_user_id,"
                            + "course_subject_id,score_stub,comment_stub,state,published_at,"
                            + "graded_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    resultId,
                    workspaceId,
                    submissionId,
                    studentUserId,
                    courseSubjectId,
                    "9.5",
                    "Xuất sắc",
                    "PUBLISHED",
                    now,
                    identities.teacherAId());
            return null;
        });
        return resultId;
    }

    /** Ghi projection cho các kết quả và chờ operation SUCCEEDED tại revision mới. */
    private void recordResults(UUID workspaceId, UUID... resultIds) {
        List<String> targetKeys =
                Arrays.stream(resultIds)
                        .map(resultId -> NativePolicyTemplates.resultKey(workspaceId, resultId))
                        .toList();
        String payloadHash =
                idempotency.payloadHash(
                        "POST",
                        "/workspaces/" + workspaceId + "/results",
                        JSON.readTree("{\"targets\":\"" + String.join(",", targetKeys) + "\"}"));
        AuthorizationOperation operation =
                resultProjector.recordResultChange(
                        identities.ownerId(),
                        workspaceId,
                        "revocation-matrix-" + UUID.randomUUID(),
                        payloadHash,
                        targetKeys);
        awaitOperationSucceeded(jdbc, operation.getId());
    }

    // ---------- Dữ kiện thư mục ----------

    private UUID seedAccount(KeycloakLogin login) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO user_account(id,keycloak_sub,username,email,email_verified,"
                            + "origin,identity_state) VALUES (?,?,?,?,?,'SELF_REGISTERED','ACTIVE')",
                    actor,
                    login.subject(),
                    "revocation-user-" + actor,
                    login.email(),
                    true);
            jdbc.update(
                    "INSERT INTO workspace(id,kind,owner_user_id,state)"
                            + " VALUES (?,'PERSONAL',?,'ACTIVE')",
                    workspace,
                    actor);
            return null;
        });
        return actor;
    }

    private UUID seedCenter(UUID ownerUserId) {
        UUID workspace = UUID.randomUUID();
        UUID center = UUID.randomUUID();
        UUID ownerMembership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO workspace(id,kind,center_id,state)"
                            + " VALUES (?,'CENTER',?,'ACTIVE')",
                    workspace,
                    center);
            jdbc.update(
                    "INSERT INTO center(id,workspace_id,keycloak_organization_id,name,"
                            + "owner_user_id,state) VALUES (?,?,?,?,?,'ACTIVE')",
                    center,
                    workspace,
                    "center-org-" + center,
                    "Center test",
                    ownerUserId);
            // Bất biến của DB: center chỉ commit được khi có đúng membership OWNER của chủ sở hữu.
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    ownerMembership,
                    workspace,
                    ownerUserId);
            jdbc.update(
                    "INSERT INTO membership_role(membership_id,role) VALUES (?,'OWNER')",
                    ownerMembership);
            return null;
        });
        return workspace;
    }

    private UUID addMembership(UUID workspaceId, UUID userId, String... roles) {
        UUID membership = UUID.randomUUID();
        tx(() -> {
            jdbc.update(
                    "INSERT INTO center_membership(id,workspace_id,user_id,state,joined_at)"
                            + " VALUES (?,?,?,'ACTIVE',now())",
                    membership,
                    workspaceId,
                    userId);
            for (String role : roles) {
                jdbc.update(
                        "INSERT INTO membership_role(membership_id,role) VALUES (?,?)",
                        membership,
                        role);
            }
            return null;
        });
        return membership;
    }

    private void seedSubscription(UUID workspaceId, UUID confirmedBy) {
        tx(() -> {
            jdbc.update(
                    "INSERT INTO subscription_period(id,workspace_id,valid_from,expires_at,"
                            + "confirmation_ref,confirmed_by,confirmed_at)"
                            + " VALUES (?,?,?,?,?,?,now())",
                    UUID.randomUUID(),
                    workspaceId,
                    OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                    OffsetDateTime.now(ZoneOffset.UTC).plusDays(365),
                    "revocation-confirm-" + UUID.randomUUID(),
                    confirmedBy);
            return null;
        });
    }

    // ---------- Dữ kiện giảng dạy tối thiểu ----------

    private UUID createClass(RealSession session, UUID workspaceId, String name) throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes",
                        "{\"name\":\"" + name + "\"}");
        awaitOperationSucceeded(jdbc, operationId);
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM school_class WHERE workspace_id = ? AND name = ?",
                        UUID.class,
                        workspaceId,
                        name),
                "Lớp phải tồn tại sau khi operation SUCCEEDED");
    }

    private UUID createCourse(
            RealSession session, UUID workspaceId, UUID classId, String name, List<UUID> subjectIds)
            throws Exception {
        String subjects =
                subjectIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/courses",
                        "{\"name\":\"" + name + "\",\"subjectIds\":[" + subjects + "]}");
        awaitOperationSucceeded(jdbc, operationId);
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM course WHERE workspace_id = ? AND class_id = ? AND name = ?",
                        UUID.class,
                        workspaceId,
                        classId,
                        name),
                "Khóa phải tồn tại sau khi operation SUCCEEDED");
    }

    private UUID addRoster(RealSession session, UUID workspaceId, UUID classId, UUID studentUserId)
            throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/" + workspaceId + "/classes/" + classId + "/students",
                        "{\"studentUserIds\":[\"" + studentUserId + "\"]}");
        awaitOperationSucceeded(jdbc, operationId);
        return classStudentId(classId, studentUserId);
    }

    private void enroll(
            RealSession session, UUID workspaceId, UUID classStudentId, UUID courseSubjectId)
            throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/" + workspaceId + "/enrollments",
                        "{\"classStudentIds\":[\""
                                + classStudentId
                                + "\"],\"courseSubjectIds\":[\""
                                + courseSubjectId
                                + "\"]}");
        awaitOperationSucceeded(jdbc, operationId);
    }

    private void assignTeacher(
            RealSession session, UUID workspaceId, UUID courseSubjectId, UUID teacherUserId)
            throws Exception {
        UUID operationId =
                postOperation(
                        session,
                        "/api/workspaces/"
                                + workspaceId
                                + "/course-subjects/"
                                + courseSubjectId
                                + "/teachers",
                        "{\"teacherUserIds\":[\"" + teacherUserId + "\"]}");
        awaitOperationSucceeded(jdbc, operationId);
    }

    private UUID courseSubjectId(UUID courseId, UUID subjectId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM course_subject WHERE course_id = ? AND subject_id = ?",
                        UUID.class,
                        courseId,
                        subjectId),
                "Môn–khóa phải tồn tại");
    }

    private UUID classStudentId(UUID classId, UUID studentUserId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM class_student WHERE class_id = ? AND student_user_id = ?",
                        UUID.class,
                        classId,
                        studentUserId),
                "Học sinh phải có trong roster của lớp");
    }

    private UUID enrollmentId(UUID workspaceId, UUID classStudentId, UUID courseSubjectId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM enrollment WHERE workspace_id = ? AND class_student_id = ?"
                                + " AND course_subject_id = ? AND state = 'ACTIVE'",
                        UUID.class,
                        workspaceId,
                        classStudentId,
                        courseSubjectId),
                "Ghi danh phải ACTIVE");
    }

    private UUID teachingAssignmentId(UUID workspaceId, UUID courseSubjectId, UUID teacherUserId) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM teaching_assignment WHERE workspace_id = ?"
                                + " AND course_subject_id = ? AND teacher_user_id = ?"
                                + " AND state = 'ACTIVE'",
                        UUID.class,
                        workspaceId,
                        courseSubjectId,
                        teacherUserId),
                "Phân công giảng dạy phải ACTIVE");
    }

    // ---------- Đường đọc ----------

    private static String rosterPath(UUID workspaceId, UUID courseSubjectId) {
        return "/api/workspaces/"
                + workspaceId
                + "/course-subjects/"
                + courseSubjectId
                + "/enrollments";
    }

    private static String resultsPath(UUID workspaceId, UUID studentUserId) {
        return "/api/workspaces/" + workspaceId + "/students/" + studentUserId + "/results";
    }

    private List<UUID> workspaceIds(RealSession session) throws Exception {
        JsonNode body = getJson(session, "/api/me/workspaces", 200);
        assertTrue(body.isArray(), "Danh sách workspace trả dạng mảng JSON");
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : body) {
            ids.add(UUID.fromString(item.get("id").stringValue()));
        }
        return ids;
    }

    // ---------- HTTP plumbing ----------

    private UUID operationIdOf(MockHttpServletResponse response, String action) throws Exception {
        assertEquals(202, response.getStatus(), action + " phải trả 202 OperationView");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertEquals("PENDING", body.get("state").stringValue());
        assertFalse(body.get("completed").booleanValue());
        assertFalse(body.get("statusUrl").stringValue().isBlank());
        return UUID.fromString(body.get("operationId").stringValue());
    }

    /** Mutation kỳ vọng thành công theo hợp đồng OperationView 202; dùng cho mọi setup đã có. */
    private UUID postOperation(RealSession session, String uri, String json) throws Exception {
        return operationIdOf(mutate(session, post(uri), json), "POST " + uri);
    }

    /** DELETE thu hồi: chốt 202 PENDING theo hợp đồng, trả operationId để chờ SUCCEEDED. */
    private UUID deleteOperation(RealSession session, String uri) throws Exception {
        return operationIdOf(mutate(session, delete(uri), null), "DELETE " + uri);
    }

    private MockHttpServletResponse mutate(
            RealSession session, MockHttpServletRequestBuilder builder, @Nullable String json)
            throws Exception {
        var request =
                builder.cookie(session.cookie())
                        .with(csrf())
                        .header("Idempotency-Key", "revocation-" + UUID.randomUUID());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc().perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse getRaw(RealSession session, String uri) throws Exception {
        return mockMvc().perform(get(uri).cookie(session.cookie())).andReturn().getResponse();
    }

    private MockHttpServletResponse getWithHeader(
            RealSession session, String uri, String name, String value) throws Exception {
        return mockMvc()
                .perform(get(uri).cookie(session.cookie()).header(name, value))
                .andReturn()
                .getResponse();
    }

    private JsonNode getJson(RealSession session, String uri, int expectedStatus) throws Exception {
        MockHttpServletResponse response = getRaw(session, uri);
        assertEquals(expectedStatus, response.getStatus(), "GET " + uri);
        return JSON.readTree(response.getContentAsString());
    }
}
