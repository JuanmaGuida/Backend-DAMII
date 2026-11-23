package com.reclamos.backend.config;

import com.reclamos.backend.dto.error.ApiProblem;
import com.reclamos.backend.security.ApiContentSecurityPolicyHeaderWriter;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * M2 no tiene usuarios locales: la identidad viene del proveedor (M1 o el mock de DEV) y se
     * valida en {@link BearerTokenAuthenticationFilter}. Declarar este bean impide que Spring Boot
     * autoconfigure su usuario en memoria con una contraseña generada, que además imprime en el log
     * ("Using generated security password"). La cátedra aclaró que imprimir credenciales generadas
     * incumple el Eje D (DDA2-263, SEG-10). Con form login y HTTP Basic deshabilitados, nadie lo invoca.
     */
    @Bean
    UserDetailsService noLocalUsers() {
        return username -> {
            throw new UsernameNotFoundException("M2 no define usuarios locales");
        };
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
                // DDA2-262 (SEG-09). X-Content-Type-Options: nosniff, X-Frame-Options: DENY y Cache-Control
                // los aporta Spring Security por defecto; acá se agregan HSTS, Referrer-Policy y CSP.
                // CORS: no se habilita ningún origen cruzado (el front llega por el mismo origen vía proxy),
                // así que nunca hay Access-Control-Allow-Origin ni comodín con credenciales.
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31_536_000)
                                .includeSubDomains(true)
                                // El backend queda detrás de un proxy que termina TLS y le habla por HTTP; por
                                // defecto Spring sólo emite HSTS sobre requests seguros y nunca lo enviaría.
                                // Los navegadores ignoran HSTS recibido por HTTP, así que emitirlo siempre es inocuo.
                                .requestMatcher(request -> true))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new ApiContentSecurityPolicyHeaderWriter()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            ApiProblem.write(response, objectMapper, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                                    "La sesión no es válida", request.getRequestURI());
                        })
                        .accessDeniedHandler((request, response, exception) -> ApiProblem.write(
                                response, objectMapper, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                "No tiene permisos para realizar esta operación", request.getRequestURI())))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
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
                        .requestMatchers(HttpMethod.GET, "/api/attachments/*/content").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/indicators/**").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/api/staff/tickets/*/cancellation-requests", "/api/staff/tickets/*/cancellation-requests/**",
                                "/api/staff/tickets/*/cancellation-conflicts", "/api/staff/tickets/*/cancellation-conflicts/**")
                        .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/tickets/*/cancellation-requests").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/cancellation-requests").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tracking/actions/cancellation-requests",
                                "/api/tracking/actions/cancellation-requests/history").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/staff/labels/*/tickets").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/staff/labels", "/api/staff/labels/*")
                        .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/staff/labels").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/staff/labels/*").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/staff/labels/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/staff/tickets/*/labels").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/staff/tickets/*/labels/*").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/staff/tickets/*/labels").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/staff/tickets/*/labels/*").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers("/api/staff/tickets/*/reclassification-proposals/**",
                                "/api/staff/tickets/*/reclassification-proposals").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/staff/tickets/*/duplicate-candidates")
                        .hasAnyRole("AGENT", "ADMIN")
                        // GET /me/tickets (Entidades V1.49): listado propio del ciudadano.
                        // Cualquier rol conserva capacidades ciudadanas base, así que
                        // alcanza con estar autenticado — el scoping por citizenId lo
                        // hace TicketService.listMyTickets, no un rol puntual acá.
                        .requestMatchers(HttpMethod.GET, "/api/me/tickets").authenticated()
                        // Ayuda con IA: la clasificación es pública (el reclamo puede ser anónimo) y
                        // limitada por cliente; el análisis de un ticket es sólo para staff y el acceso
                        // real al ticket lo valida TicketService.requireStaffReadAccess.
                        .requestMatchers(HttpMethod.POST, "/api/ai/classify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/staff/tickets/*/ai-insights")
                        .hasAnyRole("AGENT", "AREA_RESPONSIBLE", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tickets").permitAll()
                        // La bandeja global y las acciones de triage son administrativas.
                        // AREA_RESPONSIBLE conserva lectura acotada en /api/staff/tickets/*,
                        // pero no obtiene acceso global a la bandeja M2.
                        .requestMatchers(HttpMethod.GET, "/api/tickets")
                        .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/staff/tickets/area").hasRole("AREA_RESPONSIBLE")
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
                        .requestMatchers(HttpMethod.GET, "/api/tickets/*/classification").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/tickets/*/classification")
                        .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/route").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/staff/tickets/*/duplicate").hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/information-request").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/information-response").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/attachments").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution/confirm").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/resolution/reopen").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/satisfaction-survey").authenticated()
                        // POST/GET /tickets/{id}/messages (Entidades V1.49 §10 + tabla de
                        // endpoints): chat de ticket. Igual que el resto de las acciones
                        // puntuales sobre un ticket, el filtro de entrada acá es sólo
                        // "autenticado" — la matriz real (owner→PUBLIC únicamente,
                        // AGENT/ADMIN cualquier ajeno, AREA_RESPONSIBLE sólo su areaId,
                        // ningún rol interno en su propio ticket) la valida
                        // TicketMessageService.requireWriteAuthority/requireReadAuthority.
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/messages").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/tickets/*/messages", "/api/tickets/*/messages/*").authenticated()
                        // PATCH/DELETE /tickets/{id}/messages/{messageId}: editar/borrar un
                        // mensaje propio. Acá también alcanza con "autenticado" — la
                        // restricción real (sólo el propio autor, y sólo sobre mensajes
                        // creados en M2, nunca los llegados por integración) la valida
                        // TicketMessageService.findOwnMessage, no este filtro grueso por rol.
                        .requestMatchers(HttpMethod.PATCH, "/api/tickets/*/messages/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/tickets/*/messages/*").authenticated()
                        // POST /tickets/{id}/cancel (Entidades V1.49 §24): "Ciudadano owner
                        // / propietario anónimo acreditado / AGENT / ADMIN". Igual que el
                        // resto de las acciones sobre un ticket puntual, alcanza con estar
                        // autenticado acá — el ownership (dueño) o el rol staff (AGENT/ADMIN,
                        // sin AREA_RESPONSIBLE) lo valida TicketService.requireCancelAuthority.
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/cancel").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/tracking/access").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/tracking/actions/cancel",
                                "/api/tracking/actions/information-response",
                                "/api/tracking/actions/confirm-resolution",
                                "/api/tracking/actions/reopen",
                                "/api/tracking/actions/attachments",
                                "/api/tracking/actions/attachments/*/content",
                                // POST /tracking/actions/messages: el propietario anónimo escribe
                                // en su propio chat sin JWT, acreditándose con trackingCode+
                                // password en el body — mismo patrón que el resto de
                                // /tracking/actions/*. La autorización real (ticket anónimo,
                                // sin citizenId, PUBLIC únicamente) la valida
                                // TicketMessageService.createAnonymous.
                                "/api/tracking/actions/messages").permitAll()
                        // El simulador sólo existe cuando app.simulator.enabled=true y aun
                        // entonces queda reservado al personal interno: un ciudadano no puede
                        // inyectar eventos de estado sobre tickets (propios ni ajenos).
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/simulate-status-update")
                        .hasAnyRole("AGENT", "ADMIN")
                        // BE - DDA2-114/115/116 (US "Panel de administración del catálogo"):
                        // el panel admin de Categories/Subcategories/Request Types es
                        // exclusivo de ADMIN. La verificación exhaustiva de esta
                        // autorización (tests dedicados, casos de acceso denegado) es
                        // DDA2-139/140/141 — historia separada — pero dejar estos
                        // endpoints de escritura bajo el fallback authenticated() de
                        // abajo permitiría que cualquier CITIZEN autenticado modifique
                        // el catálogo, así que el gate mínimo se agrega ya acá.
                        .requestMatchers("/api/admin/catalog/**").hasRole("ADMIN")
                        // Directorio de personal (panel "Agentes"): lista correos y carga de
                        // trabajo de todo el equipo, así que es exclusivo de ADMIN.
                        .requestMatchers(HttpMethod.GET, "/api/admin/agents").hasRole("ADMIN")
                        // MVC maps HEAD to GET handlers. Apply the same role gates before the fallback.
                        .requestMatchers(HttpMethod.HEAD, "/api/admin/agents").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.HEAD, "/api/staff/tickets/area").hasRole("AREA_RESPONSIBLE")
                        .requestMatchers(HttpMethod.HEAD, "/api/indicators/**", "/api/tickets",
                                "/api/tickets/*/classification", "/api/staff/labels", "/api/staff/labels/*",
                                "/api/staff/labels/*/tickets", "/api/staff/tickets/*/duplicate-candidates")
                        .hasAnyRole("AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.HEAD, "/api/staff/tickets/*", "/api/staff/tickets/*/citizen-view")
                        .hasAnyRole("AGENT", "AREA_RESPONSIBLE", "ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    FilterRegistrationBean<Filter> apiErrorDispatch(ObjectMapper objectMapper) {
        // Format container errors after Spring Security, before Boot's default error page.
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
            var httpRequest = (HttpServletRequest) request;
            var httpResponse = (HttpServletResponse) response;
            Object originalPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
            Object originalStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            if (httpResponse.isCommitted() || !(originalPath instanceof String path)
                    || !path.startsWith(httpRequest.getContextPath() + "/api/")
                    || !(originalStatus instanceof Integer value) || value < 400 || value >= 600) {
                chain.doFilter(request, response);
                return;
            }

            HttpStatusCode status = HttpStatusCode.valueOf(value);
            HttpStatus knownStatus = HttpStatus.resolve(value);
            String code = knownStatus == null ? "HTTP_ERROR" : knownStatus.name();
            String detail = status.is5xxServerError()
                    ? "No fue posible completar la operación."
                    : "No fue posible procesar la solicitud.";
            ApiProblem.write(httpResponse, objectMapper, status, code, detail, path);
        });
        registration.setDispatcherTypes(DispatcherType.ERROR);
        return registration;
    }
}
