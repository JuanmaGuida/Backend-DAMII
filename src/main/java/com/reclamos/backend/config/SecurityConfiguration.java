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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

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
                        .requestMatchers(HttpMethod.POST, "/api/tickets").permitAll()
                        // La bandeja global y las acciones de triage son administrativas.
                        // AREA_RESPONSIBLE conserva lectura acotada en /api/staff/tickets/*,
                        // pero no obtiene acceso global a la bandeja M2.
                        .requestMatchers(HttpMethod.GET, "/api/tickets")
                        .hasAnyRole("AGENT", "ADMIN")
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
                        // POST /review, PATCH /classification, POST /route (Story 2.3 -
                        // Enforcement de permisos por rol en backend): son acciones de
                        // triage. Guía funcional M2 §7 dice que la capacidad staff de
                        // AREA_RESPONSIBLE se limita a "leer y enviar mensajes PUBLIC/
                        // INTERNAL" — no incluye tomar, reclasificar ni derivar tickets,
                        // ni siquiera los de su propia área. Por eso NO va en este
                        // hasAnyRole, a diferencia de la bandeja/staff-detail (sólo
                        // lectura). El bloqueo de "acción staff en ticket propio" (Entidades
                        // §3.3: "la vista staff queda completamente read-only, incluso para
                        // ADMIN") no se resuelve acá por rol — lo valida
                        // TicketService.requireTriageAuthority.
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/review").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/tickets/*/classification")
                        .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/route").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/information-request").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/information-response").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution/confirm").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution/reopen").authenticated()
                        // POST /tickets/{id}/cancel (Entidades V1.49 §24): "Ciudadano owner
                        // / propietario anónimo acreditado / AGENT / ADMIN". Igual que el
                        // resto de las acciones sobre un ticket puntual, alcanza con estar
                        // autenticado acá — el ownership (dueño) o el rol staff (AGENT/ADMIN,
                        // sin AREA_RESPONSIBLE) lo valida TicketService.requireCancelAuthority.
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/cancel").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tracking/access").permitAll()
                        // El simulador sólo existe cuando app.simulator.enabled=true y aun
                        // entonces requiere una sesión válida; nunca queda público por accidente.
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/simulate-status-update").authenticated()
                        // BE - DDA2-114/115/116 (US "Panel de administración del catálogo"):
                        // el panel admin de Categories/Subcategories/Request Types es
                        // exclusivo de ADMIN. La verificación exhaustiva de esta
                        // autorización (tests dedicados, casos de acceso denegado) es
                        // DDA2-139/140/141 — historia separada — pero dejar estos
                        // endpoints de escritura bajo el fallback authenticated() de
                        // abajo permitiría que cualquier CITIZEN autenticado modifique
                        // el catálogo, así que el gate mínimo se agrega ya acá.
                        .requestMatchers("/api/admin/catalog/**").hasRole("ADMIN")
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
