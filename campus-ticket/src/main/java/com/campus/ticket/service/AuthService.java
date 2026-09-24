package com.campus.ticket.service;

import com.campus.ticket.constants.RedisConstants;
import com.campus.ticket.dto.LoginRequest;
import com.campus.ticket.dto.LoginUser;
import com.campus.ticket.entity.CampusUser;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.UserMapper;
import com.campus.ticket.security.CredentialFingerprint;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    public String login(LoginRequest request){
        if(request == null
                || request.getStudentNo() == null
                || request.getStudentNo().isBlank()
                || request.getPassword() ==null
                || request.getPassword().isBlank()){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "学号和密码不能为空"
            );
        }

        String studentNo = request.getStudentNo().trim();
        String password = request.getPassword();

        if(studentNo.length()>32 || password.getBytes(StandardCharsets.UTF_8).length>72){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "学号或密码长度超出限制"
            );
        }
        CampusUser user = userMapper.findByStudentNo(studentNo);
        if (user == null
                || user.getPasswordHash() == null
                || user.getPasswordHash().isBlank()
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "LOGIN_FAILED",
                    "学号或密码错误"
            );
        }
        String token = UUID.randomUUID().toString().replace("-", "");

        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_KEY_PREFIX + token,
                user.getId() + ":" + CredentialFingerprint.of(user.getPasswordHash()),
                RedisConstants.LOGIN_TTL
        );

        return token;
    }

    public void logout(String token) {
        stringRedisTemplate.delete(
                RedisConstants.LOGIN_KEY_PREFIX + token
        );
    }

    public LoginUser getLoginUser(String token){
        if(token == null || !token.matches("[0-9a-f]{32}")){
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "登录凭证无效，请重新登录"
            );
        }
        String session = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_KEY_PREFIX + token);
        // Legacy ID-only tokens must be renewed; never bypass password-change revocation.
        if(session == null || !session.matches("[1-9][0-9]*:[0-9a-f]{64}")){
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "登录已失效，请重新登录"
            );
        }

        String[] parts = session.split(":", 2);
        Long userId;
        try {
            userId = Long.valueOf(parts[0]);
        } catch (NumberFormatException e) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录凭证无效，请重新登录");
        }
        CampusUser user = userMapper.findCredentialsById(userId);
        if (user == null || user.getPasswordHash() == null
                || !parts[1].equals(CredentialFingerprint.of(user.getPasswordHash()))) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "登录已失效，请重新登录"
            );
        }
        return new LoginUser(
                user.getId(),
                user.getStudentNo(),
                user.getName(),
                user.getRole()
        );
    }


}
