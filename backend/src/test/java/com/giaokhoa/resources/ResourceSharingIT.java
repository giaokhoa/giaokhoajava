package com.giaokhoa.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Chia sẻ tài nguyên giữa các không gian ở tầng HTTP: cổng nhận (chưa accept thì chưa dùng), giới
 * hạn phạm vi của nguồn không bị gỡ qua lưu/fork, thu hồi revocable chặn cả bản gốc lẫn bản dẫn
 * xuất nhưng giữ lịch sử, share lâu dài sống sót khi người chia sẻ rời đi và không bị đơn phương
 * thu hồi, giáo viên không gói cá nhân vẫn lưu được tại center mà không mở kho cá nhân, và đề gộp
 * nhiều nguồn chỉ chia sẻ được khi mọi thành phần cùng cho phép.
 *
 * <p>Kịch bản đề gộp nạp sẵn quan hệ thành phần vào bảng resource_dependency. Fixture còn lại nạp
 * dữ kiện có thật bằng JDBC.
 */
@SpringBootTest
class ResourceSharingIT extends ResourceFixture {
    private static final String SCHEMA =
            "resource_sharing_" + UUID.randomUUID().toString().replace("-", "");
    private static final List<String> USE_AND_SAVE = List.of("content.read", "use", "library.save");
    private static final List<String> USE_SAVE_AND_FORK =
            List.of("content.read", "use", "library.save", "fork");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema() {
        // Schema do Flyway tạo bằng credential migration nên cũng phải xoá bằng credential đó;
        // datasource ứng dụng chạy bằng role runtime không có quyền xoá schema.
        migratorJdbc().execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void shareMustBeAcceptedBeforeRecipientCenterCanUse() throws Exception {
        KeycloakLogin ownerALogin = createVerifiedUser("share-a-owner");
        KeycloakLogin authorLogin = createVerifiedUser("share-a-author");
        KeycloakLogin ownerBLogin = createVerifiedUser("share-b-owner");
        KeycloakLogin teacherBLogin = createVerifiedUser("share-b-teacher");
        KeycloakLogin teacherCLogin = createVerifiedUser("share-c-teacher");
        try {
            UUID ownerAId =
                    seedAccount(
                            ownerALogin.subject(), "SELF_REGISTERED", ownerALogin.email(), true);
            UUID authorId =
                    seedAccount(
                            authorLogin.subject(), "SELF_REGISTERED", authorLogin.email(), true);
            UUID ownerBId =
                    seedAccount(
                            ownerBLogin.subject(), "SELF_REGISTERED", ownerBLogin.email(), true);
            UUID teacherBId =
                    seedAccount(
                            teacherBLogin.subject(),
                            "SELF_REGISTERED",
                            teacherBLogin.email(),
                            true);
            UUID teacherCId =
                    seedAccount(
                            teacherCLogin.subject(),
                            "SELF_REGISTERED",
                            teacherCLogin.email(),
                            true);
            UUID centerA = seedCenter(ownerAId);
            addMembership(centerA, authorId, "TEACHER");
            seedSubscription(centerA, -1, 365, ownerAId);
            UUID centerB = seedCenter(ownerBId);
            addMembership(centerB, teacherBId, "TEACHER");
            seedSubscription(centerB, -1, 365, ownerBId);
            UUID centerC = seedCenter(ownerAId);
            addMembership(centerC, teacherCId, "TEACHER");
            VersionRef version =
                    seedOwnedVersion(
                            centerA,
                            authorId,
                            firstSubjectId(),
                            "QUESTION",
                            "THAN-BAI-CHIA-SE",
                            "DAP-AN-CHIA-SE");

            UUID grantId;
            try (var author = openSession(authorLogin)) {
                JsonNode shared =
                        sampleActionExpectCreated(
                                author,
                                centerA,
                                shareJson(
                                        version.resourceId(),
                                        version.versionId(),
                                        centerB,
                                        "REVOCABLE",
                                        USE_AND_SAVE,
                                        List.of(centerB)));
                grantId = targetIdOf(shared);
            }

            try (var teacherB = openSession(teacherBLogin)) {
                assertEquals(
                        403,
                        getRaw(
                                        teacherB,
                                        bodyUri(centerB, version.resourceId(), version.versionId()))
                                .getStatus(),
                        "Share mới ở trạng thái offered, chưa accept thì nơi nhận chưa dùng được");
            }

            try (var ownerB = openSession(ownerBLogin)) {
                sampleActionExpectCreated(ownerB, centerB, acceptJson(grantId));
            }

            try (var teacherB = openSession(teacherBLogin)) {
                MockHttpServletResponse body =
                        getRaw(
                                teacherB,
                                bodyUri(centerB, version.resourceId(), version.versionId()));
                assertEquals(
                        200,
                        body.getStatus(),
                        "Sau accept, giáo viên khác tại nơi nhận đọc được thân bài theo điều kiện"
                                + " nguồn, body: "
                                + body.getContentAsString());
                sampleActionExpectCreated(
                        teacherB, centerB, saveJson(version.resourceId(), version.versionId()));
            }

            try (var teacherC = openSession(teacherCLogin)) {
                assertEquals(
                        403,
                        getRaw(
                                        teacherC,
                                        bodyUri(centerB, version.resourceId(), version.versionId()))
                                .getStatus(),
                        "Thành viên trung tâm khác không tự có quyền từ share cho B");
                assertEquals(
                        403,
                        getRaw(
                                        teacherC,
                                        bodyUri(centerC, version.resourceId(), version.versionId()))
                                .getStatus(),
                        "Share cho B không mở quyền tại trung tâm không liên quan");
            }
        } finally {
            deleteUser(ownerALogin.subject());
            deleteUser(authorLogin.subject());
            deleteUser(ownerBLogin.subject());
            deleteUser(teacherBLogin.subject());
            deleteUser(teacherCLogin.subject());
        }
    }

    @Test
    void versionRestrictedToOneCenterCannotEscapeThroughSaveOrFork() throws Exception {
        KeycloakLogin ownerXLogin = createVerifiedUser("escape-x-owner");
        KeycloakLogin authorXLogin = createVerifiedUser("escape-x-author");
        KeycloakLogin ownerALogin = createVerifiedUser("escape-a-owner");
        KeycloakLogin teacherALogin = createVerifiedUser("escape-a-teacher");
        KeycloakLogin ownerBLogin = createVerifiedUser("escape-b-owner");
        try {
            UUID ownerXId =
                    seedAccount(
                            ownerXLogin.subject(), "SELF_REGISTERED", ownerXLogin.email(), true);
            UUID authorXId =
                    seedAccount(
                            authorXLogin.subject(), "SELF_REGISTERED", authorXLogin.email(), true);
            UUID ownerAId =
                    seedAccount(
                            ownerALogin.subject(), "SELF_REGISTERED", ownerALogin.email(), true);
            UUID teacherAId =
                    seedAccount(
                            teacherALogin.subject(),
                            "SELF_REGISTERED",
                            teacherALogin.email(),
                            true);
            UUID ownerBId =
                    seedAccount(
                            ownerBLogin.subject(), "SELF_REGISTERED", ownerBLogin.email(), true);
            UUID centerX = seedCenter(ownerXId);
            addMembership(centerX, authorXId, "TEACHER");
            seedSubscription(centerX, -1, 365, ownerXId);
            UUID centerA = seedCenter(ownerAId);
            addMembership(centerA, teacherAId, "TEACHER");
            seedSubscription(centerA, -1, 365, ownerAId);
            UUID centerB = seedCenter(ownerBId);
            seedSubscription(centerB, -1, 365, ownerBId);
            UUID teacherPersonal = personalWorkspaceOf(teacherAId);
            seedSubscription(teacherPersonal, -1, 365, teacherAId);
            VersionRef version =
                    seedOwnedVersion(
                            centerX,
                            authorXId,
                            firstSubjectId(),
                            "QUESTION",
                            "THAN-BAI-GIOI-HAN",
                            "DAP-AN-GIOI-HAN");

            UUID grantId;
            try (var authorX = openSession(authorXLogin)) {
                JsonNode shared =
                        sampleActionExpectCreated(
                                authorX,
                                centerX,
                                shareJson(
                                        version.resourceId(),
                                        version.versionId(),
                                        centerA,
                                        "REVOCABLE",
                                        USE_SAVE_AND_FORK,
                                        List.of(centerA)));
                grantId = targetIdOf(shared);
            }
            try (var ownerA = openSession(ownerALogin)) {
                sampleActionExpectCreated(ownerA, centerA, acceptJson(grantId));
            }

            UUID forkId;
            try (var teacherA = openSession(teacherALogin)) {
                sampleAction(
                        teacherA,
                        teacherPersonal,
                        saveJson(version.resourceId(), version.versionId()),
                        403);
                JsonNode forked =
                        sampleActionExpectCreated(
                                teacherA,
                                centerA,
                                forkJson(
                                        version.resourceId(),
                                        version.versionId(),
                                        "Bản chỉnh sửa tại A"));
                forkId = targetIdOf(forked);
                sampleAction(
                        teacherA,
                        centerA,
                        shareJson(
                                forkId,
                                forkVersionId(forkId),
                                centerB,
                                "REVOCABLE",
                                USE_AND_SAVE,
                                List.of(centerB)),
                        403);
            }

            try (var ownerB = openSession(ownerBLogin)) {
                assertEquals(
                        403,
                        getRaw(ownerB, bodyUri(centerB, forkId, forkVersionId(forkId))).getStatus(),
                        "Bản fork giữ giới hạn nguồn: ngoài phạm vi được phép không đọc được");
                assertEquals(
                        403,
                        getRaw(ownerB, bodyUri(centerB, version.resourceId(), version.versionId()))
                                .getStatus(),
                        "Version gốc bị giới hạn cũng không đọc được tại B");
            }
        } finally {
            deleteUser(ownerXLogin.subject());
            deleteUser(authorXLogin.subject());
            deleteUser(ownerALogin.subject());
            deleteUser(teacherALogin.subject());
            deleteUser(ownerBLogin.subject());
        }
    }

    @Test
    void revokingRevocableShareBlocksOriginalAndDerivedCopiesButKeepsHistory() throws Exception {
        KeycloakLogin ownerALogin = createVerifiedUser("revoke-a-owner");
        KeycloakLogin authorLogin = createVerifiedUser("revoke-a-author");
        KeycloakLogin ownerBLogin = createVerifiedUser("revoke-b-owner");
        KeycloakLogin teacherBLogin = createVerifiedUser("revoke-b-teacher");
        KeycloakLogin studentBLogin = createVerifiedUser("revoke-b-student");
        try {
            UUID ownerAId =
                    seedAccount(
                            ownerALogin.subject(), "SELF_REGISTERED", ownerALogin.email(), true);
            UUID authorId =
                    seedAccount(
                            authorLogin.subject(), "SELF_REGISTERED", authorLogin.email(), true);
            UUID ownerBId =
                    seedAccount(
                            ownerBLogin.subject(), "SELF_REGISTERED", ownerBLogin.email(), true);
            UUID teacherBId =
                    seedAccount(
                            teacherBLogin.subject(),
                            "SELF_REGISTERED",
                            teacherBLogin.email(),
                            true);
            UUID studentBId =
                    seedAccount(
                            studentBLogin.subject(),
                            "SELF_REGISTERED",
                            studentBLogin.email(),
                            true);
            UUID centerA = seedCenter(ownerAId);
            addMembership(centerA, authorId, "TEACHER");
            seedSubscription(centerA, -1, 365, ownerAId);
            UUID centerB = seedCenter(ownerBId);
            addMembership(centerB, teacherBId, "TEACHER");
            addMembership(centerB, studentBId, "STUDENT");
            seedSubscription(centerB, -1, 365, ownerBId);
            VersionRef version =
                    seedOwnedVersion(
                            centerA,
                            authorId,
                            firstSubjectId(),
                            "QUESTION",
                            "THAN-BAI-THU-HOI",
                            "DAP-AN-THU-HOI");

            UUID grantId;
            try (var author = openSession(authorLogin)) {
                JsonNode shared =
                        sampleActionExpectCreated(
                                author,
                                centerA,
                                shareJson(
                                        version.resourceId(),
                                        version.versionId(),
                                        centerB,
                                        "REVOCABLE",
                                        USE_SAVE_AND_FORK,
                                        List.of(centerB)));
                grantId = targetIdOf(shared);
            }
            try (var ownerB = openSession(ownerBLogin)) {
                sampleActionExpectCreated(ownerB, centerB, acceptJson(grantId));
            }

            UUID forkId;
            try (var teacherB = openSession(teacherBLogin)) {
                sampleActionExpectCreated(
                        teacherB, centerB, saveJson(version.resourceId(), version.versionId()));
                JsonNode forked =
                        sampleActionExpectCreated(
                                teacherB,
                                centerB,
                                forkJson(
                                        version.resourceId(),
                                        version.versionId(),
                                        "Bản fork tại B"));
                forkId = targetIdOf(forked);
            }
            UUID courseSubjectId = seedTeachingChain(centerB, studentBId);
            UUID activityId =
                    seedActivityWithSubmission(
                            centerB,
                            courseSubjectId,
                            version.resourceId(),
                            version.versionId(),
                            teacherBId,
                            studentBId);

            try (var author = openSession(authorLogin)) {
                sampleActionExpectCreated(author, centerA, revokeJson(grantId));
            }

            try (var teacherB = openSession(teacherBLogin)) {
                assertEquals(
                        403,
                        getRaw(
                                        teacherB,
                                        bodyUri(centerB, version.resourceId(), version.versionId()))
                                .getStatus(),
                        "Sau thu hồi, lần dùng mới qua bản gốc bị từ chối ngay");
                assertEquals(
                        403,
                        getRaw(teacherB, bodyUri(centerB, forkId, forkVersionId(forkId)))
                                .getStatus(),
                        "Thu hồi chặn cả bản fork dẫn xuất từ share đã thu hồi");
                sampleAction(
                        teacherB,
                        centerB,
                        saveJson(version.resourceId(), version.versionId()),
                        403);
            }
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM sample_submission WHERE activity_id = ?",
                            Integer.class,
                            activityId),
                    "Thu hồi quyền không xóa lịch sử bài nộp đã phát sinh");
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM sample_activity WHERE id = ?",
                            Integer.class,
                            activityId),
                    "Thu hồi quyền không xóa hoạt động đã giao");
        } finally {
            deleteUser(ownerALogin.subject());
            deleteUser(authorLogin.subject());
            deleteUser(ownerBLogin.subject());
            deleteUser(teacherBLogin.subject());
            deleteUser(studentBLogin.subject());
        }
    }

    @Test
    void longTermShareSurvivesGrantorExitAndRejectsUnilateralRevoke() throws Exception {
        KeycloakLogin ownerALogin = createVerifiedUser("longterm-a-owner");
        KeycloakLogin authorLogin = createVerifiedUser("longterm-a-author");
        KeycloakLogin ownerBLogin = createVerifiedUser("longterm-b-owner");
        KeycloakLogin teacherBLogin = createVerifiedUser("longterm-b-teacher");
        try {
            UUID ownerAId =
                    seedAccount(
                            ownerALogin.subject(), "SELF_REGISTERED", ownerALogin.email(), true);
            UUID authorId =
                    seedAccount(
                            authorLogin.subject(), "SELF_REGISTERED", authorLogin.email(), true);
            UUID ownerBId =
                    seedAccount(
                            ownerBLogin.subject(), "SELF_REGISTERED", ownerBLogin.email(), true);
            UUID teacherBId =
                    seedAccount(
                            teacherBLogin.subject(),
                            "SELF_REGISTERED",
                            teacherBLogin.email(),
                            true);
            UUID centerA = seedCenter(ownerAId);
            UUID authorMembership = addMembership(centerA, authorId, "TEACHER");
            seedSubscription(centerA, -1, 365, ownerAId);
            UUID centerB = seedCenter(ownerBId);
            addMembership(centerB, teacherBId, "TEACHER");
            seedSubscription(centerB, -1, 365, ownerBId);
            VersionRef version =
                    seedOwnedVersion(
                            centerA,
                            authorId,
                            firstSubjectId(),
                            "QUESTION",
                            "THAN-BAI-LAU-DAI",
                            "DAP-AN-LAU-DAI");

            UUID grantId;
            try (var author = openSession(authorLogin)) {
                JsonNode shared =
                        sampleActionExpectCreated(
                                author,
                                centerA,
                                shareJson(
                                        version.resourceId(),
                                        version.versionId(),
                                        centerB,
                                        "LONG_TERM",
                                        USE_AND_SAVE,
                                        List.of(centerB)));
                grantId = targetIdOf(shared);
            }
            try (var ownerB = openSession(ownerBLogin)) {
                sampleActionExpectCreated(ownerB, centerB, acceptJson(grantId));
            }
            try (var teacherB = openSession(teacherBLogin)) {
                assertEquals(
                        200,
                        getRaw(
                                        teacherB,
                                        bodyUri(centerB, version.resourceId(), version.versionId()))
                                .getStatus(),
                        "Nơi nhận dùng được version lâu dài sau khi accept");
            }

            try (var author = openSession(authorLogin)) {
                sampleAction(author, centerA, revokeJson(grantId), 403);
            }

            try (var ownerA = openSession(ownerALogin)) {
                UUID operationId =
                        postMutation(
                                ownerA,
                                "/api/workspaces/" + centerA + "/memberships/" + authorMembership,
                                "{}",
                                "DELETE");
                awaitOperationSucceeded(jdbc, operationId);
            }

            try (var teacherB = openSession(teacherBLogin)) {
                assertEquals(
                        200,
                        getRaw(
                                        teacherB,
                                        bodyUri(centerB, version.resourceId(), version.versionId()))
                                .getStatus(),
                        "Người chia sẻ rời đi không làm mất quyền dùng lâu dài của nơi nhận");
            }
            try (var author = openSession(authorLogin)) {
                assertEquals(
                        403,
                        getRaw(author, bodyUri(centerA, version.resourceId(), version.versionId()))
                                .getStatus(),
                        "Đã rời center thì không giữ quyền tại center chỉ vì từng chia sẻ");
                sampleAction(author, centerA, revokeJson(grantId), 403);
            }
        } finally {
            deleteUser(ownerALogin.subject());
            deleteUser(authorLogin.subject());
            deleteUser(ownerBLogin.subject());
            deleteUser(teacherBLogin.subject());
        }
    }

    @Test
    void teacherWithoutPersonalPlanSavesAtCenterButNotIntoPersonalLibrary() throws Exception {
        KeycloakLogin systemAuthorLogin = createVerifiedUser("noplan-system-author");
        KeycloakLogin ownerLogin = createVerifiedUser("noplan-center-owner");
        KeycloakLogin teacherLogin = createVerifiedUser("noplan-teacher");
        try {
            UUID systemAuthorId =
                    seedAccount(
                            systemAuthorLogin.subject(),
                            "SELF_REGISTERED",
                            systemAuthorLogin.email(),
                            true);
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID teacherId =
                    seedAccount(
                            teacherLogin.subject(), "SELF_REGISTERED", teacherLogin.email(), true);
            UUID center = seedCenter(ownerId);
            addMembership(center, teacherId, "TEACHER");
            seedSubscription(center, -1, 365, ownerId);
            UUID teacherPersonal = personalWorkspaceOf(teacherId);
            VersionRef system =
                    seedSystemVersion(
                            systemAuthorId,
                            firstSubjectId(),
                            "THAN-BAI-LUU-TAI-CENTER",
                            "DAP-AN-LUU-TAI-CENTER");

            try (var teacher = openSession(teacherLogin)) {
                sampleActionExpectCreated(
                        teacher, center, saveJson(system.resourceId(), system.versionId()));
                assertEquals(
                        200,
                        getRaw(teacher, bodyUri(center, system.resourceId(), system.versionId()))
                                .getStatus(),
                        "Đã lưu tại center thì đọc lại được tại center");
                sampleAction(
                        teacher,
                        teacherPersonal,
                        saveJson(system.resourceId(), system.versionId()),
                        403);
                assertEquals(
                        403,
                        getRaw(
                                        teacher,
                                        bodyUri(
                                                teacherPersonal,
                                                system.resourceId(),
                                                system.versionId()))
                                .getStatus(),
                        "Lưu tại center không mở kho cá nhân cho giáo viên chưa có gói");
            }
        } finally {
            deleteUser(systemAuthorLogin.subject());
            deleteUser(ownerLogin.subject());
            deleteUser(teacherLogin.subject());
        }
    }

    @Test
    void sharingCompositeExamRequiresPermissionFromEveryComponent() throws Exception {
        KeycloakLogin ownerALogin = createVerifiedUser("composite-a-owner");
        KeycloakLogin authorALogin = createVerifiedUser("composite-a-author");
        KeycloakLogin ownerXLogin = createVerifiedUser("composite-x-owner");
        KeycloakLogin authorXLogin = createVerifiedUser("composite-x-author");
        KeycloakLogin ownerBLogin = createVerifiedUser("composite-b-owner");
        try {
            UUID ownerAId =
                    seedAccount(
                            ownerALogin.subject(), "SELF_REGISTERED", ownerALogin.email(), true);
            UUID authorAId =
                    seedAccount(
                            authorALogin.subject(), "SELF_REGISTERED", authorALogin.email(), true);
            UUID ownerXId =
                    seedAccount(
                            ownerXLogin.subject(), "SELF_REGISTERED", ownerXLogin.email(), true);
            UUID authorXId =
                    seedAccount(
                            authorXLogin.subject(), "SELF_REGISTERED", authorXLogin.email(), true);
            UUID ownerBId =
                    seedAccount(
                            ownerBLogin.subject(), "SELF_REGISTERED", ownerBLogin.email(), true);
            UUID centerA = seedCenter(ownerAId);
            addMembership(centerA, authorAId, "TEACHER");
            seedSubscription(centerA, -1, 365, ownerAId);
            UUID centerX = seedCenter(ownerXId);
            addMembership(centerX, authorXId, "TEACHER");
            seedSubscription(centerX, -1, 365, ownerXId);
            UUID centerB = seedCenter(ownerBId);
            seedSubscription(centerB, -1, 365, ownerBId);
            UUID subjectId = firstSubjectId();
            VersionRef ownQuestion =
                    seedOwnedVersion(
                            centerA,
                            authorAId,
                            subjectId,
                            "QUESTION",
                            "THAN-CAU-TU-DO",
                            "DAP-CAU-TU-DO");
            VersionRef restrictedQuestion =
                    seedOwnedVersion(
                            centerX,
                            authorXId,
                            subjectId,
                            "QUESTION",
                            "THAN-CAU-GIOI-HAN",
                            "DAP-CAU-GIOI-HAN");
            VersionRef exam =
                    seedOwnedVersion(
                            centerA, authorAId, subjectId, "EXAM", "THAN-DE-GOP", "DAP-DE-GOP");

            // Câu bị giới hạn chỉ được cấp cho A: A nhận theo share bị khóa phạm vi ở A.
            UUID grantId;
            try (var authorX = openSession(authorXLogin)) {
                JsonNode shared =
                        sampleActionExpectCreated(
                                authorX,
                                centerX,
                                shareJson(
                                        restrictedQuestion.resourceId(),
                                        restrictedQuestion.versionId(),
                                        centerA,
                                        "REVOCABLE",
                                        USE_AND_SAVE,
                                        List.of(centerA)));
                grantId = targetIdOf(shared);
            }
            try (var ownerA = openSession(ownerALogin)) {
                sampleActionExpectCreated(ownerA, centerA, acceptJson(grantId));
            }

            // Đề gộp gồm cả hai câu: quan hệ thành phần là dữ kiện canonical.
            seedDependency(exam, ownQuestion, null);
            seedDependency(exam, restrictedQuestion, grantId);

            try (var authorA = openSession(authorALogin)) {
                sampleAction(
                        authorA,
                        centerA,
                        shareJson(
                                exam.resourceId(),
                                exam.versionId(),
                                centerB,
                                "REVOCABLE",
                                USE_AND_SAVE,
                                List.of(centerB)),
                        403);
                assertEquals(
                        0,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM share_grant WHERE source_resource_id = ?"
                                        + " AND source_version_id = ?"
                                        + " AND recipient_workspace_id = ?",
                                Integer.class,
                                exam.resourceId(),
                                exam.versionId(),
                                centerB),
                        "Đề gộp bị từ chối không để lại grant nào cho trung tâm đích");
                // Đối chứng: câu được phép đi một mình thì share ra B thành công, nên lời từ chối
                // ở trên đến từ thành phần bị giới hạn chứ không phải lỗi chung của luồng share.
                sampleActionExpectCreated(
                        authorA,
                        centerA,
                        shareJson(
                                ownQuestion.resourceId(),
                                ownQuestion.versionId(),
                                centerB,
                                "REVOCABLE",
                                USE_AND_SAVE,
                                List.of(centerB)));
            }
        } finally {
            deleteUser(ownerALogin.subject());
            deleteUser(authorALogin.subject());
            deleteUser(ownerXLogin.subject());
            deleteUser(authorXLogin.subject());
            deleteUser(ownerBLogin.subject());
        }
    }

    // ---------- Helper riêng ----------

    private UUID forkVersionId(UUID forkResourceId) {
        return java.util.Objects.requireNonNull(
                jdbc.queryForObject(
                        "SELECT id FROM resource_version WHERE resource_id = ?"
                                + " ORDER BY version_no LIMIT 1",
                        UUID.class,
                        forkResourceId),
                "Fork phải có version đầu của riêng nó");
    }

    private void seedDependency(
            VersionRef derived, VersionRef source, @Nullable UUID shareGrantId) {
        // usage_constraints là NOT NULL (V024) và theo định dạng
        // "<marker>;grant=..;actions=..;allowed=.." của các cạnh provenance do production ghi.
        // Cạnh ở đây là quan hệ thành phần do tác giả dựng sẵn, không phải cạnh fork/share.
        String usageConstraints =
                shareGrantId == null
                        ? "origin=COMPOSITION;grant=-;actions=-;allowed=-"
                        : "origin=COMPOSITION;grant="
                                + shareGrantId
                                + ";actions=content.read,use,library.save;allowed=-";
        tx(() -> {
            jdbc.update(
                    "INSERT INTO resource_dependency(id,derived_resource_id,derived_version_id,"
                            + "source_resource_id,source_version_id,share_grant_id,usage_constraints)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    derived.resourceId(),
                    derived.versionId(),
                    source.resourceId(),
                    source.versionId(),
                    shareGrantId,
                    usageConstraints);
            return null;
        });
    }
}
