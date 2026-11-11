package com.giaokhoa.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Quyền đọc/lưu/fork tài nguyên ở tầng HTTP: thân bài và đáp án là hai scope tách riêng, lưu giữ
 * nguyên định danh và chủ sở hữu nguồn, bản đã lưu đứng yên ở version cũ cho tới khi nơi nhận chủ
 * động nhận bản mới, và fork tạo định danh mới còn truy vết nguồn trong khi người chỉ có quyền dùng
 * không fork được.
 *
 * <p>Đây là test viết trước cho story kho tài nguyên (ban đầu ở PR test-first #218, đỏ vì route đọc
 * thân/đáp án và các action SAVE_VERSION, FORK_VERSION, RECEIVE_UPDATE, CORRECT_SOURCE chưa được
 * cài đặt). Bản này được port vào gate nghiệm thu US7 sau khi hành vi đã land trên main; fixture
 * chỉ seed dữ kiện canonical có thật (resource, resource_version, workspace, gói, chuỗi ghi danh)
 * bằng JDBC.
 */
@SpringBootTest
class ResourceAccessIT extends ResourceFixture {
    private static final String SCHEMA =
            "resource_access_" + UUID.randomUUID().toString().replace("-", "");

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
    void bodyAndAnswerAreSeparateReads() throws Exception {
        KeycloakLogin authorLogin = createVerifiedUser("resource-author");
        KeycloakLogin studentLogin = createVerifiedUser("resource-reader-student");
        KeycloakLogin outsiderLogin = createVerifiedUser("resource-outsider");
        try {
            UUID authorId =
                    seedAccount(
                            authorLogin.subject(), "SELF_REGISTERED", authorLogin.email(), true);
            UUID studentId =
                    seedAccount(
                            studentLogin.subject(), "SELF_REGISTERED", studentLogin.email(), true);
            seedAccount(outsiderLogin.subject(), "SELF_REGISTERED", outsiderLogin.email(), true);
            UUID personal = personalWorkspaceOf(authorId);
            seedSubscription(personal, -1, 365, authorId);
            UUID subjectId = firstSubjectId();
            VersionRef version =
                    seedOwnedVersion(
                            personal,
                            authorId,
                            subjectId,
                            "QUESTION",
                            "THAN-BAI-CAU-HOI-MOT",
                            "DAP-AN-CAU-HOI-MOT");
            UUID courseSubjectId = seedTeachingChain(personal, studentId);
            seedActivityWithSubmission(
                    personal,
                    courseSubjectId,
                    version.resourceId(),
                    version.versionId(),
                    authorId,
                    studentId);

            try (var author = openSession(authorLogin)) {
                MockHttpServletResponse body =
                        getRaw(
                                author,
                                bodyUri(personal, version.resourceId(), version.versionId()));
                assertEquals(
                        200,
                        body.getStatus(),
                        "Chủ sở hữu đọc được thân bài, body: " + body.getContentAsString());
                assertTrue(
                        body.getContentAsString().contains("THAN-BAI-CAU-HOI-MOT"),
                        "Thân bài trả đúng nội dung stub của version");
                assertFalse(
                        body.getContentAsString().contains("DAP-AN-CAU-HOI-MOT"),
                        "DTO thân bài không được chứa đáp án");
                assertFalse(
                        body.getContentAsString().contains("answer"),
                        "DTO thân bài không có trường đáp án");

                MockHttpServletResponse answer =
                        getRaw(
                                author,
                                answerUri(personal, version.resourceId(), version.versionId()));
                assertEquals(
                        200,
                        answer.getStatus(),
                        "Chủ sở hữu đọc đáp án qua route riêng, body: "
                                + answer.getContentAsString());
                assertTrue(
                        answer.getContentAsString().contains("DAP-AN-CAU-HOI-MOT"),
                        "Route đáp án trả đúng đáp án stub");
            }

            try (var student = openSession(studentLogin)) {
                MockHttpServletResponse body =
                        getRaw(
                                student,
                                bodyUri(personal, version.resourceId(), version.versionId()));
                assertEquals(
                        200,
                        body.getStatus(),
                        "Học sinh được giao bài đọc được thân bài, body: "
                                + body.getContentAsString());
                MockHttpServletResponse answer =
                        getRaw(
                                student,
                                answerUri(personal, version.resourceId(), version.versionId()));
                assertEquals(403, answer.getStatus(), "Quyền làm bài không mở quyền xem đáp án");
            }

            try (var outsider = openSession(outsiderLogin)) {
                assertEquals(
                        403,
                        getRaw(
                                        outsider,
                                        bodyUri(
                                                personal,
                                                version.resourceId(),
                                                version.versionId()))
                                .getStatus(),
                        "Người không có nguồn quyền nào không đọc được thân bài");
                assertEquals(
                        403,
                        getRaw(
                                        outsider,
                                        answerUri(
                                                personal,
                                                version.resourceId(),
                                                version.versionId()))
                                .getStatus(),
                        "Người không có nguồn quyền nào không đọc được đáp án");
            }
        } finally {
            deleteUser(authorLogin.subject());
            deleteUser(studentLogin.subject());
            deleteUser(outsiderLogin.subject());
        }
    }

    @Test
    void savingSystemVersionKeepsSourceIdentityAndOwner() throws Exception {
        KeycloakLogin systemAuthorLogin = createVerifiedUser("resource-system-author");
        KeycloakLogin saverLogin = createVerifiedUser("resource-saver");
        KeycloakLogin noPlanLogin = createVerifiedUser("resource-saver-no-plan");
        try {
            UUID systemAuthorId =
                    seedAccount(
                            systemAuthorLogin.subject(),
                            "SELF_REGISTERED",
                            systemAuthorLogin.email(),
                            true);
            UUID saverId =
                    seedAccount(saverLogin.subject(), "SELF_REGISTERED", saverLogin.email(), true);
            UUID noPlanId =
                    seedAccount(
                            noPlanLogin.subject(), "SELF_REGISTERED", noPlanLogin.email(), true);
            UUID personal = personalWorkspaceOf(saverId);
            seedSubscription(personal, -1, 365, saverId);
            UUID noPlanPersonal = personalWorkspaceOf(noPlanId);
            VersionRef system =
                    seedSystemVersion(
                            systemAuthorId,
                            firstSubjectId(),
                            "THAN-BAI-HE-THONG",
                            "DAP-AN-HE-THONG");
            UUID systemWorkspace = systemWorkspaceOf(system.resourceId());

            try (var saver = openSession(saverLogin)) {
                JsonNode saved =
                        sampleActionExpectCreated(
                                saver, personal, saveJson(system.resourceId(), system.versionId()));
                assertTrue(
                        saved.hasNonNull("targetId") || saved.hasNonNull("id"),
                        "Lưu thành công trả định danh mục lưu");

                MockHttpServletResponse body =
                        getRaw(saver, bodyUri(personal, system.resourceId(), system.versionId()));
                assertEquals(
                        200,
                        body.getStatus(),
                        "Bản lưu đọc lại được tại workspace nhận với đúng định danh nguồn, body: "
                                + body.getContentAsString());
                assertTrue(
                        body.getContentAsString().contains("THAN-BAI-HE-THONG"),
                        "Bản lưu dùng đúng version nguồn đã lưu");
            }
            assertEquals(
                    systemWorkspace,
                    systemWorkspaceOf(system.resourceId()),
                    "Lưu không đổi chủ sở hữu canonical của nguồn");
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM resource_version WHERE resource_id = ?",
                            Integer.class,
                            system.resourceId()),
                    "Lưu không nhân bản version nguồn");

            try (var noPlan = openSession(noPlanLogin)) {
                sampleAction(
                        noPlan,
                        noPlanPersonal,
                        saveJson(system.resourceId(), system.versionId()),
                        403);
            }
        } finally {
            deleteUser(systemAuthorLogin.subject());
            deleteUser(saverLogin.subject());
            deleteUser(noPlanLogin.subject());
        }
    }

    @Test
    void savedCopyStaysOnOldVersionUntilUpdateIsReceived() throws Exception {
        KeycloakLogin systemAuthorLogin = createVerifiedUser("resource-update-author");
        KeycloakLogin saverLogin = createVerifiedUser("resource-update-saver");
        try {
            UUID systemAuthorId =
                    seedAccount(
                            systemAuthorLogin.subject(),
                            "SELF_REGISTERED",
                            systemAuthorLogin.email(),
                            true);
            UUID saverId =
                    seedAccount(saverLogin.subject(), "SELF_REGISTERED", saverLogin.email(), true);
            UUID personal = personalWorkspaceOf(saverId);
            seedSubscription(personal, -1, 365, saverId);
            VersionRef system =
                    seedSystemVersion(
                            systemAuthorId, firstSubjectId(), "THAN-BAI-BAN-MOT", "DAP-AN-BAN-MOT");
            UUID newVersionId =
                    seedNextLockedVersion(
                            system.resourceId(), 2, "THAN-BAI-BAN-HAI", "DAP-AN-BAN-HAI");

            UUID libraryItemId;
            try (var saver = openSession(saverLogin)) {
                JsonNode saved =
                        sampleActionExpectCreated(
                                saver, personal, saveJson(system.resourceId(), system.versionId()));
                libraryItemId = targetIdOf(saved);

                MockHttpServletResponse oldBody =
                        getRaw(saver, bodyUri(personal, system.resourceId(), system.versionId()));
                assertEquals(
                        200,
                        oldBody.getStatus(),
                        "Bản đã lưu vẫn đọc được sau khi nguồn phát hành bản mới");
                assertTrue(
                        oldBody.getContentAsString().contains("THAN-BAI-BAN-MOT"),
                        "Bản đã lưu đứng yên ở version cũ, không tự nhảy sang bản mới");
                assertEquals(
                        403,
                        getRaw(saver, bodyUri(personal, system.resourceId(), newVersionId))
                                .getStatus(),
                        "Chưa nhận cập nhật thì nơi nhận chưa dùng được version mới");

                sampleActionExpectCreated(
                        saver,
                        personal,
                        "{\"action\":\"RECEIVE_UPDATE\",\"libraryItemId\":\""
                                + libraryItemId
                                + "\",\"replacementVersionId\":\""
                                + newVersionId
                                + "\"}");

                MockHttpServletResponse newBody =
                        getRaw(saver, bodyUri(personal, system.resourceId(), newVersionId));
                assertEquals(
                        200,
                        newBody.getStatus(),
                        "Sau khi chủ động nhận cập nhật, nơi nhận dùng được version mới");
                assertTrue(
                        newBody.getContentAsString().contains("THAN-BAI-BAN-HAI"),
                        "Lần dùng sau nhận đúng nội dung version mới");
                assertEquals(
                        200,
                        getRaw(saver, bodyUri(personal, system.resourceId(), system.versionId()))
                                .getStatus(),
                        "Version cũ vẫn đọc lại được sau khi nhận bản mới");
            }
        } finally {
            deleteUser(systemAuthorLogin.subject());
            deleteUser(saverLogin.subject());
        }
    }

    @Test
    void forkCreatesNewIdentityWithProvenanceAndUseOnlyCannotFork() throws Exception {
        KeycloakLogin authorLogin = createVerifiedUser("resource-fork-author");
        KeycloakLogin studentLogin = createVerifiedUser("resource-fork-student");
        try {
            UUID authorId =
                    seedAccount(
                            authorLogin.subject(), "SELF_REGISTERED", authorLogin.email(), true);
            UUID studentId =
                    seedAccount(
                            studentLogin.subject(), "SELF_REGISTERED", studentLogin.email(), true);
            UUID personal = personalWorkspaceOf(authorId);
            seedSubscription(personal, -1, 365, authorId);
            VersionRef version =
                    seedOwnedVersion(
                            personal,
                            authorId,
                            firstSubjectId(),
                            "QUESTION",
                            "THAN-BAI-GOC",
                            "DAP-AN-GOC");
            UUID courseSubjectId = seedTeachingChain(personal, studentId);
            seedActivityWithSubmission(
                    personal,
                    courseSubjectId,
                    version.resourceId(),
                    version.versionId(),
                    authorId,
                    studentId);

            UUID forkId;
            try (var author = openSession(authorLogin)) {
                JsonNode forked =
                        sampleActionExpectCreated(
                                author,
                                personal,
                                forkJson(
                                        version.resourceId(),
                                        version.versionId(),
                                        "Bản chỉnh sửa"));
                forkId = targetIdOf(forked);
                assertNotEquals(
                        version.resourceId(),
                        forkId,
                        "Fork tạo resource mới với định danh mới, không dùng lại ID nguồn");

                UUID forkVersionId =
                        java.util.Objects.requireNonNull(
                                jdbc.queryForObject(
                                        "SELECT id FROM resource_version WHERE resource_id = ?"
                                                + " ORDER BY version_no LIMIT 1",
                                        UUID.class,
                                        forkId),
                                "Fork phải có version đầu của riêng nó");
                assertNotEquals(
                        version.versionId(),
                        forkVersionId,
                        "Version của fork là định danh mới, truy vết version nguồn");
                MockHttpServletResponse forkBody =
                        getRaw(author, bodyUri(personal, forkId, forkVersionId));
                assertEquals(
                        200,
                        forkBody.getStatus(),
                        "Bản fork đọc được tại workspace nhận, body: "
                                + forkBody.getContentAsString());
                assertTrue(
                        forkBody.getContentAsString().contains("THAN-BAI-GOC"),
                        "Fork bắt đầu từ đúng nội dung của version nguồn");
            }
            assertEquals(
                    personal,
                    systemWorkspaceOf(forkId),
                    "Fork thuộc workspace nhận; chủ sở hữu canonical của nguồn không đổi");
            assertEquals(
                    authorId,
                    jdbc.queryForObject(
                            "SELECT created_by_user_id FROM resource WHERE id = ?",
                            UUID.class,
                            forkId),
                    "Fork ghi người tạo là actor fork tại nơi nhận");

            try (var student = openSession(studentLogin)) {
                sampleAction(
                        student,
                        personal,
                        forkJson(version.resourceId(), version.versionId(), "Fork trái quyền"),
                        403);
            }
        } finally {
            deleteUser(authorLogin.subject());
            deleteUser(studentLogin.subject());
        }
    }

    @Test
    void correctionReachesOnlyRecipientsOfAffectedVersion() throws Exception {
        KeycloakLogin ownerLogin = createVerifiedUser("correction-center-owner");
        KeycloakLogin authorLogin = createVerifiedUser("correction-author");
        KeycloakLogin saverLogin = createVerifiedUser("correction-saver");
        KeycloakLogin strangerLogin = createVerifiedUser("correction-stranger");
        try {
            UUID ownerId =
                    seedAccount(ownerLogin.subject(), "SELF_REGISTERED", ownerLogin.email(), true);
            UUID authorId =
                    seedAccount(
                            authorLogin.subject(), "SELF_REGISTERED", authorLogin.email(), true);
            UUID saverId =
                    seedAccount(saverLogin.subject(), "SELF_REGISTERED", saverLogin.email(), true);
            UUID strangerId =
                    seedAccount(
                            strangerLogin.subject(),
                            "SELF_REGISTERED",
                            strangerLogin.email(),
                            true);
            UUID center = seedCenter(ownerId);
            addMembership(center, authorId, "TEACHER");
            seedSubscription(center, -1, 365, ownerId);
            UUID saverPersonal = personalWorkspaceOf(saverId);
            seedSubscription(saverPersonal, -1, 365, saverId);
            UUID strangerPersonal = personalWorkspaceOf(strangerId);
            seedSubscription(strangerPersonal, -1, 365, strangerId);
            VersionRef version =
                    seedOwnedVersion(
                            center,
                            authorId,
                            firstSubjectId(),
                            "QUESTION",
                            "THAN-BAI-BI-LOI",
                            "DAP-AN-BI-LOI");
            UUID replacementVersionId =
                    seedNextLockedVersion(
                            version.resourceId(),
                            2,
                            "THAN-BAI-DA-DINH-CHINH",
                            "DAP-AN-DA-DINH-CHINH");

            UUID libraryItemId;
            try (var saver = openSession(saverLogin)) {
                JsonNode saved =
                        sampleActionExpectCreated(
                                saver,
                                saverPersonal,
                                saveJson(version.resourceId(), version.versionId()));
                libraryItemId = targetIdOf(saved);
            }

            try (var author = openSession(authorLogin)) {
                sampleActionExpectCreated(
                        author,
                        center,
                        "{\"action\":\"CORRECT_SOURCE\",\"resourceId\":\""
                                + version.resourceId()
                                + "\",\"affectedVersionId\":\""
                                + version.versionId()
                                + "\",\"replacementVersionId\":\""
                                + replacementVersionId
                                + "\",\"reason\":\"Sai đáp án ở bản đầu\"}");
            }
            assertEquals(
                    "THAN-BAI-BI-LOI",
                    jdbc.queryForObject(
                            "SELECT body_stub FROM resource_version WHERE id = ?",
                            String.class,
                            version.versionId()),
                    "Đính chính tạo bản thay thế và thông báo, không sửa nội dung version bị lỗi");

            try (var saver = openSession(saverLogin)) {
                sampleActionExpectCreated(
                        saver,
                        saverPersonal,
                        "{\"action\":\"RECEIVE_UPDATE\",\"libraryItemId\":\""
                                + libraryItemId
                                + "\",\"replacementVersionId\":\""
                                + replacementVersionId
                                + "\"}");
                MockHttpServletResponse corrected =
                        getRaw(
                                saver,
                                bodyUri(saverPersonal, version.resourceId(), replacementVersionId));
                assertEquals(
                        200,
                        corrected.getStatus(),
                        "Nơi đã nhận version bị lỗi dùng được bản đính chính sau khi nhận cập nhật");
                assertTrue(
                        corrected.getContentAsString().contains("THAN-BAI-DA-DINH-CHINH"),
                        "Bản đính chính là version thay thế của cùng nguồn");
                assertEquals(
                        200,
                        getRaw(
                                        saver,
                                        bodyUri(
                                                saverPersonal,
                                                version.resourceId(),
                                                version.versionId()))
                                .getStatus(),
                        "Version bị lỗi vẫn là lịch sử đọc lại được, không bị ghi đè");
            }

            try (var stranger = openSession(strangerLogin)) {
                assertEquals(
                        403,
                        getRaw(
                                        stranger,
                                        bodyUri(
                                                strangerPersonal,
                                                version.resourceId(),
                                                replacementVersionId))
                                .getStatus(),
                        "Thông báo đính chính không cấp quyền xem version cho nơi chưa từng nhận");
                sampleAction(
                        stranger,
                        strangerPersonal,
                        "{\"action\":\"RECEIVE_UPDATE\",\"libraryItemId\":\""
                                + libraryItemId
                                + "\",\"replacementVersionId\":\""
                                + replacementVersionId
                                + "\"}",
                        403);
            }
        } finally {
            deleteUser(ownerLogin.subject());
            deleteUser(authorLogin.subject());
            deleteUser(saverLogin.subject());
            deleteUser(strangerLogin.subject());
        }
    }
}
