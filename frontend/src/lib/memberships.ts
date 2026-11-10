import { isRecord, listItems } from "./api-client";

// Danh mục vai trò cố định của center; không có đường tạo vai trò tùy chỉnh.
export const CENTER_ROLES = ["OWNER", "MANAGER", "TEACHER", "STUDENT", "PARENT"] as const;
export type CenterRole = (typeof CENTER_ROLES)[number];

export type Membership = {
  id: string;
  user: { id: string; username: string; displayName?: string };
  roles: CenterRole[];
  state: string;
  version: number;
};

function isCenterRole(value: unknown): value is CenterRole {
  return typeof value === "string" && (CENTER_ROLES as readonly string[]).includes(value);
}

export function parseMemberships(data: unknown): Membership[] {
  const memberships: Membership[] = [];
  for (const item of listItems(data)) {
    if (!isRecord(item)
        || typeof item.id !== "string" || !item.id
        || !isRecord(item.user)
        || typeof item.user.id !== "string" || !item.user.id
        || typeof item.user.username !== "string" || !item.user.username
        || !Array.isArray(item.roles)
        || typeof item.state !== "string" || !item.state
        || typeof item.version !== "number" || !Number.isSafeInteger(item.version) || item.version < 0) {
      continue;
    }
    const roles = item.roles.filter(isCenterRole);
    // Vai trò ngoài danh mục cố định làm bản ghi không đáng tin; bỏ thay vì hiển thị sai quyền.
    if (roles.length !== item.roles.length) continue;
    memberships.push({
      id: item.id,
      user: {
        id: item.user.id,
        username: item.user.username,
        displayName: typeof item.user.displayName === "string" && item.user.displayName
          ? item.user.displayName
          : undefined,
      },
      roles,
      state: item.state,
      version: item.version,
    });
  }
  return memberships;
}

// Ẩn chức năng theo capability do backend trả về: vai trò của chính actor trong center này.
// Đây chỉ là hiển thị; backend vẫn xét lại mọi mutation và lỗi 403 được báo nguyên văn.
export function memberPermissions(
  memberships: readonly Membership[],
  userId: string | null,
): { canManage: boolean; canAssignManager: boolean } {
  const own = userId ? memberships.find((item) => item.user.id === userId) : undefined;
  const roles = own?.roles ?? [];
  return {
    canManage: roles.includes("OWNER") || roles.includes("MANAGER"),
    canAssignManager: roles.includes("OWNER"),
  };
}
