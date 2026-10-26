package com.example.damai.common.security;

import com.example.damai.common.config.SecurityConfig;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 只加载安全配置与探针接口，不连接MySQL，也不启动超时任务。 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SecurityBoundaryTest.TestConfig.class)
@WebAppConfiguration
class SecurityBoundaryTest {
    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import({SecurityConfig.class, CsrfController.class, ProbeController.class})
    static class TestConfig {}

    @RestController
    static class ProbeController {
        @GetMapping({"/public/events", "/user/orders", "/admin/events", "/events", "/orders"})
        public String read() { return "ok"; }
        @PostMapping({"/admin/events", "/public/events"})
        public String write() { return "ok"; }
    }

    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityFilter;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(securityFilter).build();
    }

    private MockHttpSession session(String role) {
        MockHttpSession session = new MockHttpSession();
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new LoginUser(1L, role.toLowerCase(), LoginUser.AccountType.valueOf(role)),
                        null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContext);
        return session;
    }

    private String csrf(MockHttpSession session) throws Exception {
        String json = mvc.perform(get("/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    @Test
    void anonymousCanBrowseButCannotReadPrivateLists() throws Exception {
        mvc.perform(get("/public/events")).andExpect(status().isOk());
        mvc.perform(get("/user/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void userAndAdminCannotCrossPrivateNamespaces() throws Exception {
        mvc.perform(get("/user/orders").session(session("USER"))).andExpect(status().isOk());
        mvc.perform(get("/admin/events").session(session("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/events").session(session("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/user/orders").session(session("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void oldMixedPathsRemainBlocked() throws Exception {
        mvc.perform(get("/events").session(session("USER"))).andExpect(status().isForbidden());
        mvc.perform(get("/orders").session(session("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void writesRequireBothCsrfAndCorrectRole() throws Exception {
        MockHttpSession admin = session("ADMIN");
        mvc.perform(post("/admin/events").session(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/events").session(admin).header("X-CSRF-TOKEN", csrf(admin)))
                .andExpect(status().isOk());
        MockHttpSession user = session("USER");
        mvc.perform(post("/admin/events").session(user).header("X-CSRF-TOKEN", csrf(user)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/public/events").session(admin).header("X-CSRF-TOKEN", csrf(admin)))
                .andExpect(status().isForbidden());
    }
}
