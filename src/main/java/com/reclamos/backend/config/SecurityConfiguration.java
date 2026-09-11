package com.reclamos.backend.config;

import com.reclamos.backend.dto.error.ApiErrorResponse;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfiguration {
    private static final ApiErrorResponse UNAUTHORIZED_RESPONSE =
            new ApiErrorResponse("INVALID_TOKEN", "La sesión no es válida");
    private static final ApiErrorResponse FORBIDDEN_RESPONSE =
            new ApiErrorResponse("FORBIDDEN", "No tiene permisos para realizar esta operación");

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
            ObjectMapper objectMapper
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHORIZED_RESPONSE))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, objectMapper, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN_RESPONSE)))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/openapi.yaml").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/catalog/categories",
                                "/api/catalog/categories/{categoryId}/subcategories",
                                "/api/catalog/neighborhoods",
                                "/api/catalog/neighborhoods/{neighborhoodId}",
                                "/api/catalog/subcategories/{subcategoryId}/request-types",
                                "/api/catalog/request-types/{requestTypeId}/form").permitAll()
                        .requestMatchers(HttpMethod.HEAD,
                                "/api/catalog/categories",
                                "/api/catalog/categories/{categoryId}/subcategories",
                                "/api/catalog/neighborhoods",
                                "/api/catalog/neighborhoods/{neighborhoodId}",
                                "/api/catalog/subcategories/{subcategoryId}/request-types",
                                "/api/catalog/request-types/{requestTypeId}/form").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        // GET /me/tickets (Entidades V1.49): listado propio del ciudadano.
                        // Cualquier rol conserva capacidades ciudadanas base, así que
                        // alcanza con estar autenticado — el scoping por citizenId lo
                        // hace TicketService.listMyTickets, no un rol puntual acá.
                        .requestMatchers(HttpMethod.GET, "/api/me/tickets").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets").authenticated()
                        // BE - Story 3.1: la bandeja de triage es para quienes gestionan
                        // tickets del lado staff (Guía funcional M2 §7: AGENT, AREA_RESPONSIBLE
                        // y ADMIN conservan acceso staff a tickets ajenos). CITIZEN sólo tiene
                        // capacidades ciudadanas sobre sus propios tickets y queda afuera.
                        // Pendiente (no lo cubre este cambio): la Guía también dice que
                        // AREA_RESPONSIBLE sólo accede a tickets de su propia areaId — acá
                        // sólo se resuelve el "quién puede entrar", no el "qué ve una vez
                        // adentro" por área.
                        .requestMatchers(HttpMethod.GET, "/api/tickets")
                        .hasAnyRole("AGENT", "AREA_RESPONSIBLE", "ADMIN")
                        // GET /tickets/{id} (Entidades V1.49): detalle ciudadano, "Ciudadano
                        // owner". No hay rol puntual que filtrar acá — cualquier autenticado
                        // conserva capacidades ciudadanas base — el ownership real (sólo el
                        // dueño del ticket) lo valida TicketService.requireOwner (403 si no
                        // coincide el citizenId).
                        .requestMatchers(HttpMethod.GET, "/api/tickets/*").authenticated()
                        // GET /staff/tickets/{id} (Guía funcional M2 §7): detalle staff,
                        // mismos roles que la bandeja (AGENT/AREA_RESPONSIBLE/ADMIN). El
                        // scoping por areaId de AREA_RESPONSIBLE (y la excepción de ticket
                        // propio) lo valida TicketService.requireStaffAccess, no acá.
                        .requestMatchers(HttpMethod.GET, "/api/staff/tickets/*")
                        .hasAnyRole("AGENT", "AREA_RESPONSIBLE", "ADMIN")
                        // GET /staff/tickets/{id}/citizen-view (Guía funcional M2 §7.1):
                        // mismo filtro de rol de entrada que el detalle staff — el
                        // ownership/areaId real lo valida el mismo
                        // TicketService.requireStaffAccess (getStaffCitizenView delega en
                        // getStaffDetail).
                        .requestMatchers(HttpMethod.GET, "/api/staff/tickets/*/citizen-view")
                        .hasAnyRole("AGENT", "AREA_RESPONSIBLE", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/review").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/tickets/*/classification").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/route").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/information-request").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/information-response").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution/confirm").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution/reopen").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tracking/access").permitAll()
                        // BE - Story 3.4/DDA2-61: el simulador de updateTicketStatus simula
                        // una llamada de un sistema externo (llegaría por bus de eventos, no
                        // HTTP con bearer token de agente) y sólo existe como bean cuando
                        // app.simulator.enabled=true (ver TicketSimulationController).
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/simulate-status-update").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, int status,
                                   ApiErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
