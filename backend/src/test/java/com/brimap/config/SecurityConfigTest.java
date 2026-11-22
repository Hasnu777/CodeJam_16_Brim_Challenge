package com.brimap.config;

import com.brimap.audit.AuditService;
import com.brimap.auth.CurrentUser;
import com.brimap.auth.UserController;
import com.brimap.common.ApiExceptionHandler;
import com.brimap.model.Role;
import com.brimap.repository.AppUserRepository;
import com.brimap.repository.DelegationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Layer 1 of role enforcement (the filter chain), proven on a real controller: an anonymous caller is refused
 * with 401 BEFORE the request body is parsed or validated, and an Auditor's write is refused with 403.
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class SecurityConfigTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuditService audit;
    @MockitoBean AppUserRepository users;
    @MockitoBean DelegationRepository delegations;
    @MockitoBean AppClock clock;

    private static UsernamePasswordAuthenticationToken as(String id, Role role) {
        return new UsernamePasswordAuthenticationToken(new CurrentUser(id, id, role, "D04", BigDecimal.ZERO), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    @Test
    void anonymousWriteIsRefusedWith401BeforeTheBodyIsValidated() throws Exception {
        // An empty body would fail bean validation (400) if the request ever reached the controller.
        mvc.perform(post("/api/delegations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value("Not logged in. POST /api/auth/login with a userId first."));
        verify(audit).recordDenial(any(), anyString(), anyString(), anyString());
    }

    @Test
    void anonymousReadIsRefusedWith401() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void auditorWriteIsRefusedWith403() throws Exception {
        mvc.perform(post("/api/delegations").with(authentication(as("U006", Role.AUD)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("Auditors have read-only access; POST is not allowed"));
    }

    @Test
    void clerkCannotReadTheUserList() throws Exception {
        mvc.perform(get("/api/users").with(authentication(as("U004", Role.CLK)))).andExpect(status().isForbidden());
    }

    @Test
    void loginStaysPublic() throws Exception {
        // Reaches the controller: an empty userId fails validation (400), proving the route is not blocked.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
