package com.giaokhoa.identity.security;

import com.giaokhoa.shared.config.GiaokhoaProperties;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OidcBackChannelLogoutHandler;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            OidcBackChannelLogoutHandler backChannelLogoutHandler,
            ProblemAuthenticationEntryPoint apiAuthenticationEntryPoint,
            ProblemAccessDeniedHandler problemAccessDeniedHandler,
            GiaokhoaProperties properties)
            throws Exception {
        var authorizationRequests =
                new DefaultOAuth2AuthorizationRequestResolver(
                        registrations, "/oauth2/authorization");
        authorizationRequests.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());

        var rpLogout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        rpLogout.setPostLogoutRedirectUri(properties.appOrigin().replaceAll("/+$", "") + "/");

        var inboundBackChannel =
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/logout/connect/back-channel/keycloak");
        var apiRequests = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        // Chỉ khách API khai báo nhận JSON mới là đối tượng của entry point 401: client
        // frontend luôn gửi Accept: application/json. Request ở ngữ cảnh trình duyệt gửi
        // Accept */* hoặc không gửi (MediaTypeRequestMatcher suy ra */* khi thiếu header)
        // nên */* phải bị bỏ qua, nếu không matcher này khớp cả luồng trình duyệt.
        var jsonMediaTypes =
                new MediaTypeRequestMatcher(
                        MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
        jsonMediaTypes.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        var jsonApiRequests = new AndRequestMatcher(apiRequests, jsonMediaTypes);
        // Default của entry point ủy thác phải khai tường minh là redirect đăng nhập.
        // Nếu chỉ đăng ký qua defaultAuthenticationEntryPointFor, entry point đăng ký
        // đầu tiên (chính là bản 401 này, vì oauth2Login đăng ký sau) sẽ thành default
        // cho mọi request không khớp matcher nào — request */* sẽ nhận 401 thay vì 302.
        var authenticationEntryPoint =
                DelegatingAuthenticationEntryPoint.builder()
                        .addEntryPointFor(apiAuthenticationEntryPoint, jsonApiRequests)
                        .defaultEntryPoint(
                                new LoginUrlAuthenticationEntryPoint(
                                        "/oauth2/authorization/keycloak"))
                        .build();

        http.authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers(HttpMethod.GET, "/api/session")
                                .permitAll()
                                .requestMatchers(HttpMethod.GET, "/actuator/health")
                                .permitAll()
                                .requestMatchers(inboundBackChannel)
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(inboundBackChannel))
                // Lỗi ở filter chain phải mang cùng định dạng ProblemDetail như lỗi trong MVC:
                // khách API (Accept JSON) chưa đăng nhập nhận 401 thay vì redirect về trang
                // đăng nhập, và 403 (CSRF) trả thân có code/traceId thay vì thân mặc định của
                // container. Request ở ngữ cảnh trình duyệt (mọi request còn lại) đi qua
                // default của entry point ủy thác: redirect 302 tới
                // /oauth2/authorization/keycloak như hành vi gốc.
                .exceptionHandling(exceptions ->
                        exceptions
                                .authenticationEntryPoint(authenticationEntryPoint)
                                .accessDeniedHandler(problemAccessDeniedHandler))
                // Chốt tường minh chống fixation ở đúng mặc định của Spring Security
                // (đổi session id khi đăng nhập) để nâng cấp framework không âm thầm đổi.
                .sessionManagement(
                        session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .oauth2Login(oauth2 ->
                        oauth2.authorizationEndpoint(endpoint ->
                                endpoint.authorizationRequestResolver(authorizationRequests)))
                .oidcLogout(oidc ->
                        oidc.backChannel(
                                channel -> channel.logoutHandler(backChannelLogoutHandler)))
                .logout(logout -> logout.logoutUrl("/api/logout").logoutSuccessHandler(rpLogout))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());
        return http.build();
    }
}
