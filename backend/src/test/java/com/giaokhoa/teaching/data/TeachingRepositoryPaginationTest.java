package com.giaokhoa.teaching.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

class TeachingRepositoryPaginationTest {
    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PREVIOUS = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FIRST = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SECOND = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TEACHER = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TeachingRepository repository = new TeachingRepository(jdbc);

    @Test
    void revokedCursorRemainsUsableAndTeacherFilterRunsBeforeLimit() throws SQLException {
        OffsetDateTime time = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        when(jdbc.queryForList(
                        contains("SELECT created_at FROM school_class"),
                        eq(OffsetDateTime.class),
                        eq(WORKSPACE),
                        eq(PREVIOUS)))
                .thenReturn(List.of(time));
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class)))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    Object[] args =
                            Arrays.copyOfRange(call.getArguments(), 2, call.getArguments().length);
                    assertTrue(sql.contains("EXISTS (SELECT 1 FROM homeroom_assignment"));
                    assertTrue(sql.contains("c.state = 'ACTIVE'"));
                    assertTrue(sql.contains("(c.created_at, c.id) > (?, ?)"));
                    assertTrue(sql.endsWith("LIMIT ?"));
                    assertEquals(List.of(WORKSPACE, TEACHER, time, PREVIOUS, 2), List.of(args));
                    RowMapper<?> mapper = call.getArgument(1);
                    return List.of(classRow(mapper, FIRST), classRow(mapper, SECOND));
                });

        TeachingRepository.Page<TeachingRepository.ClassRow> page =
                repository.pageClasses(WORKSPACE, PREVIOUS.toString(), 1, TEACHER);

        assertEquals(1, page.items().size());
        assertEquals(FIRST, page.items().getFirst().id());
        assertEquals(FIRST.toString(), page.nextCursor());
    }

    @Test
    void allOtherTeachingListsLimitRowsInDatabase() {
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class)))
                .thenReturn(List.of());

        repository.pageRoster(WORKSPACE, PREVIOUS, null, 20);
        repository.pageCourses(WORKSPACE, PREVIOUS, null, 20);
        repository.pageEnrollments(WORKSPACE, PREVIOUS, null, 20);
        repository.pageTeachingAssignments(WORKSPACE, PREVIOUS, null, 20);
        repository.pageHomeroomAssignments(WORKSPACE, PREVIOUS, null, 20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(5))
                .query(
                        sql.capture(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class));
        for (String query : sql.getAllValues()) {
            assertTrue(query.contains("ORDER BY "));
            assertTrue(query.endsWith("LIMIT ?"));
        }
    }

    @Test
    void batchCourseSubjectsKeepsRowsGroupedAndSkipsEmptyInput() {
        UUID courseA = FIRST;
        UUID courseB = SECOND;
        UUID subjectId = PREVIOUS;
        var a1 =
                new TeachingRepository.CourseSubjectRow(
                        UUID.randomUUID(), courseA, subjectId, "Toán", "ACTIVE");
        var a2 =
                new TeachingRepository.CourseSubjectRow(
                        UUID.randomUUID(), courseA, subjectId, "Văn", "ACTIVE");
        var b1 =
                new TeachingRepository.CourseSubjectRow(
                        UUID.randomUUID(), courseB, subjectId, "Toán", "ACTIVE");
        when(jdbc.query(
                        any(PreparedStatementCreator.class),
                        org.mockito.ArgumentMatchers
                                .<RowMapper<TeachingRepository.CourseSubjectRow>>any()))
                .thenReturn(List.of(a1, a2, b1));

        var grouped = repository.currentSubjectsByCourse(WORKSPACE, List.of(courseA, courseB));

        assertEquals(List.of(a1, a2), grouped.get(courseA));
        assertEquals(List.of(b1), grouped.get(courseB));
        assertTrue(repository.currentSubjectsByCourse(WORKSPACE, List.of()).isEmpty());
    }

    @Test
    void cursorFromAnotherClassCannotAdvanceRosterOrCoursePage() {
        UUID otherClass = FIRST;
        when(jdbc.queryForList(
                        contains("AND class_id = ?"),
                        eq(OffsetDateTime.class),
                        eq(WORKSPACE),
                        eq(PREVIOUS),
                        eq(otherClass)))
                .thenReturn(List.of());

        var rosterPage = repository.pageRoster(WORKSPACE, otherClass, PREVIOUS.toString(), 20);
        var coursePage = repository.pageCourses(WORKSPACE, otherClass, PREVIOUS.toString(), 20);

        assertTrue(rosterPage.items().isEmpty());
        assertTrue(coursePage.items().isEmpty());
        verify(jdbc, times(2))
                .queryForList(
                        contains("AND class_id = ?"),
                        eq(OffsetDateTime.class),
                        eq(WORKSPACE),
                        eq(PREVIOUS),
                        eq(otherClass));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void invalidCursorDoesNotReadUnboundedData() {
        var page = repository.pageClasses(WORKSPACE, "not-a-uuid", 100, null);
        assertTrue(page.items().isEmpty());
        verifyNoInteractions(jdbc);
    }

    private static Object classRow(RowMapper<?> mapper, UUID id) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getObject("workspace_id", UUID.class)).thenReturn(WORKSPACE);
        when(rs.getString("name")).thenReturn("Lop test");
        when(rs.getString("state")).thenReturn("ACTIVE");
        when(rs.getLong("version")).thenReturn(0L);
        return mapper.mapRow(rs, 0);
    }
}
