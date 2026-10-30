package com.smartdrive.account.service;

import com.smartdrive.account.dto.RegisterRequest;
import com.smartdrive.account.entity.Account;
import com.smartdrive.account.entity.Role;
import com.smartdrive.account.mapper.AccountMapper;
import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.JwtCodec;
import com.smartdrive.account.dto.LoginResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 账户注册、登录与查询 */
@Service
public class AccountService {

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtCodec jwtCodec;

    public AccountService(AccountMapper accountMapper, PasswordEncoder passwordEncoder, JwtCodec jwtCodec) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtCodec = jwtCodec;
    }

    /**
     * 注册账户：手机号唯一（先查后插，并发撞唯一键时兜底转 409），密码只存 BCrypt 哈希。
     *
     * @return 新账户 userId
     */
    public long register(RegisterRequest request) {
        Role role = Role.parse(request.role());
        if (accountMapper.selectByPhone(request.phone()) != null) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_EXISTS);
        }

        Account account = new Account();
        account.setPhone(request.phone());
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        account.setRole(role.name());
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.PHONE_ALREADY_EXISTS);
        }
        return account.getId();
    }

    /** 按 userId 查账户：GET /api/users/me 的数据访问入口 */
    public Optional<Account> findById(long userId) {
        return Optional.ofNullable(accountMapper.selectById(userId));
    }

    public LoginResponse login(String phone, String password) {
        Account account = accountMapper.selectByPhone(phone);
        if (account == null || !passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "手机号或密码错误");
        }
        Instant expiresAt = Instant.now().plus(2, ChronoUnit.HOURS);
        return new LoginResponse(jwtCodec.issue(account.getId(), account.getRole(), expiresAt), expiresAt, account.getRole());
    }
}
