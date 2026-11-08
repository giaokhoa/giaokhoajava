package com.giaokhoa.teaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Hai route đọc danh sách phân công hiện hành: GET giáo viên của một môn–khóa và GET chủ nhiệm của
 * một lớp là nguồn assignmentId cho các route DELETE thu hồi tương ứng. Danh sách chỉ chứa phân
 * công đang hiệu lực (đã thu hồi thì biến mất), quyết quyền theo nguồn đọc roster như danh sách ghi
 * danh và roster của lớp: quản trị, giáo viên được phân công đúng môn–khóa, chủ nhiệm hiện hành của
 * lớp.
 */
@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeachingAssignmentListIT extends TeachingFixture {
    private static final String SCHEMA =
            "teaching_assignment_list_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        isolatedSchema(registry, SCHEMA);
    }

    @AfterAll
    static void removeSchema(@Autowired ApplicationContext context) {
        stopSchedulerAndDropSchema(context, SCHEMA);
    }

    @Test
    void teacherAssignmentListIsSourceForRevoke() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner())) {
            UUID classId = createClass(owner, fixture.workspaceId(), "Lớp danh sách giáo viên");
            List<UUID> subjects = subjectIds(owner, 1);
            UUID courseId =
                    createCourse(owner, fixture.workspaceId(), classId, "Khóa danh sách", subjects);
            UUID courseSubjectId = courseSubjectId(courseId, subjects.getFirst());
            String uri =
                    "/api/workspaces/"
                            + fixture.workspaceId()
                            + "/course-subjects/"
                            + courseSubjectId
                            + "/teachers";

            JsonNode empty = getJson(owner, uri, 200);
            assertEquals(0, empty.get("items").size(), "Chưa phân công thì danh sách rỗng");

            assignTeachers(
                    owner,
                    fixture.workspaceId(),
                    courseSubjectId,
                    List.of(fixture.teacherAId(), fixture.teacherBId()));

            JsonNode listed = getJson(owner, uri, 200);
            assertEquals(2, listed.get("items").size());
            Set<UUID> teacherIds = new HashSet<>();
            UUID assignmentOfA = null;
            for (JsonNode item : listed.get("items")) {
                assertFalse(item.get("id").stringValue().isBlank(), "Mỗi dòng phải kèm id");
                assertEquals(courseSubjectId.toString(), item.get("courseSubjectId").stringValue());
                assertEquals("ACTIVE", item.get("state").stringValue());
                UUID teacherId = UUID.fromString(item.get("teacherUserId").stringValue());
                teacherIds.add(teacherId);
                if (teacherId.equals(fixture.teacherAId())) {
                    assignmentOfA = UUID.fromString(item.get("id").stringValue());
                }
            }
            assertEquals(Set.of(fixture.teacherAId(), fixture.teacherBId()), teacherIds);

            try (var teacher = openSession(fixture.teacherA())) {
                JsonNode seen = getJson(teacher, uri, 200);
                assertEquals(
                        2,
                        seen.get("items").size(),
                        "Giáo viên được phân công đọc được roster phân công của môn–khóa");
            }
            try (var student = openSession(fixture.students().getFirst())) {
                JsonNode deniedBody = getJson(student, uri, 403);
                assertEquals("PERMISSION_DENIED", deniedBody.get("code").stringValue());
            }

            UUID operationId = postMutation(owner, uri + "/" + assignmentOfA, "{}", "DELETE");
            awaitOperationSucceeded(jdbc, operationId);

            JsonNode afterRevoke = getJson(owner, uri, 200);
            assertEquals(
                    1,
                    afterRevoke.get("items").size(),
                    "Phân công đã thu hồi không còn trong danh sách hiện hành");
            assertEquals(
                    fixture.teacherBId().toString(),
                    afterRevoke.get("items").get(0).get("teacherUserId").stringValue());
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void homeroomAssignmentListIsSourceForRevoke() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner())) {
            UUID classId = createClass(owner, fixture.workspaceId(), "Lớp danh sách chủ nhiệm");
            String uri =
                    "/api/workspaces/"
                            + fixture.workspaceId()
                            + "/classes/"
                            + classId
                            + "/homeroom-assignments";

            JsonNode empty = getJson(owner, uri, 200);
            assertEquals(0, empty.get("items").size(), "Chưa phân công thì danh sách rỗng");

            UUID operationId =
                    postMutation(
                            owner,
                            uri,
                            "{\"teacherUserId\":\"" + fixture.teacherAId() + "\"}",
                            "POST");
            awaitOperationSucceeded(jdbc, operationId);

            JsonNode listed = getJson(owner, uri, 200);
            assertEquals(1, listed.get("items").size());
            JsonNode item = listed.get("items").get(0);
            assertEquals(classId.toString(), item.get("classId").stringValue());
            assertEquals(fixture.teacherAId().toString(), item.get("teacherUserId").stringValue());
            assertEquals("ACTIVE", item.get("state").stringValue());
            UUID assignmentId = UUID.fromString(item.get("id").stringValue());

            try (var homeroom = openSession(fixture.teacherA())) {
                JsonNode seen = getJson(homeroom, uri, 200);
                assertEquals(
                        1,
                        seen.get("items").size(),
                        "Chủ nhiệm hiện hành đọc được danh sách chủ nhiệm của lớp mình");
            }
            try (var otherTeacher = openSession(fixture.teacherB())) {
                getJson(otherTeacher, uri, 403);
            }

            UUID revokeOperation = postMutation(owner, uri + "/" + assignmentId, "{}", "DELETE");
            awaitOperationSucceeded(jdbc, revokeOperation);

            JsonNode afterRevoke = getJson(owner, uri, 200);
            assertEquals(
                    0,
                    afterRevoke.get("items").size(),
                    "Phân công đã thu hồi không còn trong danh sách hiện hành");
        } finally {
            deleteFixture(fixture);
        }
    }

    @Test
    void assignmentListsFollowNotFoundAndDeniedPrecedents() throws Exception {
        Fixture fixture = seedTeachingCenter();
        try (var owner = openSession(fixture.owner());
                var teacher = openSession(fixture.teacherA())) {
            JsonNode missingSubject =
                    getJson(
                            owner,
                            "/api/workspaces/"
                                    + fixture.workspaceId()
                                    + "/course-subjects/"
                                    + UUID.randomUUID()
                                    + "/teachers",
                            404);
            assertEquals("COURSE_SUBJECT_NOT_FOUND", missingSubject.get("code").stringValue());

            JsonNode missingClass =
                    getJson(
                            owner,
                            "/api/workspaces/"
                                    + fixture.workspaceId()
                                    + "/classes/"
                                    + UUID.randomUUID()
                                    + "/homeroom-assignments",
                            404);
            assertEquals("CLASS_NOT_FOUND", missingClass.get("code").stringValue());

            UUID classId = createClass(owner, fixture.workspaceId(), "Lớp chưa phân công");
            List<UUID> subjects = subjectIds(owner, 1);
            UUID courseId =
                    createCourse(
                            owner, fixture.workspaceId(), classId, "Khóa chưa phân công", subjects);
            UUID courseSubjectId = courseSubjectId(courseId, subjects.getFirst());
            getJson(
                    teacher,
                    "/api/workspaces/"
                            + fixture.workspaceId()
                            + "/course-subjects/"
                            + courseSubjectId
                            + "/teachers",
                    403);
        } finally {
            deleteFixture(fixture);
        }
    }
}
