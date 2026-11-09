package com.giaokhoa.authorization.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.giaokhoa.authorization.projection.ProjectionWorker;
import com.giaokhoa.support.IntegrationEnvironment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Chứng minh tuần tự hóa xuyên instance trên TEST PostgreSQL chuyên dụng, không phải mutex trong
 * JVM.
 */
@SpringBootTest
class ResourceMutationCoordinatorIT extends IntegrationEnvironment {

    // Worker không thuộc phạm vi kiểm thử của class này (coordinator được gọi trực tiếp và
    // đồng bộ): mock để worker nền không tự xử lý các operation mà class lái trực tiếp —
    // trạng thái khẳng định phải do class điều khiển, không do lịch poll của worker quyết
    // định.
    @MockitoBean ProjectionWorker scheduledWorker;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void twoWorkerInstancesSerializeMutationsForTheSameResource() throws Exception {
        // Các đối tượng coordinator độc lập mô phỏng các application worker riêng biệt.
        ResourceMutationCoordinator firstWorker =
                new ResourceMutationCoordinator(jdbc, transactions);
        ResourceMutationCoordinator secondWorker =
                new ResourceMutationCoordinator(jdbc, transactions);
        String resource = "workspace:concurrent-resource-" + java.util.UUID.randomUUID();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(() ->
                            firstWorker.withResourceLock(
                                    resource,
                                    () -> {
                                        firstEntered.countDown();
                                        await(releaseFirst);
                                        return null;
                                    }));
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS));
            var second =
                    pool.submit(() -> {
                        secondStarted.countDown();
                        secondWorker.withResourceLock(
                                resource,
                                () -> {
                                    secondEntered.countDown();
                                    return null;
                                });
                    });
            try {
                assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                assertFalse(
                        secondEntered.await(250, TimeUnit.MILLISECONDS),
                        "Second worker entered before the first transaction released its lock");
            } finally {
                releaseFirst.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for lock release");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding resource lock", ex);
        }
    }
}
