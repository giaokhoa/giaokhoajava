package com.giaokhoa.authorization.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthzProjectionRepositoryImpl implements AuthzProjectionRepositoryCustom {

    private final JdbcTemplate jdbc;

    AuthzProjectionRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DependentProjection> findReadyDependentsOfAny(Collection<UUID> partitionIds) {
        // Toán tử chồng mảng && của PostgreSQL không có tương đương trong JPQL nên điểm này giữ
        // SQL native trong fragment của repository (Spring Data JPA Reference, Native Queries).
        return jdbc.query(
                "SELECT id, partition_id FROM authz_projection"
                        + " WHERE state = 'READY' AND dependency_partition_ids && ?",
                ps ->
                        ps.setArray(
                                1,
                                ps.getConnection().createArrayOf("uuid", partitionIds.toArray())),
                (rs, rowNum) ->
                        new DependentProjection(
                                rs.getObject("id", UUID.class),
                                rs.getObject("partition_id", UUID.class)));
    }
}
