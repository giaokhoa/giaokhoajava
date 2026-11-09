package com.giaokhoa.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.giaokhoa.authorization.operation.IdempotencyService;
import com.giaokhoa.authorization.policy.ResourceMutationCoordinator;
import com.giaokhoa.identity.InitialCredentialHandoff;
import com.giaokhoa.tenancy.model.MembershipRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MembershipServiceTest {
    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PREVIOUS = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FIRST = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SECOND = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MembershipService service =
            new MembershipService(
                    jdbc,
                    mock(IdempotencyService.class),
                    mock(InitialCredentialHandoff.class),
                    mock(MembershipPolicyProjector.class),
                    mock(ResourceMutationCoordinator.class));

    @Test
    void revokedCursorStillStartsAtNextMembershipAndFetchesOnlyOnePage() throws SQLException {
        OffsetDateTime joinedAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        // Anchor lookup must include REVOKED rows so a deletion cannot erase the next page.
        when(jdbc.queryForList(
                        contains("SELECT joined_at"),
                        eq(OffsetDateTime.class),
                        eq(WORKSPACE),
                        eq(PREVIOUS)))
                .thenReturn(List.of(joinedAt));
        when(jdbc.query(
                        anyString(),
                        org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
                        any(Object[].class)))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    Object[] args =
                            Arrays.copyOfRange(call.getArguments(), 2, call.getArguments().length);
                    assertTrue(sql.contains("(joined_at, id) > (?, ?)"));
                    assertTrue(sql.contains("LIMIT ?"));
                    assertEquals(List.of(WORKSPACE, joinedAt, PREVIOUS, 2), List.of(args));
                    RowMapper<?> mapper = call.getArgument(1);
                    return List.of(
                            membershipRow(mapper, FIRST, "MANAGER"),
                            membershipRow(mapper, FIRST, "OWNER"),
                            membershipRow(mapper, SECOND, "STUDENT"));
                });

        MembershipService.MembershipPage page =
                service.pageMemberships(WORKSPACE, PREVIOUS.toString(), 1);

        assertEquals(1, page.items().size());
        assertEquals(FIRST, page.items().getFirst().id());
        assertEquals(
                Set.of(MembershipRole.Role.MANAGER, MembershipRole.Role.OWNER),
                Set.copyOf(page.items().getFirst().roles()));
        assertEquals(FIRST.toString(), page.nextCursor());
    }

    @Test
    void malformedCursorReturnsEmptyPageWithoutQueryingDatabase() {
        MembershipService.MembershipPage page = service.pageMemberships(WORKSPACE, "bad", 20);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        verifyNoInteractions(jdbc);
    }

    private static Object membershipRow(RowMapper<?> mapper, UUID id, String role)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getObject("user_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getString("username")).thenReturn("learner");
        when(rs.getString("state")).thenReturn("ACTIVE");
        when(rs.getLong("version")).thenReturn(1L);
        when(rs.getString("role")).thenReturn(role);
        return mapper.mapRow(rs, 0);
    }
}
