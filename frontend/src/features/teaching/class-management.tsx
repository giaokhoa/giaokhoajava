// Điểm vào công khai của feature teaching cho quản lý lớp: type/parser/helper
// thuần nằm ở teaching-helpers.ts, danh sách chủ nhiệm ở
// homeroom-assignment-list.tsx, nội dung route lớp ở center-classes.tsx.
// File này chỉ re-export để giữ nguyên các đường import cũ.
export {
  ifMatchValue,
  nameBody,
  operationSucceeded,
  parseClasses,
  parseHomeroomAssignments,
  parsePage,
  parseRoster,
  parseSubjects,
  stateLabel,
  teachingErrorMessage,
  toggleSelection,
  type HomeroomAssignment,
  type RosterEntry,
  type Subject,
  type TeachingClass,
  type TeachingMutate,
} from "./teaching-helpers";
export { CenterClassesContent } from "./center-classes";
