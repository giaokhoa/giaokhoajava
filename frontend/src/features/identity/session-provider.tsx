"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { apiRequest, isRecord, setSessionIdentity } from "../../lib/api-client";

export type SessionUser = {
  id: string;
  username: string;
  email?: string;
  emailVerified: boolean;
  identityState: string;
};

export type SessionState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; user: SessionUser }
  | { status: "error"; message: string };

type SessionContextValue = {
  session: SessionState;
  refreshSession: () => Promise<void>;
  activeWorkspaceId: string | null;
  selectWorkspace: (workspaceId: string | null) => void;
};

const SessionContext = createContext<SessionContextValue | null>(null);

export function parseSessionView(data: unknown): SessionState {
  if (!isRecord(data)) {
    return { status: "error", message: "Phản hồi phiên không hợp lệ." };
  }
  if (data.authenticated !== true) {
    return { status: "anonymous" };
  }
  const user = data.user;
  if (
    !isRecord(user)
    || typeof user.id !== "string" || !user.id
    || typeof user.username !== "string" || !user.username
    || typeof user.identityState !== "string" || !user.identityState
  ) {
    return { status: "error", message: "Phản hồi phiên không hợp lệ." };
  }
  return {
    status: "authenticated",
    user: {
      id: user.id,
      username: user.username,
      email: typeof user.email === "string" && user.email ? user.email : undefined,
      emailVerified: user.emailVerified === true,
      identityState: user.identityState,
    },
  };
}

async function loadSessionState(): Promise<SessionState> {
  try {
    const result = await apiRequest<unknown>("/api/session");
    return result.kind === "data"
      ? parseSessionView(result.data)
      : { status: "error", message: "Phản hồi phiên không hợp lệ." };
  } catch (error) {
    return {
      status: "error",
      message: error instanceof Error ? error.message : "Không tải được trạng thái phiên.",
    };
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<SessionState>({ status: "loading" });
  const [activeWorkspaceId, setActiveWorkspaceId] = useState<string | null>(null);
  const userIdRef = useRef<string | null>(null);
  const refreshSequenceRef = useRef(0);

  // Trạng thái phiên chỉ sống trong bộ nhớ của request hiện tại, không lưu trữ ở
  // client. CSRF được api-client giữ trong bộ nhớ theo danh tính báo tại đây:
  // đổi người dùng hoặc mất phiên thì token của phiên trước bị bỏ ngay tại điểm
  // này, nên token luôn theo đúng phiên hiện hành như khi lấy mới từng lần.
  const applySession = useCallback((next: SessionState) => {
    const nextUserId = next.status === "authenticated" ? next.user.id : null;
    setSessionIdentity(nextUserId);
    if (nextUserId !== userIdRef.current) {
      // Đổi tài khoản hoặc mất phiên: bỏ lựa chọn workspace của phiên trước.
      userIdRef.current = nextUserId;
      setActiveWorkspaceId(null);
    }
    setSession(next);
  }, []);

  const refreshSession = useCallback(async () => {
    const sequence = ++refreshSequenceRef.current;
    const next = await loadSessionState();
    if (sequence === refreshSequenceRef.current) applySession(next);
  }, [applySession]);

  useEffect(() => {
    let ignore = false;
    const sequence = ++refreshSequenceRef.current;
    void loadSessionState().then((next) => {
      if (!ignore && sequence === refreshSequenceRef.current) applySession(next);
    });
    return () => {
      ignore = true;
    };
  }, [applySession]);

  const value = useMemo<SessionContextValue>(
    () => ({
      session,
      refreshSession,
      activeWorkspaceId,
      selectWorkspace: setActiveWorkspaceId,
    }),
    [session, refreshSession, activeWorkspaceId],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) {
    throw new Error("useSession phải được dùng bên trong SessionProvider.");
  }
  return value;
}
