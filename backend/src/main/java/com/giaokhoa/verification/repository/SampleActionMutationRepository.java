package com.giaokhoa.verification.repository;

import com.giaokhoa.verification.model.SampleActionMutation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SampleActionMutationRepository
        extends JpaRepository<SampleActionMutation, SampleActionMutation.Id>, SampleActionClaim {

    /** Sửa định danh đích của biên nhận vừa claim về đúng dòng thật đã chèn (khe đua tra cứu). */
    @Modifying
    @Query(
            "UPDATE SampleActionMutation m SET m.targetId = :targetId"
                    + " WHERE m.id.actorUserId = :actorUserId"
                    + " AND m.id.idempotencyKey = :idempotencyKey")
    void retarget(
            @Param("actorUserId") UUID actorUserId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("targetId") UUID targetId);
}
