package com.smartdrive.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.account.controller.AuthController;
import com.smartdrive.account.controller.UserController;
import com.smartdrive.account.dto.RegisterRequest;
import com.smartdrive.account.mapper.AccountMapper;
import com.smartdrive.account.service.AccountService;
import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.JwtCodec;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.common.api.web.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Real account mapper and BCrypt; HTTP authorization uses the production servlet filter. */
@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_MYSQL_INTEGRATION", matches = "true")
class AccountMySqlIntegrationTest {
    @Test void persistedIdentityControlsLoginMeAndRoleAuthorization() throws Exception {
        try (var f = new Fixture()) {
            long id = f.service.register(new RegisterRequest(f.phone, f.password, "PASSENGER"));
            var account = f.mapper.selectById(id);
            assertNotEquals(f.password, account.getPasswordHash());
            assertTrue(f.encoder.matches(f.password, account.getPasswordHash()));
            assertEquals(ErrorCode.UNAUTHORIZED, assertThrows(ApiException.class,
                    () -> f.service.login(f.phone, "incorrect-password")).errorCode());
            String token = f.service.login(f.phone, f.password).accessToken();
            f.mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(id))
                    .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
            f.mvc.perform(put("/api/drivers/me/online").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            f.mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
            var past = new JwtCodec(Fixture.KEY, f.json,
                    Clock.fixed(Instant.now().minusSeconds(60), ZoneOffset.UTC));
            String expired = past.issue(id, "PASSENGER", Instant.now().minusSeconds(10));
            f.mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expired))
                    .andExpect(status().isUnauthorized());
            f.mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token + "x"))
                    .andExpect(status().isUnauthorized());
            // A correctly signed token cannot override the persisted account role.
            String wrongRole = f.jwt.issue(id, "DRIVER", Instant.now().plusSeconds(60));
            f.mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + wrongRole))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test void concurrentRegistrationStoresOneBcryptAccountAndRejectsAllDuplicates() throws Exception {
        try (var f = new Fixture(); var pool = Executors.newFixedThreadPool(8)) {
            var start = new CyclicBarrier(8);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) results.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                try {
                    f.service.register(new RegisterRequest(f.phone, f.password, "DRIVER"));
                    return true;
                } catch (ApiException ex) {
                    assertEquals(ErrorCode.PHONE_ALREADY_EXISTS, ex.errorCode());
                    return false;
                }
            }));
            int successes = 0;
            for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1L, f.jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE phone=?", Long.class, f.phone));
            var stored = f.mapper.selectByPhone(f.phone);
            assertTrue(f.encoder.matches(f.password, stored.getPasswordHash()));
            assertEquals("DRIVER", f.service.login(f.phone, f.password).role());
        }
    }

    static class Fixture implements AutoCloseable {
        static final String KEY = "step19-test-key-longer-than-thirty-two-bytes";
        // Not an API request: a UUID in the phone column isolates mapper-level tests from real accounts.
        final String phone = "it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        final String password = UUID.randomUUID().toString();
        final DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:mysql://localhost:3306/account_db?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                System.getenv().getOrDefault("SMARTDRIVE_DB_USER", "smartdrive"), System.getenv("SMARTDRIVE_DB_PASSWORD"));
        final JdbcTemplate jdbc = new JdbcTemplate(source);
        final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        final ObjectMapper json = new ObjectMapper();
        final JwtCodec jwt = new JwtCodec(KEY, json, Clock.systemUTC());
        final AccountMapper mapper;
        final AccountService service;
        final MockMvc mvc;

        Fixture() throws Exception {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(AccountMapper.class);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(config);
            mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AccountMapper.class);
            service = new AccountService(mapper, encoder, jwt);
            mvc = MockMvcBuilders.standaloneSetup(new AuthController(service), new UserController(service))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .addFilters(new ServletAuthFilter(jwt, json)).build();
        }

        @Override public void close() {
            jdbc.update("DELETE FROM account WHERE phone=?", phone);
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE phone=?", Long.class, phone));
        }
    }
}
