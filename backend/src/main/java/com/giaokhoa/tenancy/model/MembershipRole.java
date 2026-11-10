package com.giaokhoa.tenancy.model;

/**
 * Hộp chứa enum role của membership. Bảng {@code membership_role} không còn entity riêng: nó là
 * element collection thuộc sở hữu của {@link CenterMembership}, vòng đời và version đi theo
 * membership sở hữu.
 */
public class MembershipRole {
    public enum Role {
        OWNER,
        MANAGER,
        TEACHER,
        STUDENT,
        PARENT
    }

    private MembershipRole() {}
}
