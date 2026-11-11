package com.giaokhoa.support;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client HTTP thật của riêng tầng e2e-backend (B107): bind vào server nhúng đang chạy trên cổng
 * ngẫu nhiên của {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} bằng {@link RestTestClient}
 * — client fluent của Spring Framework 7 cho stack servlet. Khác MockMvc ở tầng it-pure, request ở
 * đây đi qua socket, servlet container và filter chain thật.
 *
 * <p>Client không tự giữ cookie và không có post-processor mô phỏng: Cookie {@code SESSION} của
 * phiên JDBC thật được mang tường minh theo từng request, token CSRF lấy qua {@code GET
 * /api/session} — đúng hợp đồng frontend production dùng — và cache theo từng phiên trong vòng đời
 * của một test class instance. Redirect không bao giờ tự đi theo: các khẳng định 3xx đọc header
 * {@code Location} của phản hồi gốc.
 */
public final class HttpTestClient {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SESSION_COOKIE = "SESSION";

    /**
     * Phản hồi đã đọc trọn: status, thân dạng chuỗi và headers (giữ kín, chỉ tra cứu qua {@link
     * #header(String)} và {@link #cookieValue(String)}) — đúng phần các khẳng định của suite tiêu
     * thụ từ {@code MockHttpServletResponse} trước đây.
     */
    public static final class Response {
        private final int statusCode;
        private final String content;
        private final HttpHeaders headers;

        Response(int status, String body, @Nullable HttpHeaders headers) {
            this.statusCode = status;
            this.content = body;
            HttpHeaders copy = new HttpHeaders();
            if (headers != null) {
                copy.putAll(headers);
            }
            this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
        }

        public int status() {
            return statusCode;
        }

        public String body() {
            return content;
        }

        public @Nullable String header(String name) {
            return headers.getFirst(name);
        }

        /** Giá trị một cookie trong các header {@code Set-Cookie} của phản hồi, nếu có. */
        public @Nullable String cookieValue(String name) {
            for (String setCookie :
                    headers.getOrDefault(HttpHeaders.SET_COOKIE, List.<String>of())) {
                String pair = setCookie.split(";", 2)[0];
                int equals = pair.indexOf('=');
                if (equals > 0 && pair.substring(0, equals).trim().equals(name)) {
                    return pair.substring(equals + 1).trim();
                }
            }
            return null;
        }
    }

    /** Token CSRF của một phiên (hoặc của phiên ẩn danh vừa được cấp khi lấy token). */
    private record Csrf(String headerName, String token, @Nullable String sessionId) {}

    private final RestTestClient client;
    private final Map<String, Csrf> csrfBySession = new ConcurrentHashMap<>();

    private HttpTestClient(RestTestClient client) {
        this.client = client;
    }

    /**
     * Bind vào server thật của context hiện hành. Chỉ hợp lệ khi context chạy {@code RANDOM_PORT}:
     * cổng lấy từ {@code local.server.port} của Environment như mẫu {@code @LocalServerPort}.
     */
    public static HttpTestClient bind(WebApplicationContext context) {
        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException(
                    "HttpTestClient chỉ dùng với @SpringBootTest(webEnvironment = RANDOM_PORT)");
        }
        // Factory mặc định tự giữ HttpClient của JDK (chính sách redirect mặc định NEVER —
        // đúng điều các khẳng định 3xx cần); chỉ đặt timeout đọc cho các lượt poll dài.
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(120));
        RestTestClient restClient =
                RestTestClient.bindToServer(factory).baseUrl("http://127.0.0.1:" + port).build();
        return new HttpTestClient(restClient);
    }

    /** GET qua HTTP thật; request đọc không cần CSRF. */
    public Response get(String uri, @Nullable Cookie session) {
        return get(uri, session, Map.of());
    }

    public Response get(String uri, @Nullable Cookie session, Map<String, String> headers) {
        return exchange(HttpMethod.GET, uri, session, false, null, headers);
    }

    /**
     * Một request qua HTTP thật. {@code csrf} = true thì token CSRF của đúng phiên (hoặc phiên ẩn
     * danh mới) được lấy qua {@code GET /api/session} và gửi kèm; thân khác null được gửi với
     * Content-Type JSON trừ khi {@code headers} đã khai {@code Content-Type} riêng (ví dụ form).
     */
    public Response exchange(
            HttpMethod method,
            String uri,
            @Nullable Cookie session,
            boolean csrf,
            @Nullable String body,
            Map<String, String> headers) {
        Cookie effectiveSession = session;
        Csrf token = null;
        if (csrf) {
            token = csrfFor(session);
            if (effectiveSession == null && token.sessionId() != null) {
                effectiveSession = new Cookie(SESSION_COOKIE, token.sessionId());
            }
        }
        RestTestClient.RequestBodySpec spec = client.method(method).uri(uri);
        if (effectiveSession != null) {
            spec = spec.cookie(effectiveSession.getName(), effectiveSession.getValue());
        }
        if (token != null) {
            spec = spec.header(token.headerName(), token.token());
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(header.getKey())) {
                spec = spec.header(header.getKey(), header.getValue());
            }
        }
        String contentType = headers.get(HttpHeaders.CONTENT_TYPE);
        if (contentType != null) {
            spec = spec.contentType(MediaType.parseMediaType(contentType));
        } else if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON);
        }
        RestTestClient.ResponseSpec exchanged =
                body == null ? spec.exchange() : spec.body(body).exchange();
        var result = exchanged.returnResult(String.class);
        return new Response(
                result.getStatus().value(),
                result.getResponseBody() == null ? "" : result.getResponseBody(),
                result.getResponseHeaders());
    }

    /**
     * Bản HTTP của {@code requestWhenReady} ở {@link IntegrationEnvironment}: thử lại đúng khi
     * fence chưa sẵn sàng (503 {@code AUTHORIZATION_NOT_READY}), trả phản hồi đầu tiên ngoài fence.
     */
    public Response whenReady(Supplier<Response> call) {
        AtomicReference<Response> last = new AtomicReference<>();
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> {
                    Response response = call.get();
                    last.set(response);
                    return response.status() != 503
                            || !response.body().contains("AUTHORIZATION_NOT_READY");
                });
        return Objects.requireNonNull(last.get());
    }

    /**
     * Token CSRF hiện hành của phiên: đọc từ {@code GET /api/session} (endpoint permitAll công bố
     * {@code csrf.headerName}/{@code csrf.token} cho client thật). Với khách ẩn danh, lời gọi này
     * cũng cấp một phiên mới — id phiên đó được giữ lại để request đột biến đi cùng đúng phiên đã
     * sinh token.
     */
    private Csrf csrfFor(@Nullable Cookie session) {
        String key = session == null ? "" : session.getValue();
        return csrfBySession.computeIfAbsent(
                key,
                _ -> {
                    Response view = get("/api/session", session);
                    if (view.status() != HttpStatus.OK.value()) {
                        throw new IllegalStateException(
                                "GET /api/session must return the CSRF view, got " + view.status());
                    }
                    JsonNode csrf = JSON.readTree(view.body()).get("csrf");
                    return new Csrf(
                            Objects.requireNonNull(csrf.get("headerName")).stringValue(),
                            Objects.requireNonNull(csrf.get("token")).stringValue(),
                            view.cookieValue(SESSION_COOKIE));
                });
    }
}
