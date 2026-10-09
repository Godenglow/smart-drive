package com.smartdrive.account.service;

import com.smartdrive.account.dto.RegisterRequest;
import com.smartdrive.account.entity.Account;
import com.smartdrive.account.entity.Role;
import com.smartdrive.account.mapper.AccountMapper;
import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 账户注册与查询 */
@Service
public class AccountService {

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AccountMapper accountMapper, PasswordEncoder passwordEncoder) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
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

    /** 按 userId 查账户：GET /api/users/me 的数据访问入口，授权（JWT）由 Day 5 接入 */
    public Optional<Account> findById(long userId) {
        return Optional.ofNullable(accountMapper.selectById(userId));
    }
}
