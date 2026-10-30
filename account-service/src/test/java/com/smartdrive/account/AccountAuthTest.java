package com.smartdrive.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.account.controller.AuthController;
import com.smartdrive.account.controller.UserController;
import com.smartdrive.account.dto.LoginRequest;
import com.smartdrive.account.dto.LoginResponse;
import com.smartdrive.account.entity.Account;
import com.smartdrive.account.mapper.AccountMapper;
import com.smartdrive.account.service.AccountService;
import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.auth.JwtCodec;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.common.api.web.GlobalExceptionHandler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AccountAuthTest {
    private final Instant now = Instant.parse("2026-10-30T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final JwtCodec codec = new JwtCodec("test-secret-must-be-longer-than-thirty-two-bytes", mapper,
            Clock.fixed(now, ZoneOffset.UTC));
    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private AccountService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(accountMapper);
        service = new AccountService(accountMapper, new BCryptPasswordEncoder(), codec);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service), new UserController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ServletAuthFilter(codec, mapper)).build();
    }

    @Test
    void loginIssuesSignedTokenAndMeReadsOnlyAuthenticatedAccount() throws Exception {
        Account passenger = account(11L, "13800138000", "PASSENGER");
        when(accountMapper.selectByPhone(passenger.getPhone())).thenReturn(passenger);
        when(accountMapper.selectById(11L)).thenReturn(passenger);
        LoginResponse login = service.login(passenger.getPhone(), "Passw0rd!23");
        assertEquals("PASSENGER", login.role());
        assertEquals(11L, codec.verify(login.accessToken()).userId());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(11))
                .andExpect(jsonPath("$.data.phone").value(passenger.getPhone()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void badPasswordIsRejected() {
        Account passenger = account(11L, "13800138000", "PASSENGER");
        when(accountMapper.selectByPhone(passenger.getPhone())).thenReturn(passenger);
        assertThrows(ApiException.class, () -> service.login(passenger.getPhone(), "wrong-password"));
    }

    @Test
    void loginEndpointValidatesInputAndRejectsWrongPassword() throws Exception {
        Account passenger = account(11L, "13800138000", "PASSENGER");
        when(accountMapper.selectByPhone(passenger.getPhone())).thenReturn(passenger);
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"phone\":\"123\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"phone\":\"13800138000\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void missingExpiredAndForgedTokensReturn401() throws Exception {
        String valid = codec.issue(11L, "PASSENGER", now.plusSeconds(3600));
        int signatureStart = valid.lastIndexOf('.') + 1;
        String forged = valid.substring(0, signatureStart) + (valid.charAt(signatureStart) == 'A' ? 'B' : 'A')
                + valid.substring(signatureStart + 1);
        String expired = codec.issue(11L, "PASSENGER", now.plusSeconds(1));
        JwtCodec later = new JwtCodec("test-secret-must-be-longer-than-thirty-two-bytes", mapper,
                Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> later.verify(expired));
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + valid + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passengerCannotUseDriverRouteAndDriverCan() throws Exception {
        String passenger = codec.issue(11L, "PASSENGER", now.plusSeconds(3600));
        String driver = codec.issue(12L, "DRIVER", now.plusSeconds(3600));
        mvc.perform(get("/api/drivers/me/notices").header("Authorization", "Bearer " + passenger))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertEquals("DRIVER", codec.verify(driver).role());
        mvc.perform(get("/api/drivers/me/notices").header("Authorization", "Bearer " + driver))
                .andExpect(status().isNotFound());
    }

    @Test
    void driverTokenCanReadItsOwnAccountButNotPassengerOrderCreation() throws Exception {
        Account driver = account(12L, "13900139000", "DRIVER");
        when(accountMapper.selectById(12L)).thenReturn(driver);
        String token = codec.issue(12L, "DRIVER", now.plusSeconds(3600));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("DRIVER"));
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private Account account(long id, String phone, String role) {
        Account account = new Account();
        account.setId(id);
        account.setPhone(phone);
        account.setRole(role);
        account.setPasswordHash(new BCryptPasswordEncoder().encode("Passw0rd!23"));
        return account;
    }
}
