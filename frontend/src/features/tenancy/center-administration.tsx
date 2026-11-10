// Điểm vào công khai của feature tenancy: nội dung hai route nằm ở
// platform-centers.tsx và center-members.tsx; helper membership dùng chung ở
// lib/memberships. File này chỉ re-export để giữ nguyên các đường import cũ.
export {
  CENTER_ROLES,
  memberPermissions,
  parseMemberships,
  type CenterRole,
  type Membership,
} from "../../lib/memberships";
export {
  centerCreateBody,
  parseCenters,
  PlatformCentersContent,
  subscriptionConfirmBody,
  type CenterSummary,
} from "./platform-centers";
export {
  CenterMembersContent,
  INVITABLE_ROLES,
  invitationBody,
  membershipRolesBody,
  parseStudentIssue,
  studentIssueBody,
  type StudentHandoff,
} from "./center-members";
