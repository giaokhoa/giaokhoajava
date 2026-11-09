package com.giaokhoa.support;

import com.giaokhoa.authorization.policy.PolicyGraph;
import com.giaokhoa.authorization.policy.PolicyGraphWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Chèn lỗi có kịch bản tại biên Keycloak của {@link
 * com.giaokhoa.authorization.projection.ProjectionReconciler} — chỉ dùng trong test, không bao giờ
 * vào code production. Mỗi lần ghi lỗi đều mô phỏng một điểm crash thật trong hợp đồng mục 6: lỗi
 * giữa directory và graph, lỗi giữa graph và commit revision, hoặc Keycloak sập hẳn. Lỗi được đếm
 * đúng số lần đã hẹn rồi tự hết, để lần retry sau đó chạy trên cùng desired state bền vững và chứng
 * minh retry hội tụ thay vì nhân bản grant.
 */
public final class ProjectionFaultInjection {
    private final AtomicInteger remainingApplyFailures = new AtomicInteger();
    private final AtomicInteger remainingDirectorySetFailures = new AtomicInteger();
    private final List<PolicyGraph> appliedGraphList =
            Collections.synchronizedList(new ArrayList<>());
    private final List<String> retractedRootList = Collections.synchronizedList(new ArrayList<>());

    /** N lần gọi {@code writer.apply} tiếp theo ném lỗi 502, mô phỏng Keycloak sập giữa chừng. */
    public void failNextApplies(int times) {
        remainingApplyFailures.set(times);
    }

    /**
     * N lần ghi membership directory tiếp theo ném lỗi 502, mô phỏng crash sau khi graph đã áp mà
     * trước khi commit revision.
     */
    public void failNextDirectorySets(int times) {
        remainingDirectorySetFailures.set(times);
    }

    /** Hết mọi kịch bản lỗi đang hẹn; các lần gọi sau đó chạy bình thường. */
    public void clear() {
        remainingApplyFailures.set(0);
        remainingDirectorySetFailures.set(0);
    }

    /**
     * Mọi graph mà {@code writer.apply} đã được gọi với, theo đúng thứ tự gọi — gồm cả lần gọi đã
     * ném lỗi, dùng để chứng minh retry áp lại đúng graph.
     */
    public List<PolicyGraph> appliedGraphs() {
        return List.copyOf(appliedGraphList);
    }

    /** Mọi root permission đã thu hồi, dạng {@code targetKey|scope}, theo đúng thứ tự gọi. */
    public List<String> retractedRoots() {
        return List.copyOf(retractedRootList);
    }

    /**
     * Answer cho {@code writer.apply}: đếm và ghi lại graph TRƯỚC khi ném lỗi theo kịch bản, để lần
     * gọi đã crash vẫn để lại bằng chứng graph nó định áp; còn không thì trả về cùng resource id
     * xác định từ target key như stub mặc định của test.
     */
    public Answer<PolicyGraphWriter.AppliedGraph> applyAnswer() {
        return call -> {
            PolicyGraph graph = call.getArgument(0, PolicyGraph.class);
            appliedGraphList.add(graph);
            if (remainingApplyFailures.getAndDecrement() > 0) {
                throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
            }
            return new PolicyGraphWriter.AppliedGraph(
                    "resource:" + graph.targetKey(), List.of("owner"));
        };
    }

    /** Answer cho {@code writer.retractRoot}: đếm và ghi lại target/scope, không ném lỗi. */
    public Answer<Void> retractAnswer() {
        return call -> {
            retractedRootList.add(
                    call.getArgument(0, String.class) + "|" + call.getArgument(1, String.class));
            return null;
        };
    }

    /**
     * Answer cho {@code directory.setRealmGroupMembership}: ném lỗi khi còn trong kịch bản, còn
     * không thì ghi đúng vào map membership mà test đang theo dõi.
     */
    public Answer<Void> directorySetAnswer(Map<String, Boolean> memberships) {
        return call -> {
            if (remainingDirectorySetFailures.getAndDecrement() > 0) {
                throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
            }
            memberships.put(call.getArgument(0, String.class), call.getArgument(2, Boolean.class));
            return null;
        };
    }
}
