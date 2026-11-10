package com.giaokhoa.teaching.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

class TeachingRepositoryPaginationTest {
    private static final UUID WORKSPACE = UUID.randomUUID();
    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID PREVIOUS = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TeachingRepository repository = new TeachingRepository(jdbc);

    @DisplayName("Đọc batch course subjects giữ các dòng theo nhóm và bỏ qua đầu vào rỗng")
    @Test
    void batchSubjectsGroupedSkipsEmpty() {
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
}
