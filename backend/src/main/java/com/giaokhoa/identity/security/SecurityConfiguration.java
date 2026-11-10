package com.giaokhoa.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OidcBackChannelLogoutHandler;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            OidcBackChannelLogoutHandler backChannelLogoutHandler,
            @Value("${giaokhoa.app-origin}") String appOrigin)
            throws Exception {
        var authorizationRequests =
                new DefaultOAuth2AuthorizationRequestResolver(
                        registrations, "/oauth2/authorization");
        authorizationRequests.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce());

        var rpLogout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        rpLogout.setPostLogoutRedirectUri(appOrigin.replaceAll("/+$", "") + "/");

        var inboundBackChannel =
                PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/logout/connect/back-channel/keycloak");

        http.authorizeHttpRequests(authorize ->
                        authorize
                                .requestMatchers(HttpMethod.GET, "/api/session")
                                .permitAll()
                                .requestMatchers(inboundBackChannel)
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(inboundBackChannel))
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
