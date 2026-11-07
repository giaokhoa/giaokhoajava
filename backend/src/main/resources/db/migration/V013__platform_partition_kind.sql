-- Grant nền tảng toàn cục giữ desired state trên một partition riêng; các kind cũ giữ nguyên.
ALTER TABLE authz_partition DROP CONSTRAINT authz_partition_kind_check;
ALTER TABLE authz_partition ADD CONSTRAINT authz_partition_kind_check
    CHECK (kind IN ('WORKSPACE', 'GRANT', 'ACCOUNT', 'PLATFORM'));
