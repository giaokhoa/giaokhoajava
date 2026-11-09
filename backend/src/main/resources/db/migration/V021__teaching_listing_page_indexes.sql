-- Stable keyset listing indexes; historical revoked rows stay addressable through primary keys.
CREATE INDEX ix_class_listing_page ON school_class (workspace_id, created_at, id)
    WHERE state = 'ACTIVE';
CREATE INDEX ix_roster_listing_page ON class_student (workspace_id, class_id, created_at, id)
    WHERE state IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_course_listing_page ON course (workspace_id, class_id, created_at, id)
    WHERE state = 'ACTIVE';
CREATE INDEX ix_enrollment_listing_page ON enrollment (workspace_id, course_subject_id, created_at, id)
    WHERE state IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_teaching_assignment_listing_page
    ON teaching_assignment (workspace_id, course_subject_id, created_at, id)
    WHERE state IN ('PENDING', 'ACTIVE');
CREATE INDEX ix_homeroom_assignment_listing_page
    ON homeroom_assignment (workspace_id, class_id, created_at, id)
    WHERE state IN ('PENDING', 'ACTIVE');
