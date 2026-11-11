-- Baseline hệ thống: toàn bộ cấu trúc dữ liệu của mặt phẳng hệ thống và của mặt phẳng
-- dùng chung cho workspace chưa kích hoạt, gộp từ chuỗi migration đầu tiên. File chạy
-- trong schema đích mà Flyway trỏ tới (public ở deployment chuẩn; schema riêng ở các
-- môi trường kiểm thử cô lập) nên mọi statement để tên trần như chuỗi migration cũ.
-- Mọi thay đổi cấu trúc kể từ đây đi qua migration tăng dần mới; không sửa file này.
--
-- Cách ly tenant không dùng row-level security: workspace đã kích hoạt có schema riêng
-- (xem baseline tenant), còn các bảng tenant trong public là mặt phẳng dùng chung cho
-- workspace chưa kích hoạt và luôn được lọc bằng workspace_id ở truy vấn ứng dụng.
--
-- Cột resource.subject_id cố ý không có khóa ngoại: subject là dữ kiện danh mục của mặt
-- phẳng hệ thống trong khi resource tồn tại ở cả hai mặt phẳng nên khóa ngoại xuyên
-- schema không dùng được; tính toàn vẹn của tham chiếu do tầng ứng dụng bảo đảm, cùng
-- mẫu với các cột tham chiếu xuyên mặt phẳng khác trong baseline này.

-- Các hàm PL/pgSQL đứng trước bảng mà chúng tham chiếu trong file gộp này nên phải hoãn
-- kiểm tra thân hàm tới lần chạy đầu tiên (đúng cách pg_restore xử lý dump); chỉ áp
-- trong transaction của migration.
SET LOCAL check_function_bodies = false;

--
--

CREATE FUNCTION create_authz_scope() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO authz_partition(id, kind, owner_key)
    VALUES (gen_random_uuid(),
        CASE WHEN TG_TABLE_NAME = 'user_account' THEN 'ACCOUNT' ELSE 'WORKSPACE' END,
        CASE WHEN TG_TABLE_NAME = 'user_account' THEN 'account:' ELSE 'workspace:' END || NEW.id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_directory_immutable_links() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_TABLE_NAME = 'workspace' THEN
        IF OLD.kind IS DISTINCT FROM NEW.kind
            OR OLD.owner_user_id IS DISTINCT FROM NEW.owner_user_id THEN
            RAISE EXCEPTION 'Workspace kind and personal owner cannot change' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'center' THEN
        IF OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
            OR OLD.owner_user_id IS DISTINCT FROM NEW.owner_user_id THEN
            RAISE EXCEPTION 'Center workspace and owner cannot change' USING ERRCODE = '23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'center_membership' THEN
        IF OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
            OR OLD.user_id IS DISTINCT FROM NEW.user_id THEN
            RAISE EXCEPTION 'Membership workspace and user cannot change' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_directory_workspace_kind() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    actual_kind VARCHAR(16);
BEGIN
    SELECT kind INTO actual_kind FROM workspace WHERE id = NEW.workspace_id;
    IF TG_TABLE_NAME = 'center_membership' AND actual_kind <> 'CENTER' THEN
        RAISE EXCEPTION 'Center membership requires a CENTER workspace' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'subscription_period' AND actual_kind NOT IN ('PERSONAL', 'CENTER') THEN
        RAISE EXCEPTION 'Subscription requires a PERSONAL or CENTER workspace' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_resource_immutable_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.owner_workspace_id IS DISTINCT FROM NEW.owner_workspace_id THEN
        RAISE EXCEPTION 'Resource owner workspace cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_authz_operation_payload() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (OLD.id, OLD.initiator_user_id, OLD.workspace_id, OLD.idempotency_key,
        OLD.payload_hash, OLD.affected_partitions, OLD.affected_revisions, OLD.desired_revision)
        IS DISTINCT FROM
        (NEW.id, NEW.initiator_user_id, NEW.workspace_id, NEW.idempotency_key,
        NEW.payload_hash, NEW.affected_partitions, NEW.affected_revisions, NEW.desired_revision) THEN
        RAISE EXCEPTION 'Operation idempotency payload cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('SUCCEEDED', 'FAILED') AND OLD.state <> NEW.state THEN
        RAISE EXCEPTION 'Terminal authorization operation cannot restart' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_authz_partition_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (OLD.id, OLD.kind, OLD.owner_key) IS DISTINCT FROM (NEW.id, NEW.kind, NEW.owner_key)
        OR NEW.desired_revision < OLD.desired_revision
        OR NEW.applied_revision < OLD.applied_revision THEN
        RAISE EXCEPTION 'Partition identity/revision cannot be rewritten' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_authz_projection_desired() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    owner_id UUID;
    desired_changed BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        owner_id := OLD.partition_id;
        desired_changed := TRUE;
    ELSIF TG_OP = 'INSERT' THEN
        owner_id := NEW.partition_id;
        desired_changed := TRUE;
    ELSE
        IF OLD.partition_id <> NEW.partition_id THEN
            RAISE EXCEPTION 'Projection partition cannot change' USING ERRCODE = '23514';
        END IF;
        owner_id := NEW.partition_id;
        desired_changed := (OLD.target_key, OLD.keycloak_resource_id, OLD.policy_names,
                            OLD.dependency_partition_ids, OLD.desired_hash)
            IS DISTINCT FROM
            (NEW.target_key, NEW.keycloak_resource_id, NEW.policy_names,
             NEW.dependency_partition_ids, NEW.desired_hash);
    END IF;
    IF desired_changed AND EXISTS (
        SELECT 1 FROM authz_partition p WHERE p.id = owner_id AND p.state = 'READY'
    ) THEN
        RAISE EXCEPTION 'Desired projection changes require a partition fence'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_class_student_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Roster history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_id IS DISTINCT FROM NEW.class_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Roster identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked roster entry cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active roster entry cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_correction_notice_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Correction notice history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Correction notice cannot be modified' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_course_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Course history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_id IS DISTINCT FROM NEW.class_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Course identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked course cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active course cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_course_subject_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Course subject history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_id IS DISTINCT FROM NEW.class_id
        OR OLD.course_id IS DISTINCT FROM NEW.course_id
        OR OLD.subject_id IS DISTINCT FROM NEW.subject_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Course subject identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked course subject cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active course subject cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_directory_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% history cannot be hard-deleted', TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;

    IF TG_TABLE_NAME = 'center_membership' THEN
        IF OLD.id IS DISTINCT FROM NEW.id
            OR OLD.joined_at IS DISTINCT FROM NEW.joined_at
            OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
            RAISE EXCEPTION 'Membership identity and join history cannot change' USING ERRCODE = '23514';
        END IF;
        IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Revoked membership cannot be modified' USING ERRCODE = '23514';
        END IF;
        IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
            RAISE EXCEPTION 'Active membership cannot return to PENDING' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF (OLD.id, OLD.workspace_id, OLD.confirmation_ref, OLD.confirmed_by,
                OLD.confirmed_at, OLD.created_at)
            IS DISTINCT FROM
            (NEW.id, NEW.workspace_id, NEW.confirmation_ref, NEW.confirmed_by,
                NEW.confirmed_at, NEW.created_at) THEN
            RAISE EXCEPTION 'Subscription confirmation history cannot change' USING ERRCODE = '23514';
        END IF;
        IF (OLD.valid_from, OLD.expires_at) IS DISTINCT FROM (NEW.valid_from, NEW.expires_at)
            AND (OLD.valid_from <= clock_timestamp()
                OR NEW.valid_from <= clock_timestamp()
                OR NEW.version <= OLD.version) THEN
            RAISE EXCEPTION 'Only future subscription periods may change with a new version'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_enrollment_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Enrollment history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_student_id IS DISTINCT FROM NEW.class_student_id
        OR OLD.course_subject_id IS DISTINCT FROM NEW.course_subject_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Enrollment identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked enrollment cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active enrollment cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_guardian_link_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Guardian link history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.parent_user_id IS DISTINCT FROM NEW.parent_user_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.requested_by IS DISTINCT FROM NEW.requested_by
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Guardian link identity cannot change'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('REJECTED', 'REVOKED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Closed guardian link cannot be modified'
            USING ERRCODE = '23514';
    END IF;
    IF (OLD.state = 'REQUESTED' AND NEW.state = 'REVOKED')
        OR (OLD.state = 'APPROVED' AND NEW.state IN ('REQUESTED', 'REJECTED')) THEN
        RAISE EXCEPTION 'Guardian link state transition is not allowed'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_homeroom_assignment_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Homeroom assignment history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.class_id IS DISTINCT FROM NEW.class_id
        OR OLD.teacher_user_id IS DISTINCT FROM NEW.teacher_user_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Homeroom assignment identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked homeroom assignment cannot be modified'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active homeroom assignment cannot return to PENDING'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_invitation_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Invitation history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.target_email IS DISTINCT FROM NEW.target_email
        OR OLD.target_user_id IS DISTINCT FROM NEW.target_user_id
        OR OLD.token_hash IS DISTINCT FROM NEW.token_hash
        OR OLD.intended_roles IS DISTINCT FROM NEW.intended_roles
        OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Invitation identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_library_item_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Library item history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.resource_id IS DISTINCT FROM NEW.resource_id
        OR OLD.version_id IS DISTINCT FROM NEW.version_id
        OR OLD.share_grant_id IS DISTINCT FROM NEW.share_grant_id
        OR OLD.custody IS DISTINCT FROM NEW.custody
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Library item source reference cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_notification_receipt_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Notification receipt history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.notice_id IS DISTINCT FROM NEW.notice_id
        OR OLD.recipient_workspace_id IS DISTINCT FROM NEW.recipient_workspace_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at
        OR (OLD.acknowledged_at IS NOT NULL
            AND OLD.acknowledged_at IS DISTINCT FROM NEW.acknowledged_at) THEN
        RAISE EXCEPTION 'Notification receipt identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_practice_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Practice history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF (OLD.id, OLD.personal_workspace_id, OLD.user_id, OLD.system_resource_id,
            OLD.version_id, OLD.attempted_at, OLD.content_stub, OLD.created_at)
        IS DISTINCT FROM
        (NEW.id, NEW.personal_workspace_id, NEW.user_id, NEW.system_resource_id,
            NEW.version_id, NEW.attempted_at, NEW.content_stub, NEW.created_at) THEN
        RAISE EXCEPTION 'Practice history identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_recovery_request_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Recovery request history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.requester_user_id IS DISTINCT FROM NEW.requester_user_id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.target_user_id IS DISTINCT FROM NEW.target_user_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Recovery request identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_resource_assignment_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Resource assignment history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.user_id IS DISTINCT FROM NEW.user_id
        OR OLD.resource_id IS DISTINCT FROM NEW.resource_id
        OR OLD.subject_id IS DISTINCT FROM NEW.subject_id
        OR OLD.actions IS DISTINCT FROM NEW.actions
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Resource assignment identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked resource assignment cannot be modified'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active resource assignment cannot return to PENDING'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_resource_dependency_acyclic() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (
        WITH RECURSIVE reach (resource_id, version_id) AS (
            SELECT NEW.source_resource_id, NEW.source_version_id
            UNION
            SELECT d.source_resource_id, d.source_version_id
            FROM resource_dependency d
            JOIN reach r ON d.derived_resource_id = r.resource_id
                AND d.derived_version_id = r.version_id
        )
        SELECT 1 FROM reach
        WHERE resource_id = NEW.derived_resource_id AND version_id = NEW.derived_version_id
    ) THEN
        RAISE EXCEPTION 'Resource dependency graph cannot contain a cycle'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_resource_dependency_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Resource dependency history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.derived_resource_id IS DISTINCT FROM NEW.derived_resource_id
        OR OLD.derived_version_id IS DISTINCT FROM NEW.derived_version_id
        OR OLD.source_resource_id IS DISTINCT FROM NEW.source_resource_id
        OR OLD.source_version_id IS DISTINCT FROM NEW.source_version_id
        OR OLD.share_grant_id IS DISTINCT FROM NEW.share_grant_id
        OR OLD.usage_constraints IS DISTINCT FROM NEW.usage_constraints
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Resource dependency provenance cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_resource_version() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.locked_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Locked resource version cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF (OLD.review_state = 'DRAFT' AND NEW.review_state = 'LOCKED')
        OR (OLD.review_state = 'APPROVED' AND NEW.review_state = 'DRAFT') THEN
        RAISE EXCEPTION 'Resource version review state must advance DRAFT -> APPROVED -> LOCKED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_sample_activity_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Sample activity history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ARCHIVED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Archived sample activity cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.course_subject_id IS DISTINCT FROM NEW.course_subject_id
        OR OLD.resource_id IS DISTINCT FROM NEW.resource_id
        OR OLD.version_id IS DISTINCT FROM NEW.version_id
        OR OLD.assigned_by IS DISTINCT FROM NEW.assigned_by
        OR OLD.assigned_at IS DISTINCT FROM NEW.assigned_at
        OR OLD.attempt_limit IS DISTINCT FROM NEW.attempt_limit
        OR OLD.subscription_period_id IS DISTINCT FROM NEW.subscription_period_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Sample activity snapshot cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'CLOSED' AND NEW.state = 'ASSIGNED' THEN
        RAISE EXCEPTION 'Closed sample activity cannot return to ASSIGNED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_sample_result_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Sample result history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'PUBLISHED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Published sample result cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.submission_id IS DISTINCT FROM NEW.submission_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.course_subject_id IS DISTINCT FROM NEW.course_subject_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Sample result identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_sample_submission_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Sample submission history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.activity_id IS DISTINCT FROM NEW.activity_id
        OR OLD.student_user_id IS DISTINCT FROM NEW.student_user_id
        OR OLD.attempt_no IS DISTINCT FROM NEW.attempt_no
        OR OLD.submitted_at IS DISTINCT FROM NEW.submitted_at
        OR OLD.content_stub IS DISTINCT FROM NEW.content_stub
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Sample submission content cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_school_class_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Class history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Class identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked class cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active class cannot return to PENDING' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_share_grant_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Share grant history cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.source_resource_id IS DISTINCT FROM NEW.source_resource_id
        OR OLD.source_version_id IS DISTINCT FROM NEW.source_version_id
        OR OLD.grantor_user_id IS DISTINCT FROM NEW.grantor_user_id
        OR OLD.recipient_workspace_id IS DISTINCT FROM NEW.recipient_workspace_id
        OR OLD.mode IS DISTINCT FROM NEW.mode
        OR OLD.actions IS DISTINCT FROM NEW.actions
        OR OLD.allowed_workspace_ids IS DISTINCT FROM NEW.allowed_workspace_ids
        OR OLD.valid_from IS DISTINCT FROM NEW.valid_from
        OR OLD.expires_at IS DISTINCT FROM NEW.expires_at
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Share grant identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('REVOKED', 'EXPIRED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Closed share grant cannot be modified' USING ERRCODE = '23514';
    END IF;
    IF NEW.state IS DISTINCT FROM OLD.state
        AND NOT ((OLD.state = 'OFFERED' AND NEW.state IN ('ACCEPTED', 'EXPIRED'))
            OR (OLD.state = 'ACCEPTED' AND NEW.state IN ('REVOKED', 'EXPIRED'))) THEN
        RAISE EXCEPTION 'Share grant state transition is not allowed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_subject_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Subject cannot be hard-deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.code IS DISTINCT FROM NEW.code THEN
        RAISE EXCEPTION 'Subject identity cannot change' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION guard_teaching_assignment_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Teaching assignment history cannot be hard-deleted'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.id IS DISTINCT FROM NEW.id
        OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
        OR OLD.course_subject_id IS DISTINCT FROM NEW.course_subject_id
        OR OLD.teacher_user_id IS DISTINCT FROM NEW.teacher_user_id
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'Teaching assignment identity cannot change' USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'REVOKED' AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'Revoked teaching assignment cannot be modified'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' THEN
        RAISE EXCEPTION 'Active teaching assignment cannot return to PENDING'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION preserve_authz_partition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Authorization partitions cannot be deleted' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION reject_audit_event_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Audit history cannot be modified or deleted' USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION sync_oidc_correlation_session() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        DELETE FROM oidc_session_correlation WHERE session_id = OLD.session_id;
    ELSIF OLD.session_id IS DISTINCT FROM NEW.session_id THEN
        UPDATE oidc_session_correlation SET session_id = NEW.session_id
        WHERE session_id = OLD.session_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION validate_authz_partition_ids() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    ids UUID[];
BEGIN
    IF TG_TABLE_NAME = 'authz_projection' THEN
        ids := NEW.dependency_partition_ids;
    ELSE
        ids := NEW.affected_partitions;
    END IF;
    IF TG_TABLE_NAME = 'authorization_operation' THEN
        IF cardinality(ids) = 0
            OR cardinality(NEW.affected_revisions) <> cardinality(ids)
            OR EXISTS (SELECT 1 FROM unnest(NEW.affected_revisions) AS rev(value)
                       WHERE rev.value IS NULL OR rev.value < 0) THEN
            RAISE EXCEPTION 'Invalid operation revision vector' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(ids) AS ref(id) WHERE ref.id IS NULL)
        OR cardinality(ids) <> (SELECT count(DISTINCT ref.id) FROM unnest(ids) AS ref(id))
        OR EXISTS (
            SELECT 1 FROM unnest(ids) AS referenced(id)
            WHERE NOT EXISTS (SELECT 1 FROM authz_partition p WHERE p.id = referenced.id)
        ) THEN
        RAISE EXCEPTION 'Invalid authorization partition references' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'authz_projection' THEN
        IF NEW.partition_id = ANY(ids) THEN
            RAISE EXCEPTION 'Projection cannot depend on its own partition' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_class_student_membership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    workspace_kind VARCHAR(16);
BEGIN
    SELECT kind INTO workspace_kind FROM workspace WHERE id = NEW.workspace_id;
    IF workspace_kind = 'CENTER' AND NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.student_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Center roster requires an active center membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_enrollment_same_class() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM class_student cs, course_subject css
        WHERE cs.id = NEW.class_student_id
          AND css.id = NEW.course_subject_id
          AND cs.class_id = css.class_id
    ) THEN
        RAISE EXCEPTION 'Enrollment requires both sides in the same class'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_guardian_link_approval() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.state = 'REQUESTED' AND NEW.state = 'APPROVED' AND NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.parent_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION
            'Guardian link approval requires the parent to be an active center member'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_guardian_link_membership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    workspace_kind VARCHAR(16);
BEGIN
    SELECT kind INTO workspace_kind FROM workspace WHERE id = NEW.workspace_id;
    IF workspace_kind IS DISTINCT FROM 'CENTER' THEN
        RAISE EXCEPTION 'Guardian link requires a CENTER workspace'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.parent_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Guardian link requires the parent to be an active center member'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.student_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Guardian link requires the student to be an active center member'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_homeroom_assignment_membership() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    workspace_kind VARCHAR(16);
BEGIN
    SELECT kind INTO workspace_kind FROM workspace WHERE id = NEW.workspace_id;
    IF workspace_kind = 'CENTER' AND NOT EXISTS (
        SELECT 1 FROM center_membership
        WHERE workspace_id = NEW.workspace_id
          AND user_id = NEW.teacher_user_id
          AND state = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Homeroom assignment requires an active center membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_practice_attempt() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    workspace_kind VARCHAR(16);
    workspace_owner UUID;
    resource_owner UUID;
    version_resource UUID;
    version_state VARCHAR(16);
BEGIN
    SELECT kind, owner_user_id INTO workspace_kind, workspace_owner
    FROM workspace WHERE id = NEW.personal_workspace_id;
    IF workspace_kind <> 'PERSONAL' OR workspace_owner <> NEW.user_id THEN
        RAISE EXCEPTION 'Practice history requires the actor own personal workspace'
            USING ERRCODE = '23514';
    END IF;
    SELECT owner_workspace_id INTO resource_owner
    FROM resource WHERE id = NEW.system_resource_id;
    IF NOT EXISTS (
        SELECT 1 FROM workspace WHERE id = resource_owner AND kind = 'SYSTEM'
    ) THEN
        RAISE EXCEPTION 'Practice requires a system resource' USING ERRCODE = '23514';
    END IF;
    SELECT resource_id, review_state INTO version_resource, version_state
    FROM resource_version WHERE id = NEW.version_id;
    IF version_resource <> NEW.system_resource_id OR version_state <> 'LOCKED' THEN
        RAISE EXCEPTION 'Practice requires a published version of the system resource'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_sample_result_source() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    source_student_user_id UUID;
    source_course_subject_id UUID;
BEGIN
    SELECT s.student_user_id, a.course_subject_id
        INTO source_student_user_id, source_course_subject_id
        FROM sample_submission s
        JOIN sample_activity a ON a.id = s.activity_id
    WHERE s.id = NEW.submission_id AND s.workspace_id = NEW.workspace_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Sample result requires a submission in the same workspace'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.student_user_id IS DISTINCT FROM source_student_user_id
        OR NEW.course_subject_id IS DISTINCT FROM source_course_subject_id THEN
        RAISE EXCEPTION 'Sample result student and subject must derive from the submission'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_sample_submission() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    activity_attempt_limit INTEGER;
    activity_course_subject_id UUID;
BEGIN
    SELECT attempt_limit, course_subject_id
        INTO activity_attempt_limit, activity_course_subject_id
        FROM sample_activity
        WHERE id = NEW.activity_id AND workspace_id = NEW.workspace_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Sample submission requires an activity in the same workspace'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.attempt_no > activity_attempt_limit THEN
        RAISE EXCEPTION 'Sample submission attempt exceeds the activity attempt limit'
            USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1
            FROM enrollment e
            JOIN class_student cs ON cs.id = e.class_student_id
        WHERE e.workspace_id = NEW.workspace_id
          AND e.course_subject_id = activity_course_subject_id
          AND e.state IN ('PENDING', 'ACTIVE')
          AND cs.workspace_id = NEW.workspace_id
          AND cs.student_user_id = NEW.student_user_id
    ) THEN
        RAISE EXCEPTION 'Sample submission requires a current enrollment of the student'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION verify_authz_operation_succeeded() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    current_row authorization_operation%ROWTYPE;
BEGIN
    SELECT * INTO current_row FROM authorization_operation WHERE id = NEW.id;
    IF NOT FOUND THEN RETURN NEW; END IF;
    IF current_row.state = 'SUCCEEDED' AND EXISTS (
        SELECT 1 FROM unnest(current_row.affected_partitions, current_row.affected_revisions)
            AS ref(id, revision)
        LEFT JOIN authz_partition p ON p.id = ref.id
        WHERE p.id IS NULL OR p.state <> 'READY'
            OR p.desired_revision <> ref.revision
            OR p.applied_revision <> ref.revision
    ) THEN
        RAISE EXCEPTION 'Operation cannot succeed before affected partitions are READY'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION verify_authz_partition_ready() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    current_row authz_partition%ROWTYPE;
BEGIN
    SELECT * INTO current_row FROM authz_partition WHERE id = NEW.id;
    IF NOT FOUND THEN RETURN NEW; END IF;

    IF current_row.state = 'READY' AND (
        current_row.desired_revision <> current_row.applied_revision
        OR EXISTS (
            SELECT 1 FROM authz_projection p
            WHERE p.partition_id = current_row.id AND p.state <> 'READY'
        )
    ) THEN
        RAISE EXCEPTION 'Partition cannot be READY with unapplied projections/revision'
            USING ERRCODE = '23514';
    END IF;

    -- Mọi thay đổi nguồn phải fence mọi projection phụ thuộc, ngay cả khi
    -- nguồn đạt một READY revision mới hơn trong cùng transaction.
    IF TG_OP = 'UPDATE' AND
        (OLD.desired_revision IS DISTINCT FROM NEW.desired_revision
         OR current_row.state <> 'READY') AND EXISTS (
            SELECT 1 FROM authz_projection p
            WHERE current_row.id = ANY(p.dependency_partition_ids) AND p.state = 'READY'
        ) THEN
        RAISE EXCEPTION 'Dependent projections must be fenced before source changes'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION verify_authz_projection_ready() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    current_row authz_projection%ROWTYPE;
BEGIN
    SELECT * INTO current_row FROM authz_projection WHERE id = NEW.id;
    IF NOT FOUND THEN RETURN NEW; END IF;

    IF current_row.state = 'READY' AND EXISTS (
        SELECT 1 FROM unnest(current_row.dependency_partition_ids) AS dep(id)
        LEFT JOIN authz_partition p ON p.id = dep.id
        WHERE p.id IS NULL OR p.state <> 'READY'
            OR p.desired_revision <> p.applied_revision
    ) THEN
        RAISE EXCEPTION 'Projection dependencies must be READY' USING ERRCODE = '23514';
    END IF;

    IF current_row.state <> 'READY' AND EXISTS (
        SELECT 1 FROM authz_partition p
        WHERE p.id = current_row.partition_id AND p.state = 'READY'
    ) THEN
        RAISE EXCEPTION 'READY partition cannot contain a pending projection'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION verify_center_owner() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    target_workspace UUID;
    expected_owner UUID;
    workspace_kind VARCHAR(16);
BEGIN
    IF TG_TABLE_NAME = 'center' THEN
        target_workspace := CASE WHEN TG_OP = 'DELETE' THEN OLD.workspace_id ELSE NEW.workspace_id END;
    ELSIF TG_TABLE_NAME = 'center_membership' THEN
        target_workspace := CASE WHEN TG_OP = 'DELETE' THEN OLD.workspace_id ELSE NEW.workspace_id END;
    ELSIF TG_TABLE_NAME = 'workspace' THEN
        target_workspace := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;
    ELSE
        SELECT workspace_id INTO target_workspace FROM center_membership
        WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.membership_id ELSE NEW.membership_id END;
    END IF;

    SELECT c.owner_user_id, w.kind INTO expected_owner, workspace_kind
    FROM center c JOIN workspace w ON w.id = c.workspace_id
    WHERE c.workspace_id = target_workspace;
    IF expected_owner IS NOT NULL THEN
        IF workspace_kind <> 'CENTER' OR NOT EXISTS (
            SELECT 1 FROM center_membership m
            JOIN membership_role r ON r.membership_id = m.id AND r.role = 'OWNER'
            WHERE m.workspace_id = target_workspace AND m.user_id = expected_owner
                AND m.state IN ('PENDING', 'ACTIVE')
        ) OR EXISTS (
            SELECT 1 FROM center_membership m
            JOIN membership_role r ON r.membership_id = m.id AND r.role = 'OWNER'
            WHERE m.workspace_id = target_workspace
                AND (m.user_id <> expected_owner OR m.state = 'REVOKED')
        ) THEN
            RAISE EXCEPTION 'CENTER requires exactly its designated owner membership and OWNER role'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION verify_user_personal_workspace() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    owner_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'user_account' THEN
        owner_id := NEW.id;
    ELSE
        owner_id := OLD.owner_user_id;
    END IF;
    IF owner_id IS NOT NULL
        AND EXISTS (SELECT 1 FROM user_account WHERE id = owner_id)
        AND NOT EXISTS (
            SELECT 1 FROM workspace WHERE kind = 'PERSONAL' AND owner_user_id = owner_id
        ) THEN
        RAISE EXCEPTION 'User account requires a personal workspace' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

SET default_tablespace = '';

SET default_table_access_method = heap;

CREATE TABLE audit_event (
    id uuid NOT NULL,
    actor_user_id uuid,
    workspace_id uuid,
    action character varying(32) NOT NULL,
    target_type character varying(32) NOT NULL,
    target_id uuid NOT NULL,
    operation_id uuid,
    outcome character varying(16) NOT NULL,
    occurred_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT audit_event_action_check CHECK (((action)::text = ANY ((ARRAY['PRIVILEGE_CHANGE'::character varying, 'SUBSCRIPTION_CHANGE'::character varying, 'RELATIONSHIP_CHANGE'::character varying, 'RECOVERY'::character varying, 'AUTHORIZATION_SYNC'::character varying])::text[]))),
    CONSTRAINT audit_event_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['PENDING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT audit_event_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['ACCOUNT'::character varying, 'WORKSPACE'::character varying, 'MEMBERSHIP'::character varying, 'SUBSCRIPTION'::character varying, 'RELATIONSHIP'::character varying, 'GRANT'::character varying, 'AUTHORIZATION_OPERATION'::character varying])::text[])))
);

CREATE TABLE authorization_operation (
    id uuid NOT NULL,
    initiator_user_id uuid NOT NULL,
    workspace_id uuid,
    idempotency_key character varying(255) NOT NULL,
    payload_hash character varying(64) NOT NULL,
    affected_partitions uuid[] NOT NULL,
    affected_revisions bigint[] NOT NULL,
    desired_revision bigint NOT NULL,
    state character varying(24) DEFAULT 'PENDING'::character varying NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone,
    last_error_code character varying(64),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    recorded_seq bigint NOT NULL,
    CONSTRAINT authorization_operation_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT authorization_operation_desired_revision_check CHECK ((desired_revision >= 0)),
    CONSTRAINT authorization_operation_idempotency_key_check CHECK ((btrim((idempotency_key)::text) <> ''::text)),
    CONSTRAINT authorization_operation_last_error_code_check CHECK (((last_error_code IS NULL) OR (btrim((last_error_code)::text) <> ''::text))),
    CONSTRAINT authorization_operation_payload_hash_check CHECK (((payload_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT authorization_operation_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'APPLYING'::character varying, 'SUCCEEDED'::character varying, 'RETRYABLE_FAILURE'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_authz_operation_revision_vector CHECK ((((array_ndims(affected_partitions) = 1) AND (array_lower(affected_partitions, 1) = 1) AND (array_ndims(affected_revisions) = 1) AND (array_lower(affected_revisions, 1) = 1) AND (cardinality(affected_revisions) = cardinality(affected_partitions)) AND (desired_revision = affected_revisions[1])) IS TRUE)),
    CONSTRAINT ck_authz_operation_succeeded CHECK ((((state)::text <> 'SUCCEEDED'::text) OR ((next_attempt_at IS NULL) AND (last_error_code IS NULL))))
);

ALTER TABLE authorization_operation ALTER COLUMN recorded_seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME authorization_operation_recorded_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE authz_partition (
    id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    owner_key character varying(255) NOT NULL,
    desired_revision bigint DEFAULT 0 NOT NULL,
    applied_revision bigint DEFAULT 0 NOT NULL,
    state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT authz_partition_applied_revision_check CHECK ((applied_revision >= 0)),
    CONSTRAINT authz_partition_desired_revision_check CHECK ((desired_revision >= 0)),
    CONSTRAINT authz_partition_kind_check CHECK (((kind)::text = ANY ((ARRAY['WORKSPACE'::character varying, 'GRANT'::character varying, 'ACCOUNT'::character varying, 'PLATFORM'::character varying])::text[]))),
    CONSTRAINT authz_partition_owner_key_check CHECK ((btrim((owner_key)::text) <> ''::text)),
    CONSTRAINT authz_partition_state_check CHECK (((state)::text = ANY ((ARRAY['READY'::character varying, 'PENDING'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_authz_partition_ready CHECK ((((state)::text <> 'READY'::text) OR (desired_revision = applied_revision))),
    CONSTRAINT ck_authz_partition_revision CHECK ((applied_revision <= desired_revision))
);

CREATE TABLE authz_projection (
    id uuid NOT NULL,
    partition_id uuid NOT NULL,
    target_key character varying(1024) NOT NULL,
    keycloak_resource_id character varying(512),
    policy_names text[] DEFAULT '{}'::text[] NOT NULL,
    dependency_partition_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    desired_hash character varying(64) NOT NULL,
    applied_hash character varying(64),
    state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT authz_projection_applied_hash_check CHECK (((applied_hash IS NULL) OR ((applied_hash)::text ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT authz_projection_desired_hash_check CHECK (((desired_hash)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT authz_projection_keycloak_resource_id_check CHECK (((keycloak_resource_id IS NULL) OR (btrim((keycloak_resource_id)::text) <> ''::text))),
    CONSTRAINT authz_projection_state_check CHECK (((state)::text = ANY ((ARRAY['READY'::character varying, 'PENDING'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT authz_projection_target_key_check CHECK ((btrim((target_key)::text) <> ''::text)),
    CONSTRAINT ck_authz_projection_ready CHECK ((((state)::text <> 'READY'::text) OR ((applied_hash IS NOT NULL) AND ((applied_hash)::text = (desired_hash)::text) AND (keycloak_resource_id IS NOT NULL))))
);

CREATE TABLE center (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    keycloak_organization_id character varying(512) NOT NULL,
    name character varying(255) NOT NULL,
    owner_user_id uuid NOT NULL,
    state character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT center_keycloak_organization_id_check CHECK ((btrim((keycloak_organization_id)::text) <> ''::text)),
    CONSTRAINT center_name_check CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT center_state_check CHECK ((btrim((state)::text) <> ''::text))
);

CREATE TABLE center_membership (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    user_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    joined_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT center_membership_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT ck_membership_revocation CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL) AND (revoked_at >= joined_at))))
);

CREATE TABLE class_student (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    class_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_class_student_revocation CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT class_student_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE correction_notice (
    id uuid NOT NULL,
    resource_id uuid NOT NULL,
    affected_version_id uuid NOT NULL,
    replacement_version_id uuid NOT NULL,
    reason text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_correction_notice_replacement_differs CHECK ((replacement_version_id <> affected_version_id)),
    CONSTRAINT correction_notice_reason_check CHECK ((btrim(reason) <> ''::text))
);

CREATE TABLE course (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    class_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    state character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT course_name_check CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT course_state_check CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE course_subject (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    class_id uuid NOT NULL,
    course_id uuid NOT NULL,
    subject_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_course_subject_revocation CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT course_subject_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE enrollment (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    class_student_id uuid NOT NULL,
    course_subject_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_enrollment_revocation CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT enrollment_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE guardian_link (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    parent_user_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    requested_by uuid NOT NULL,
    approved_by uuid,
    approved_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_guardian_link_state_fields CHECK (((((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'REJECTED'::character varying])::text[])) AND (approved_by IS NULL) AND (approved_at IS NULL) AND (revoked_at IS NULL)) OR (((state)::text = 'APPROVED'::text) AND (approved_by IS NOT NULL) AND (approved_at IS NOT NULL) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (approved_by IS NOT NULL) AND (approved_at IS NOT NULL) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT guardian_link_state_check CHECK (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE homeroom_assignment (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    class_id uuid NOT NULL,
    teacher_user_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_homeroom_assignment_revocation CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT homeroom_assignment_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE invitation (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    target_email character varying(320),
    target_user_id uuid,
    token_hash character varying(64) NOT NULL,
    intended_roles text[] NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    state character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_invitation_exactly_one_target CHECK (((target_email IS NULL) <> (target_user_id IS NULL))),
    CONSTRAINT invitation_intended_roles_check CHECK (((intended_roles <> '{}'::text[]) AND (intended_roles <@ ARRAY['MANAGER'::text, 'TEACHER'::text, 'STUDENT'::text, 'PARENT'::text]))),
    CONSTRAINT invitation_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACCEPTED'::character varying, 'EXPIRED'::character varying, 'REVOKED'::character varying])::text[]))),
    CONSTRAINT invitation_target_email_check CHECK (((target_email IS NULL) OR (btrim((target_email)::text) <> ''::text)))
);

CREATE TABLE library_item (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    version_id uuid NOT NULL,
    share_grant_id uuid,
    custody character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT library_item_custody_check CHECK (((custody)::text = ANY ((ARRAY['OWNED'::character varying, 'LICENSED'::character varying])::text[])))
);

CREATE TABLE membership_index (
    user_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT membership_index_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE membership_role (
    membership_id uuid NOT NULL,
    role character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT membership_role_role_check CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'MANAGER'::character varying, 'TEACHER'::character varying, 'STUDENT'::character varying, 'PARENT'::character varying])::text[])))
);

CREATE TABLE notification_receipt (
    notice_id uuid NOT NULL,
    recipient_workspace_id uuid NOT NULL,
    acknowledged_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE oidc_session_correlation (
    session_id character(36) NOT NULL,
    issuer character varying(2048) NOT NULL,
    client_id character varying(255) NOT NULL,
    sub character varying(512) NOT NULL,
    sid character varying(512),
    recorded_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    csrf_header_name character varying(128),
    csrf_token character varying(1024),
    CONSTRAINT ck_oidc_correlation_csrf CHECK (((csrf_header_name IS NULL) = (csrf_token IS NULL)))
);

CREATE TABLE recovery_mutation (
    actor_user_id uuid NOT NULL,
    idempotency_key character varying(255) NOT NULL,
    payload_hash character varying(64) NOT NULL,
    request_id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT recovery_mutation_kind_check CHECK (((kind)::text = ANY ((ARRAY['SUBMIT'::character varying, 'RESOLVE'::character varying])::text[])))
);

CREATE TABLE recovery_request (
    id uuid NOT NULL,
    requester_user_id uuid NOT NULL,
    workspace_id uuid,
    target_user_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    verified_by uuid,
    resolved_at timestamp with time zone,
    verification_note character varying(1000),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    reason character varying(1000),
    CONSTRAINT ck_recovery_request_verification CHECK (((((state)::text = 'REQUESTED'::text) AND (verified_by IS NULL) AND (resolved_at IS NULL)) OR (((state)::text = 'VERIFIED'::text) AND (verified_by IS NOT NULL) AND (resolved_at IS NULL)) OR (((state)::text = ANY ((ARRAY['RESOLVED'::character varying, 'REJECTED'::character varying])::text[])) AND (verified_by IS NOT NULL) AND (resolved_at IS NOT NULL)))),
    CONSTRAINT recovery_request_reason_check CHECK (((reason IS NULL) OR (btrim((reason)::text) <> ''::text))),
    CONSTRAINT recovery_request_state_check CHECK (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'VERIFIED'::character varying, 'RESOLVED'::character varying, 'REJECTED'::character varying])::text[]))),
    CONSTRAINT recovery_request_verification_note_check CHECK (((verification_note IS NULL) OR (btrim((verification_note)::text) <> ''::text)))
);

CREATE TABLE resource (
    id uuid NOT NULL,
    owner_workspace_id uuid NOT NULL,
    created_by_user_id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    subject_id uuid NOT NULL,
    state character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT resource_kind_check CHECK (((kind)::text = ANY ((ARRAY['QUESTION'::character varying, 'EXAM'::character varying, 'MATRIX'::character varying])::text[]))),
    CONSTRAINT resource_state_check CHECK ((btrim((state)::text) <> ''::text))
);

CREATE TABLE resource_assignment (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    user_id uuid NOT NULL,
    resource_id uuid,
    subject_id uuid,
    actions text[] NOT NULL,
    state character varying(16) NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_resource_assignment_has_target CHECK (((resource_id IS NOT NULL) OR (subject_id IS NOT NULL))),
    CONSTRAINT ck_resource_assignment_revocation CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT resource_assignment_actions_check CHECK (((actions <> '{}'::text[]) AND (actions <@ ARRAY['draft.edit'::text, 'review'::text, 'use'::text, 'answer.read'::text, 'share'::text, 'content.read'::text]))),
    CONSTRAINT resource_assignment_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE resource_dependency (
    id uuid NOT NULL,
    derived_resource_id uuid NOT NULL,
    derived_version_id uuid NOT NULL,
    source_resource_id uuid NOT NULL,
    source_version_id uuid NOT NULL,
    share_grant_id uuid,
    usage_constraints text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_resource_dependency_not_self CHECK (((derived_resource_id <> source_resource_id) OR (derived_version_id <> source_version_id))),
    CONSTRAINT resource_dependency_usage_constraints_check CHECK ((btrim(usage_constraints) <> ''::text))
);

CREATE TABLE resource_version (
    id uuid NOT NULL,
    resource_id uuid NOT NULL,
    version_no integer NOT NULL,
    label character varying(255) NOT NULL,
    body_stub text NOT NULL,
    answer_stub text,
    review_state character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    locked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_resource_version_lock CHECK ((((review_state)::text = 'LOCKED'::text) = (locked_at IS NOT NULL))),
    CONSTRAINT resource_version_label_check CHECK ((btrim((label)::text) <> ''::text)),
    CONSTRAINT resource_version_review_state_check CHECK (((review_state)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'LOCKED'::character varying])::text[]))),
    CONSTRAINT resource_version_version_no_check CHECK ((version_no >= 1))
);

CREATE TABLE sample_action_mutation (
    actor_user_id uuid NOT NULL,
    idempotency_key character varying(255) NOT NULL,
    payload_hash character varying(64) NOT NULL,
    workspace_id uuid NOT NULL,
    target_type character varying(32) NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT sample_action_mutation_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['RESOURCE'::character varying, 'ACTIVITY'::character varying, 'SUBMISSION'::character varying, 'RESULT'::character varying, 'LIBRARY_ITEM'::character varying, 'SHARE_GRANT'::character varying, 'CORRECTION_NOTICE'::character varying])::text[])))
);

CREATE TABLE sample_activity (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    course_subject_id uuid NOT NULL,
    resource_id uuid NOT NULL,
    version_id uuid NOT NULL,
    assigned_by uuid NOT NULL,
    assigned_at timestamp with time zone NOT NULL,
    due_at timestamp with time zone NOT NULL,
    attempt_limit integer NOT NULL,
    subscription_period_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT sample_activity_attempt_limit_check CHECK ((attempt_limit >= 1)),
    CONSTRAINT sample_activity_state_check CHECK (((state)::text = ANY ((ARRAY['ASSIGNED'::character varying, 'CLOSED'::character varying, 'ARCHIVED'::character varying])::text[])))
);

CREATE TABLE sample_result (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    submission_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    course_subject_id uuid NOT NULL,
    score_stub text,
    comment_stub text,
    state character varying(16) NOT NULL,
    published_at timestamp with time zone,
    graded_by uuid,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_sample_result_publication CHECK ((((state)::text = 'PUBLISHED'::text) = (published_at IS NOT NULL))),
    CONSTRAINT sample_result_state_check CHECK (((state)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying])::text[])))
);

CREATE TABLE sample_submission (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    activity_id uuid NOT NULL,
    student_user_id uuid NOT NULL,
    attempt_no integer NOT NULL,
    submitted_at timestamp with time zone NOT NULL,
    content_stub text NOT NULL,
    state character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT sample_submission_attempt_no_check CHECK ((attempt_no >= 1)),
    CONSTRAINT sample_submission_state_check CHECK ((btrim((state)::text) <> ''::text))
);

CREATE TABLE school_class (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    name character varying(255) NOT NULL,
    state character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT school_class_name_check CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT school_class_state_check CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE share_grant (
    id uuid NOT NULL,
    source_resource_id uuid NOT NULL,
    source_version_id uuid NOT NULL,
    grantor_user_id uuid NOT NULL,
    recipient_workspace_id uuid NOT NULL,
    mode character varying(16) NOT NULL,
    state character varying(16) NOT NULL,
    actions text[] NOT NULL,
    allowed_workspace_ids uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    valid_from timestamp with time zone NOT NULL,
    expires_at timestamp with time zone,
    accepted_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_share_grant_state_timestamps CHECK (((((state)::text = 'OFFERED'::text) AND (accepted_at IS NULL) AND (revoked_at IS NULL)) OR (((state)::text = 'ACCEPTED'::text) AND (accepted_at IS NOT NULL) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND ((mode)::text = 'REVOCABLE'::text) AND (accepted_at IS NOT NULL) AND (revoked_at IS NOT NULL)) OR (((state)::text = 'EXPIRED'::text) AND (revoked_at IS NULL)))),
    CONSTRAINT ck_share_grant_valid_window CHECK (((expires_at IS NULL) OR (expires_at > valid_from))),
    CONSTRAINT share_grant_actions_check CHECK (((actions <> '{}'::text[]) AND (actions <@ ARRAY['draft.edit'::text, 'review'::text, 'use'::text, 'answer.read'::text, 'share'::text, 'content.read'::text, 'fork'::text, 'library.save'::text]))),
    CONSTRAINT share_grant_mode_check CHECK (((mode)::text = ANY ((ARRAY['REVOCABLE'::character varying, 'LONG_TERM'::character varying])::text[]))),
    CONSTRAINT share_grant_state_check CHECK (((state)::text = ANY ((ARRAY['OFFERED'::character varying, 'ACCEPTED'::character varying, 'REVOKED'::character varying, 'EXPIRED'::character varying])::text[])))
);

CREATE TABLE spring_session (
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(100)
);

CREATE TABLE spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);

CREATE TABLE subject (
    id uuid NOT NULL,
    code character varying(32) NOT NULL,
    name character varying(255) NOT NULL,
    state character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT subject_code_check CHECK ((btrim((code)::text) <> ''::text)),
    CONSTRAINT subject_name_check CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT subject_state_check CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ARCHIVED'::character varying])::text[])))
);

CREATE TABLE subscription_period (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    valid_from timestamp(0) with time zone NOT NULL,
    expires_at timestamp(0) with time zone NOT NULL,
    confirmation_ref character varying(255) NOT NULL,
    confirmed_by uuid NOT NULL,
    confirmed_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_subscription_period_range CHECK ((expires_at > valid_from)),
    CONSTRAINT subscription_period_confirmation_ref_check CHECK ((btrim((confirmation_ref)::text) <> ''::text))
);

CREATE TABLE system_practice_attempt_sample (
    id uuid NOT NULL,
    personal_workspace_id uuid NOT NULL,
    user_id uuid NOT NULL,
    system_resource_id uuid NOT NULL,
    version_id uuid NOT NULL,
    attempted_at timestamp with time zone NOT NULL,
    content_stub text NOT NULL,
    outcome_stub text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT system_practice_attempt_sample_content_stub_check CHECK ((btrim(content_stub) <> ''::text)),
    CONSTRAINT system_practice_attempt_sample_outcome_stub_check CHECK (((outcome_stub IS NULL) OR (btrim(outcome_stub) <> ''::text)))
);

CREATE TABLE teaching_assignment (
    id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    course_subject_id uuid NOT NULL,
    teacher_user_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_teaching_assignment_revocation CHECK (((((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (revoked_at IS NULL)) OR (((state)::text = 'REVOKED'::text) AND (revoked_at IS NOT NULL)))),
    CONSTRAINT teaching_assignment_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'REVOKED'::character varying])::text[])))
);

CREATE TABLE user_account (
    id uuid NOT NULL,
    keycloak_sub character varying(512) NOT NULL,
    username character varying(255) NOT NULL,
    email character varying(320),
    email_verified boolean DEFAULT false NOT NULL,
    origin character varying(32) NOT NULL,
    identity_state character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_user_verified_email CHECK (((email IS NOT NULL) OR (NOT email_verified))),
    CONSTRAINT user_account_email_check CHECK (((email IS NULL) OR (btrim((email)::text) <> ''::text))),
    CONSTRAINT user_account_identity_state_check CHECK ((btrim((identity_state)::text) <> ''::text)),
    CONSTRAINT user_account_keycloak_sub_check CHECK ((btrim((keycloak_sub)::text) <> ''::text)),
    CONSTRAINT user_account_origin_check CHECK (((origin)::text = ANY ((ARRAY['SELF_REGISTERED'::character varying, 'CENTER_ISSUED'::character varying])::text[]))),
    CONSTRAINT user_account_username_check CHECK ((btrim((username)::text) <> ''::text))
);

CREATE TABLE workspace (
    id uuid NOT NULL,
    kind character varying(16) NOT NULL,
    owner_user_id uuid,
    center_id uuid,
    state character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    schema_name character varying(63),
    CONSTRAINT ck_workspace_kind_owner CHECK (((((kind)::text = 'PERSONAL'::text) AND (owner_user_id IS NOT NULL) AND (center_id IS NULL)) OR (((kind)::text = 'CENTER'::text) AND (owner_user_id IS NULL) AND (center_id IS NOT NULL)) OR (((kind)::text = 'SYSTEM'::text) AND (owner_user_id IS NULL) AND (center_id IS NULL)))),
    CONSTRAINT workspace_kind_check CHECK (((kind)::text = ANY ((ARRAY['PERSONAL'::character varying, 'CENTER'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT workspace_state_check CHECK ((btrim((state)::text) <> ''::text))
);

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT audit_event_pkey PRIMARY KEY (id);

ALTER TABLE ONLY authorization_operation
    ADD CONSTRAINT authorization_operation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY authz_partition
    ADD CONSTRAINT authz_partition_pkey PRIMARY KEY (id);

ALTER TABLE ONLY authz_projection
    ADD CONSTRAINT authz_projection_pkey PRIMARY KEY (id);

ALTER TABLE ONLY authz_projection
    ADD CONSTRAINT authz_projection_target_key_key UNIQUE (target_key);

ALTER TABLE ONLY center
    ADD CONSTRAINT center_keycloak_organization_id_key UNIQUE (keycloak_organization_id);

ALTER TABLE ONLY center_membership
    ADD CONSTRAINT center_membership_pkey PRIMARY KEY (id);

ALTER TABLE ONLY center
    ADD CONSTRAINT center_pkey PRIMARY KEY (id);

ALTER TABLE ONLY center
    ADD CONSTRAINT center_workspace_id_key UNIQUE (workspace_id);

ALTER TABLE ONLY class_student
    ADD CONSTRAINT class_student_pkey PRIMARY KEY (id);

ALTER TABLE ONLY correction_notice
    ADD CONSTRAINT correction_notice_pkey PRIMARY KEY (id);

ALTER TABLE ONLY course
    ADD CONSTRAINT course_pkey PRIMARY KEY (id);

ALTER TABLE ONLY course_subject
    ADD CONSTRAINT course_subject_pkey PRIMARY KEY (id);

ALTER TABLE ONLY enrollment
    ADD CONSTRAINT enrollment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guardian_link
    ADD CONSTRAINT guardian_link_pkey PRIMARY KEY (id);

ALTER TABLE ONLY homeroom_assignment
    ADD CONSTRAINT homeroom_assignment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invitation
    ADD CONSTRAINT invitation_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invitation
    ADD CONSTRAINT invitation_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY library_item
    ADD CONSTRAINT library_item_pkey PRIMARY KEY (id);

ALTER TABLE ONLY membership_index
    ADD CONSTRAINT membership_index_pkey PRIMARY KEY (user_id, workspace_id);

ALTER TABLE ONLY membership_role
    ADD CONSTRAINT membership_role_pkey PRIMARY KEY (membership_id, role);

ALTER TABLE ONLY oidc_session_correlation
    ADD CONSTRAINT oidc_session_correlation_pkey PRIMARY KEY (session_id);

ALTER TABLE ONLY notification_receipt
    ADD CONSTRAINT pk_notification_receipt PRIMARY KEY (notice_id, recipient_workspace_id);

ALTER TABLE ONLY recovery_mutation
    ADD CONSTRAINT recovery_mutation_pkey PRIMARY KEY (actor_user_id, idempotency_key);

ALTER TABLE ONLY recovery_request
    ADD CONSTRAINT recovery_request_pkey PRIMARY KEY (id);

ALTER TABLE ONLY resource_assignment
    ADD CONSTRAINT resource_assignment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY resource_dependency
    ADD CONSTRAINT resource_dependency_pkey PRIMARY KEY (id);

ALTER TABLE ONLY resource
    ADD CONSTRAINT resource_pkey PRIMARY KEY (id);

ALTER TABLE ONLY resource_version
    ADD CONSTRAINT resource_version_pkey PRIMARY KEY (id);

ALTER TABLE ONLY sample_action_mutation
    ADD CONSTRAINT sample_action_mutation_pkey PRIMARY KEY (actor_user_id, idempotency_key);

ALTER TABLE ONLY sample_activity
    ADD CONSTRAINT sample_activity_pkey PRIMARY KEY (id);

ALTER TABLE ONLY sample_result
    ADD CONSTRAINT sample_result_pkey PRIMARY KEY (id);

ALTER TABLE ONLY sample_submission
    ADD CONSTRAINT sample_submission_pkey PRIMARY KEY (id);

ALTER TABLE ONLY school_class
    ADD CONSTRAINT school_class_pkey PRIMARY KEY (id);

ALTER TABLE ONLY share_grant
    ADD CONSTRAINT share_grant_pkey PRIMARY KEY (id);

ALTER TABLE ONLY spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name);

ALTER TABLE ONLY spring_session
    ADD CONSTRAINT spring_session_pk PRIMARY KEY (primary_id);

ALTER TABLE ONLY subject
    ADD CONSTRAINT subject_code_key UNIQUE (code);

ALTER TABLE ONLY subject
    ADD CONSTRAINT subject_pkey PRIMARY KEY (id);

ALTER TABLE ONLY subscription_period
    ADD CONSTRAINT subscription_period_confirmation_ref_key UNIQUE (confirmation_ref);

ALTER TABLE ONLY subscription_period
    ADD CONSTRAINT subscription_period_pkey PRIMARY KEY (id);

ALTER TABLE ONLY system_practice_attempt_sample
    ADD CONSTRAINT system_practice_attempt_sample_pkey PRIMARY KEY (id);

ALTER TABLE ONLY teaching_assignment
    ADD CONSTRAINT teaching_assignment_pkey PRIMARY KEY (id);

ALTER TABLE ONLY authorization_operation
    ADD CONSTRAINT uq_authz_operation_idempotency UNIQUE (initiator_user_id, idempotency_key);

ALTER TABLE ONLY authz_partition
    ADD CONSTRAINT uq_authz_partition_owner UNIQUE (kind, owner_key);

ALTER TABLE ONLY center
    ADD CONSTRAINT uq_center_id_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY class_student
    ADD CONSTRAINT uq_class_student_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY course_subject
    ADD CONSTRAINT uq_course_subject_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY course
    ADD CONSTRAINT uq_course_workspace_class_id UNIQUE (workspace_id, class_id, id);

ALTER TABLE ONLY library_item
    ADD CONSTRAINT uq_library_item_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY center_membership
    ADD CONSTRAINT uq_membership_id_workspace UNIQUE (id, workspace_id);

ALTER TABLE ONLY resource_version
    ADD CONSTRAINT uq_resource_version_no UNIQUE (resource_id, version_no);

ALTER TABLE ONLY resource_version
    ADD CONSTRAINT uq_resource_version_resource_id UNIQUE (resource_id, id);

ALTER TABLE ONLY sample_activity
    ADD CONSTRAINT uq_sample_activity_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY sample_submission
    ADD CONSTRAINT uq_sample_submission_attempt UNIQUE (activity_id, student_user_id, attempt_no);

ALTER TABLE ONLY sample_submission
    ADD CONSTRAINT uq_sample_submission_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY school_class
    ADD CONSTRAINT uq_school_class_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY subscription_period
    ADD CONSTRAINT uq_subscription_period_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY user_account
    ADD CONSTRAINT user_account_keycloak_sub_key UNIQUE (keycloak_sub);

ALTER TABLE ONLY user_account
    ADD CONSTRAINT user_account_pkey PRIMARY KEY (id);

ALTER TABLE ONLY user_account
    ADD CONSTRAINT user_account_username_key UNIQUE (username);

ALTER TABLE ONLY workspace
    ADD CONSTRAINT workspace_pkey PRIMARY KEY (id);

CREATE INDEX ix_audit_operation ON audit_event USING btree (operation_id) WHERE (operation_id IS NOT NULL);

CREATE INDEX ix_audit_workspace_occurred ON audit_event USING btree (workspace_id, occurred_at DESC);

CREATE INDEX ix_authz_operation_live_recorded_seq ON authorization_operation USING btree (recorded_seq) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'APPLYING'::character varying, 'RETRYABLE_FAILURE'::character varying])::text[]));

--

CREATE INDEX ix_authz_operation_workspace ON authorization_operation USING btree (workspace_id, created_at DESC);

CREATE INDEX ix_authz_projection_dependencies ON authz_projection USING gin (dependency_partition_ids);

CREATE INDEX ix_authz_projection_partition ON authz_projection USING btree (partition_id);

CREATE INDEX ix_center_membership_listing_page ON center_membership USING btree (workspace_id, joined_at, id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_class_listing_page ON school_class USING btree (workspace_id, created_at, id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX ix_class_student_current_unique ON class_student USING btree (workspace_id, class_id, student_user_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_class_student_workspace_state ON class_student USING btree (workspace_id, state);

CREATE INDEX ix_correction_notice_resource ON correction_notice USING btree (resource_id);

CREATE INDEX ix_course_listing_page ON course USING btree (workspace_id, class_id, created_at, id) WHERE ((state)::text = 'ACTIVE'::text);

CREATE UNIQUE INDEX ix_course_subject_current_unique ON course_subject USING btree (workspace_id, class_id, course_id, subject_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_course_subject_workspace_state ON course_subject USING btree (workspace_id, state);

CREATE INDEX ix_course_workspace_state ON course USING btree (workspace_id, state);

CREATE UNIQUE INDEX ix_enrollment_current_unique ON enrollment USING btree (workspace_id, class_student_id, course_subject_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_enrollment_listing_page ON enrollment USING btree (workspace_id, course_subject_id, created_at, id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_enrollment_workspace_state ON enrollment USING btree (workspace_id, state);

CREATE UNIQUE INDEX ix_guardian_link_current_unique ON guardian_link USING btree (workspace_id, parent_user_id, student_user_id) WHERE ((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'APPROVED'::character varying])::text[]));

CREATE INDEX ix_guardian_link_workspace_state ON guardian_link USING btree (workspace_id, state);

CREATE UNIQUE INDEX ix_homeroom_assignment_current_unique ON homeroom_assignment USING btree (workspace_id, class_id, teacher_user_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_homeroom_assignment_listing_page ON homeroom_assignment USING btree (workspace_id, class_id, created_at, id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_homeroom_assignment_workspace_state ON homeroom_assignment USING btree (workspace_id, state);

CREATE INDEX ix_invitation_target_state ON invitation USING btree (target_user_id, state);

CREATE INDEX ix_invitation_workspace_state ON invitation USING btree (workspace_id, state);

CREATE INDEX ix_library_item_resource_version ON library_item USING btree (resource_id, version_id);

CREATE INDEX ix_library_item_share_grant ON library_item USING btree (share_grant_id) WHERE (share_grant_id IS NOT NULL);

CREATE INDEX ix_library_item_workspace_resource ON library_item USING btree (workspace_id, resource_id);

CREATE INDEX ix_notification_receipt_recipient ON notification_receipt USING btree (recipient_workspace_id);

CREATE INDEX ix_oidc_correlation_sid ON oidc_session_correlation USING btree (issuer, client_id, sid) WHERE (sid IS NOT NULL);

CREATE INDEX ix_oidc_correlation_sub ON oidc_session_correlation USING btree (issuer, client_id, sub);

CREATE INDEX ix_practice_history_workspace_time ON system_practice_attempt_sample USING btree (personal_workspace_id, attempted_at DESC);

CREATE INDEX ix_recovery_request_state ON recovery_request USING btree (state, created_at);

CREATE INDEX ix_recovery_request_target ON recovery_request USING btree (target_user_id, state);

CREATE UNIQUE INDEX ix_resource_assignment_current_resource ON resource_assignment USING btree (workspace_id, user_id, resource_id) WHERE (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (resource_id IS NOT NULL));

CREATE UNIQUE INDEX ix_resource_assignment_current_subject ON resource_assignment USING btree (workspace_id, user_id, subject_id) WHERE (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[])) AND (subject_id IS NOT NULL));

CREATE INDEX ix_resource_assignment_workspace_state ON resource_assignment USING btree (workspace_id, state);

CREATE INDEX ix_resource_dependency_derived ON resource_dependency USING btree (derived_resource_id, derived_version_id);

CREATE INDEX ix_resource_dependency_share_grant ON resource_dependency USING btree (share_grant_id) WHERE (share_grant_id IS NOT NULL);

CREATE INDEX ix_resource_dependency_source_grant ON resource_dependency USING btree (source_version_id, share_grant_id);

CREATE INDEX ix_resource_owner_state ON resource USING btree (owner_workspace_id, state);

CREATE INDEX ix_roster_listing_page ON class_student USING btree (workspace_id, class_id, created_at, id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_sample_activity_course_subject ON sample_activity USING btree (workspace_id, course_subject_id);

CREATE INDEX ix_sample_activity_workspace_state ON sample_activity USING btree (workspace_id, state);

CREATE INDEX ix_sample_result_course_subject ON sample_result USING btree (workspace_id, course_subject_id);

CREATE INDEX ix_sample_result_workspace_state ON sample_result USING btree (workspace_id, state);

CREATE INDEX ix_sample_submission_workspace_state ON sample_submission USING btree (workspace_id, state);

CREATE INDEX ix_school_class_workspace_state ON school_class USING btree (workspace_id, state);

CREATE INDEX ix_share_grant_recipient_state ON share_grant USING btree (recipient_workspace_id, state);

CREATE INDEX ix_share_grant_source_version ON share_grant USING btree (source_resource_id, source_version_id);

CREATE INDEX ix_subscription_workspace_range ON subscription_period USING btree (workspace_id, valid_from, expires_at);

CREATE UNIQUE INDEX ix_teaching_assignment_current_unique ON teaching_assignment USING btree (workspace_id, course_subject_id, teacher_user_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_teaching_assignment_listing_page ON teaching_assignment USING btree (workspace_id, course_subject_id, created_at, id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE INDEX ix_teaching_assignment_workspace_state ON teaching_assignment USING btree (workspace_id, state);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session USING btree (session_id);

CREATE INDEX spring_session_ix2 ON spring_session USING btree (expiry_time);

CREATE INDEX spring_session_ix3 ON spring_session USING btree (principal_name);

CREATE UNIQUE INDEX uq_membership_current ON center_membership USING btree (workspace_id, user_id) WHERE ((state)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying])::text[]));

CREATE UNIQUE INDEX uq_workspace_personal_owner ON workspace USING btree (owner_user_id) WHERE ((kind)::text = 'PERSONAL'::text);

CREATE TRIGGER ck_audit_event_append_only BEFORE DELETE OR UPDATE ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();

CREATE TRIGGER ck_audit_event_no_truncate BEFORE TRUNCATE ON audit_event FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_event_mutation();

CREATE TRIGGER ck_authz_operation_immutable_payload BEFORE UPDATE ON authorization_operation FOR EACH ROW EXECUTE FUNCTION guard_authz_operation_payload();

CREATE TRIGGER ck_authz_operation_refs BEFORE INSERT OR UPDATE OF affected_partitions, affected_revisions ON authorization_operation FOR EACH ROW EXECUTE FUNCTION validate_authz_partition_ids();

CREATE CONSTRAINT TRIGGER ck_authz_operation_success_deferred AFTER INSERT OR UPDATE ON authorization_operation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_authz_operation_succeeded();

CREATE TRIGGER ck_authz_partition_durable BEFORE DELETE ON authz_partition FOR EACH ROW EXECUTE FUNCTION preserve_authz_partition();

CREATE TRIGGER ck_authz_partition_monotonic BEFORE UPDATE ON authz_partition FOR EACH ROW EXECUTE FUNCTION guard_authz_partition_identity();

CREATE CONSTRAINT TRIGGER ck_authz_partition_ready_deferred AFTER INSERT OR UPDATE ON authz_partition DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_authz_partition_ready();

CREATE TRIGGER ck_authz_projection_desired BEFORE INSERT OR DELETE OR UPDATE ON authz_projection FOR EACH ROW EXECUTE FUNCTION guard_authz_projection_desired();

CREATE CONSTRAINT TRIGGER ck_authz_projection_ready_deferred AFTER INSERT OR UPDATE ON authz_projection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_authz_projection_ready();

CREATE TRIGGER ck_authz_projection_refs BEFORE INSERT OR UPDATE OF partition_id, dependency_partition_ids ON authz_projection FOR EACH ROW EXECUTE FUNCTION validate_authz_partition_ids();

CREATE TRIGGER ck_center_immutable_owner BEFORE UPDATE OF workspace_id, owner_user_id ON center FOR EACH ROW EXECUTE FUNCTION enforce_directory_immutable_links();

CREATE CONSTRAINT TRIGGER ck_center_owner AFTER INSERT OR DELETE OR UPDATE ON center DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_center_owner();

CREATE TRIGGER ck_class_student_membership BEFORE INSERT ON class_student FOR EACH ROW EXECUTE FUNCTION validate_class_student_membership();

CREATE TRIGGER ck_class_student_preserve_history BEFORE DELETE OR UPDATE ON class_student FOR EACH ROW EXECUTE FUNCTION guard_class_student_history();

CREATE TRIGGER ck_correction_notice_preserve_history BEFORE DELETE OR UPDATE ON correction_notice FOR EACH ROW EXECUTE FUNCTION guard_correction_notice_history();

CREATE TRIGGER ck_course_preserve_history BEFORE DELETE OR UPDATE ON course FOR EACH ROW EXECUTE FUNCTION guard_course_history();

CREATE TRIGGER ck_course_subject_preserve_history BEFORE DELETE OR UPDATE ON course_subject FOR EACH ROW EXECUTE FUNCTION guard_course_subject_history();

CREATE TRIGGER ck_enrollment_preserve_history BEFORE DELETE OR UPDATE ON enrollment FOR EACH ROW EXECUTE FUNCTION guard_enrollment_history();

CREATE TRIGGER ck_enrollment_same_class BEFORE INSERT ON enrollment FOR EACH ROW EXECUTE FUNCTION validate_enrollment_same_class();

CREATE TRIGGER ck_guardian_link_approval BEFORE UPDATE ON guardian_link FOR EACH ROW EXECUTE FUNCTION validate_guardian_link_approval();

CREATE TRIGGER ck_guardian_link_membership BEFORE INSERT ON guardian_link FOR EACH ROW EXECUTE FUNCTION validate_guardian_link_membership();

CREATE TRIGGER ck_guardian_link_preserve_history BEFORE DELETE OR UPDATE ON guardian_link FOR EACH ROW EXECUTE FUNCTION guard_guardian_link_history();

CREATE TRIGGER ck_homeroom_assignment_membership BEFORE INSERT ON homeroom_assignment FOR EACH ROW EXECUTE FUNCTION validate_homeroom_assignment_membership();

CREATE TRIGGER ck_homeroom_assignment_preserve_history BEFORE DELETE OR UPDATE ON homeroom_assignment FOR EACH ROW EXECUTE FUNCTION guard_homeroom_assignment_history();

CREATE TRIGGER ck_invitation_preserve_history BEFORE DELETE OR UPDATE ON invitation FOR EACH ROW EXECUTE FUNCTION guard_invitation_history();

CREATE TRIGGER ck_library_item_preserve_history BEFORE DELETE OR UPDATE ON library_item FOR EACH ROW EXECUTE FUNCTION guard_library_item_history();

CREATE TRIGGER ck_membership_immutable_identity BEFORE UPDATE OF workspace_id, user_id ON center_membership FOR EACH ROW EXECUTE FUNCTION enforce_directory_immutable_links();

CREATE CONSTRAINT TRIGGER ck_membership_owner AFTER INSERT OR DELETE OR UPDATE ON center_membership DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_center_owner();

CREATE TRIGGER ck_membership_preserve_history BEFORE DELETE OR UPDATE ON center_membership FOR EACH ROW EXECUTE FUNCTION guard_directory_history();

CREATE TRIGGER ck_membership_workspace_kind BEFORE INSERT OR UPDATE OF workspace_id ON center_membership FOR EACH ROW EXECUTE FUNCTION enforce_directory_workspace_kind();

CREATE TRIGGER ck_notification_receipt_preserve_history BEFORE DELETE OR UPDATE ON notification_receipt FOR EACH ROW EXECUTE FUNCTION guard_notification_receipt_history();

CREATE CONSTRAINT TRIGGER ck_personal_workspace_survives AFTER DELETE ON workspace DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_user_personal_workspace();

CREATE TRIGGER ck_practice_attempt_source BEFORE INSERT ON system_practice_attempt_sample FOR EACH ROW EXECUTE FUNCTION validate_practice_attempt();

CREATE TRIGGER ck_practice_history_immutable BEFORE DELETE OR UPDATE ON system_practice_attempt_sample FOR EACH ROW EXECUTE FUNCTION guard_practice_history();

CREATE TRIGGER ck_recovery_request_preserve_history BEFORE DELETE OR UPDATE ON recovery_request FOR EACH ROW EXECUTE FUNCTION guard_recovery_request_history();

CREATE TRIGGER ck_resource_assignment_preserve_history BEFORE DELETE OR UPDATE ON resource_assignment FOR EACH ROW EXECUTE FUNCTION guard_resource_assignment_history();

CREATE TRIGGER ck_resource_dependency_acyclic BEFORE INSERT ON resource_dependency FOR EACH ROW EXECUTE FUNCTION guard_resource_dependency_acyclic();

CREATE TRIGGER ck_resource_dependency_preserve_history BEFORE DELETE OR UPDATE ON resource_dependency FOR EACH ROW EXECUTE FUNCTION guard_resource_dependency_history();

CREATE TRIGGER ck_resource_immutable_owner BEFORE UPDATE OF owner_workspace_id ON resource FOR EACH ROW EXECUTE FUNCTION enforce_resource_immutable_owner();

CREATE TRIGGER ck_resource_version_guard BEFORE UPDATE ON resource_version FOR EACH ROW EXECUTE FUNCTION guard_resource_version();

CREATE CONSTRAINT TRIGGER ck_role_owner AFTER INSERT OR DELETE OR UPDATE ON membership_role DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_center_owner();

CREATE TRIGGER ck_sample_activity_preserve_history BEFORE DELETE OR UPDATE ON sample_activity FOR EACH ROW EXECUTE FUNCTION guard_sample_activity_history();

CREATE TRIGGER ck_sample_result_preserve_history BEFORE DELETE OR UPDATE ON sample_result FOR EACH ROW EXECUTE FUNCTION guard_sample_result_history();

CREATE TRIGGER ck_sample_result_source BEFORE INSERT ON sample_result FOR EACH ROW EXECUTE FUNCTION validate_sample_result_source();

CREATE TRIGGER ck_sample_submission_preserve_history BEFORE DELETE OR UPDATE ON sample_submission FOR EACH ROW EXECUTE FUNCTION guard_sample_submission_history();

CREATE TRIGGER ck_sample_submission_valid BEFORE INSERT ON sample_submission FOR EACH ROW EXECUTE FUNCTION validate_sample_submission();

CREATE TRIGGER ck_school_class_preserve_history BEFORE DELETE OR UPDATE ON school_class FOR EACH ROW EXECUTE FUNCTION guard_school_class_history();

CREATE TRIGGER ck_share_grant_preserve_history BEFORE DELETE OR UPDATE ON share_grant FOR EACH ROW EXECUTE FUNCTION guard_share_grant_history();

CREATE TRIGGER ck_subject_preserve_history BEFORE DELETE OR UPDATE ON subject FOR EACH ROW EXECUTE FUNCTION guard_subject_history();

CREATE TRIGGER ck_subscription_preserve_history BEFORE DELETE OR UPDATE ON subscription_period FOR EACH ROW EXECUTE FUNCTION guard_directory_history();

CREATE TRIGGER ck_subscription_workspace_kind BEFORE INSERT OR UPDATE OF workspace_id ON subscription_period FOR EACH ROW EXECUTE FUNCTION enforce_directory_workspace_kind();

CREATE TRIGGER ck_teaching_assignment_preserve_history BEFORE DELETE OR UPDATE ON teaching_assignment FOR EACH ROW EXECUTE FUNCTION guard_teaching_assignment_history();

CREATE CONSTRAINT TRIGGER ck_user_has_personal_workspace AFTER INSERT ON user_account DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_user_personal_workspace();

CREATE CONSTRAINT TRIGGER ck_workspace_center_owner AFTER INSERT OR UPDATE ON workspace DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_center_owner();

CREATE TRIGGER ck_workspace_immutable_kind BEFORE UPDATE OF kind, owner_user_id ON workspace FOR EACH ROW EXECUTE FUNCTION enforce_directory_immutable_links();

CREATE TRIGGER delete_oidc_correlation_after_session_delete AFTER DELETE ON spring_session FOR EACH ROW EXECUTE FUNCTION sync_oidc_correlation_session();

CREATE TRIGGER move_oidc_correlation_after_session_id_change AFTER UPDATE OF session_id ON spring_session FOR EACH ROW EXECUTE FUNCTION sync_oidc_correlation_session();

CREATE TRIGGER tr_authz_account_scope AFTER INSERT ON user_account FOR EACH ROW EXECUTE FUNCTION create_authz_scope();

CREATE TRIGGER tr_authz_workspace_scope AFTER INSERT ON workspace FOR EACH ROW EXECUTE FUNCTION create_authz_scope();

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT audit_event_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT audit_event_operation_id_fkey FOREIGN KEY (operation_id) REFERENCES authorization_operation(id);

ALTER TABLE ONLY audit_event
    ADD CONSTRAINT audit_event_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY authorization_operation
    ADD CONSTRAINT authorization_operation_initiator_user_id_fkey FOREIGN KEY (initiator_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY authorization_operation
    ADD CONSTRAINT authorization_operation_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY authz_projection
    ADD CONSTRAINT authz_projection_partition_id_fkey FOREIGN KEY (partition_id) REFERENCES authz_partition(id);

ALTER TABLE ONLY center_membership
    ADD CONSTRAINT center_membership_user_id_fkey FOREIGN KEY (user_id) REFERENCES user_account(id);

ALTER TABLE ONLY center_membership
    ADD CONSTRAINT center_membership_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY center
    ADD CONSTRAINT center_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY center
    ADD CONSTRAINT center_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY class_student
    ADD CONSTRAINT class_student_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY class_student
    ADD CONSTRAINT class_student_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY course_subject
    ADD CONSTRAINT course_subject_subject_id_fkey FOREIGN KEY (subject_id) REFERENCES subject(id);

ALTER TABLE ONLY course_subject
    ADD CONSTRAINT course_subject_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY course
    ADD CONSTRAINT course_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY enrollment
    ADD CONSTRAINT enrollment_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY class_student
    ADD CONSTRAINT fk_class_student_school_class FOREIGN KEY (workspace_id, class_id) REFERENCES school_class(workspace_id, id);

ALTER TABLE ONLY correction_notice
    ADD CONSTRAINT fk_correction_notice_affected_version FOREIGN KEY (resource_id, affected_version_id) REFERENCES resource_version(resource_id, id);

ALTER TABLE ONLY correction_notice
    ADD CONSTRAINT fk_correction_notice_replacement_version FOREIGN KEY (resource_id, replacement_version_id) REFERENCES resource_version(resource_id, id);

ALTER TABLE ONLY course
    ADD CONSTRAINT fk_course_school_class FOREIGN KEY (workspace_id, class_id) REFERENCES school_class(workspace_id, id);

ALTER TABLE ONLY course_subject
    ADD CONSTRAINT fk_course_subject_course FOREIGN KEY (workspace_id, class_id, course_id) REFERENCES course(workspace_id, class_id, id);

ALTER TABLE ONLY enrollment
    ADD CONSTRAINT fk_enrollment_class_student FOREIGN KEY (workspace_id, class_student_id) REFERENCES class_student(workspace_id, id);

ALTER TABLE ONLY enrollment
    ADD CONSTRAINT fk_enrollment_course_subject FOREIGN KEY (workspace_id, course_subject_id) REFERENCES course_subject(workspace_id, id);

ALTER TABLE ONLY homeroom_assignment
    ADD CONSTRAINT fk_homeroom_assignment_school_class FOREIGN KEY (workspace_id, class_id) REFERENCES school_class(workspace_id, id);

ALTER TABLE ONLY library_item
    ADD CONSTRAINT fk_library_item_resource_version FOREIGN KEY (resource_id, version_id) REFERENCES resource_version(resource_id, id);

ALTER TABLE ONLY library_item
    ADD CONSTRAINT fk_library_item_share_grant FOREIGN KEY (share_grant_id) REFERENCES share_grant(id);

ALTER TABLE ONLY resource_dependency
    ADD CONSTRAINT fk_resource_dependency_derived_version FOREIGN KEY (derived_resource_id, derived_version_id) REFERENCES resource_version(resource_id, id);

ALTER TABLE ONLY resource_dependency
    ADD CONSTRAINT fk_resource_dependency_source_version FOREIGN KEY (source_resource_id, source_version_id) REFERENCES resource_version(resource_id, id);

ALTER TABLE ONLY sample_activity
    ADD CONSTRAINT fk_sample_activity_course_subject FOREIGN KEY (workspace_id, course_subject_id) REFERENCES course_subject(workspace_id, id);

ALTER TABLE ONLY sample_activity
    ADD CONSTRAINT fk_sample_activity_resource_version FOREIGN KEY (resource_id, version_id) REFERENCES resource_version(resource_id, id);

ALTER TABLE ONLY sample_activity
    ADD CONSTRAINT fk_sample_activity_subscription_period FOREIGN KEY (workspace_id, subscription_period_id) REFERENCES subscription_period(workspace_id, id);

ALTER TABLE ONLY sample_result
    ADD CONSTRAINT fk_sample_result_course_subject FOREIGN KEY (workspace_id, course_subject_id) REFERENCES course_subject(workspace_id, id);

ALTER TABLE ONLY sample_result
    ADD CONSTRAINT fk_sample_result_submission FOREIGN KEY (workspace_id, submission_id) REFERENCES sample_submission(workspace_id, id);

ALTER TABLE ONLY sample_submission
    ADD CONSTRAINT fk_sample_submission_activity FOREIGN KEY (workspace_id, activity_id) REFERENCES sample_activity(workspace_id, id);

ALTER TABLE ONLY share_grant
    ADD CONSTRAINT fk_share_grant_source_version FOREIGN KEY (source_resource_id, source_version_id) REFERENCES resource_version(resource_id, id);

ALTER TABLE ONLY teaching_assignment
    ADD CONSTRAINT fk_teaching_assignment_course_subject FOREIGN KEY (workspace_id, course_subject_id) REFERENCES course_subject(workspace_id, id);

ALTER TABLE ONLY workspace
    ADD CONSTRAINT fk_workspace_center FOREIGN KEY (center_id, id) REFERENCES center(id, workspace_id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY guardian_link
    ADD CONSTRAINT guardian_link_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES user_account(id);

ALTER TABLE ONLY guardian_link
    ADD CONSTRAINT guardian_link_parent_user_id_fkey FOREIGN KEY (parent_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY guardian_link
    ADD CONSTRAINT guardian_link_requested_by_fkey FOREIGN KEY (requested_by) REFERENCES user_account(id);

ALTER TABLE ONLY guardian_link
    ADD CONSTRAINT guardian_link_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY guardian_link
    ADD CONSTRAINT guardian_link_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY homeroom_assignment
    ADD CONSTRAINT homeroom_assignment_teacher_user_id_fkey FOREIGN KEY (teacher_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY homeroom_assignment
    ADD CONSTRAINT homeroom_assignment_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY invitation
    ADD CONSTRAINT invitation_target_user_id_fkey FOREIGN KEY (target_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY invitation
    ADD CONSTRAINT invitation_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY library_item
    ADD CONSTRAINT library_item_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY membership_index
    ADD CONSTRAINT membership_index_user_id_fkey FOREIGN KEY (user_id) REFERENCES user_account(id);

ALTER TABLE ONLY membership_index
    ADD CONSTRAINT membership_index_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY membership_role
    ADD CONSTRAINT membership_role_membership_id_fkey FOREIGN KEY (membership_id) REFERENCES center_membership(id);

ALTER TABLE ONLY notification_receipt
    ADD CONSTRAINT notification_receipt_notice_id_fkey FOREIGN KEY (notice_id) REFERENCES correction_notice(id);

ALTER TABLE ONLY notification_receipt
    ADD CONSTRAINT notification_receipt_recipient_workspace_id_fkey FOREIGN KEY (recipient_workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY recovery_mutation
    ADD CONSTRAINT recovery_mutation_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY recovery_mutation
    ADD CONSTRAINT recovery_mutation_request_id_fkey FOREIGN KEY (request_id) REFERENCES recovery_request(id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE ONLY recovery_request
    ADD CONSTRAINT recovery_request_requester_user_id_fkey FOREIGN KEY (requester_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY recovery_request
    ADD CONSTRAINT recovery_request_target_user_id_fkey FOREIGN KEY (target_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY recovery_request
    ADD CONSTRAINT recovery_request_verified_by_fkey FOREIGN KEY (verified_by) REFERENCES user_account(id);

ALTER TABLE ONLY recovery_request
    ADD CONSTRAINT recovery_request_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY resource_assignment
    ADD CONSTRAINT resource_assignment_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES resource(id);

ALTER TABLE ONLY resource_assignment
    ADD CONSTRAINT resource_assignment_subject_id_fkey FOREIGN KEY (subject_id) REFERENCES subject(id);

ALTER TABLE ONLY resource_assignment
    ADD CONSTRAINT resource_assignment_user_id_fkey FOREIGN KEY (user_id) REFERENCES user_account(id);

ALTER TABLE ONLY resource_assignment
    ADD CONSTRAINT resource_assignment_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY resource
    ADD CONSTRAINT resource_created_by_user_id_fkey FOREIGN KEY (created_by_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY resource_dependency
    ADD CONSTRAINT resource_dependency_share_grant_id_fkey FOREIGN KEY (share_grant_id) REFERENCES share_grant(id);

ALTER TABLE ONLY resource
    ADD CONSTRAINT resource_owner_workspace_id_fkey FOREIGN KEY (owner_workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY resource_version
    ADD CONSTRAINT resource_version_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES resource(id);

ALTER TABLE ONLY sample_action_mutation
    ADD CONSTRAINT sample_action_mutation_actor_user_id_fkey FOREIGN KEY (actor_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY sample_action_mutation
    ADD CONSTRAINT sample_action_mutation_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY sample_activity
    ADD CONSTRAINT sample_activity_assigned_by_fkey FOREIGN KEY (assigned_by) REFERENCES user_account(id);

ALTER TABLE ONLY sample_activity
    ADD CONSTRAINT sample_activity_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES resource(id);

ALTER TABLE ONLY sample_activity
    ADD CONSTRAINT sample_activity_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY sample_result
    ADD CONSTRAINT sample_result_graded_by_fkey FOREIGN KEY (graded_by) REFERENCES user_account(id);

ALTER TABLE ONLY sample_result
    ADD CONSTRAINT sample_result_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY sample_result
    ADD CONSTRAINT sample_result_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY sample_submission
    ADD CONSTRAINT sample_submission_student_user_id_fkey FOREIGN KEY (student_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY sample_submission
    ADD CONSTRAINT sample_submission_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY school_class
    ADD CONSTRAINT school_class_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY share_grant
    ADD CONSTRAINT share_grant_grantor_user_id_fkey FOREIGN KEY (grantor_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY share_grant
    ADD CONSTRAINT share_grant_recipient_workspace_id_fkey FOREIGN KEY (recipient_workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session(primary_id) ON DELETE CASCADE;

ALTER TABLE ONLY subscription_period
    ADD CONSTRAINT subscription_period_confirmed_by_fkey FOREIGN KEY (confirmed_by) REFERENCES user_account(id);

ALTER TABLE ONLY subscription_period
    ADD CONSTRAINT subscription_period_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY system_practice_attempt_sample
    ADD CONSTRAINT system_practice_attempt_sample_personal_workspace_id_fkey FOREIGN KEY (personal_workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY system_practice_attempt_sample
    ADD CONSTRAINT system_practice_attempt_sample_system_resource_id_fkey FOREIGN KEY (system_resource_id) REFERENCES resource(id);

ALTER TABLE ONLY system_practice_attempt_sample
    ADD CONSTRAINT system_practice_attempt_sample_user_id_fkey FOREIGN KEY (user_id) REFERENCES user_account(id);

ALTER TABLE ONLY system_practice_attempt_sample
    ADD CONSTRAINT system_practice_attempt_sample_version_id_fkey FOREIGN KEY (version_id) REFERENCES resource_version(id);

ALTER TABLE ONLY teaching_assignment
    ADD CONSTRAINT teaching_assignment_teacher_user_id_fkey FOREIGN KEY (teacher_user_id) REFERENCES user_account(id);

ALTER TABLE ONLY teaching_assignment
    ADD CONSTRAINT teaching_assignment_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES workspace(id);

ALTER TABLE ONLY workspace
    ADD CONSTRAINT workspace_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES user_account(id);

--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
--
-- PostgreSQL database dump complete
--

-- Danh mục môn học hệ thống gieo sẵn (trước đây ở migration của bảng subject).
INSERT INTO subject (id, code, name, state) VALUES
    (gen_random_uuid(), 'M1', 'Môn 1', 'ACTIVE'),
    (gen_random_uuid(), 'M2', 'Môn 2', 'ACTIVE'),
    (gen_random_uuid(), 'M3', 'Môn 3', 'ACTIVE');

-- Tra cứu resource theo chủ sở hữu và môn học (danh mục cá nhân/trung tâm lọc theo môn).
CREATE INDEX ix_resource_owner_subject ON resource (owner_workspace_id, subject_id);

-- Grants cho role runtime (giaokhoa_runtime): ứng dụng chạy bằng role này, role chạy
-- migration giữ quyền sở hữu đối tượng. Mọi lệnh grant đều gắn tường minh với schema
-- đích của lần migrate (placeholder ${flyway:defaultSchema} do Flyway thay trước khi
-- chạy: public với dòng hệ thống ở deployment chuẩn, schema của workspace với dòng
-- tenant, schema cô lập ở các môi trường kiểm thử) — không dùng current_schema() vì
-- Flyway không đổi schema hiện hành của phiên theo schema đích. flyway_schema_history
-- chỉ được đọc: runtime không có quyền ghi lịch sử migration.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'giaokhoa_runtime') THEN
        CREATE ROLE giaokhoa_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;

DO $$
DECLARE
    target_schema text := '${flyway:defaultSchema}';
BEGIN
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO giaokhoa_runtime', target_schema);
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO giaokhoa_runtime',
        target_schema);
    EXECUTE format(
        'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO giaokhoa_runtime',
        target_schema);
    IF to_regclass(format('%I.flyway_schema_history', target_schema)) IS NOT NULL THEN
        EXECUTE format(
            'REVOKE INSERT, UPDATE, DELETE ON %I.flyway_schema_history FROM giaokhoa_runtime',
            target_schema);
    END IF;
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES IN SCHEMA %I '
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO giaokhoa_runtime',
        target_schema);
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES IN SCHEMA %I '
        'GRANT USAGE, SELECT ON SEQUENCES TO giaokhoa_runtime',
        target_schema);
END
$$;
