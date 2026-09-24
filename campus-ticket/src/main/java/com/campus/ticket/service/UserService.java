package com.campus.ticket.service;

import com.campus.ticket.context.UserHolder;
import com.campus.ticket.dto.*;
import com.campus.ticket.entity.CampusUser;
import com.campus.ticket.exception.BusinessException;
import com.campus.ticket.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public Long register(RegisterRequest request) {
        if (request == null || request.getStudentNo() == null
                || !request.getStudentNo().trim().matches("[A-Za-z0-9_-]{1,32}")) {
            throw invalid("学号须为1到32位字母、数字、下划线或连字符");
        }
        String name = validateName(request.getName());
        validateNewPassword(request.getPassword());
        CampusUser user = new CampusUser();
        user.setStudentNo(request.getStudentNo().trim());
        user.setName(name);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole("STUDENT");
        try {
            userMapper.insertStudent(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "STUDENT_NO_EXISTS", "学号已注册", e);
        }
        return user.getId();
    }

    public LoginUser profile() {
        return toProfile(requireUser(userMapper.findById(UserHolder.getUserId())));
    }

    @Transactional
    public LoginUser updateProfile(UpdateProfileRequest request) {
        Long userId = UserHolder.getUserId();
        if (request == null) throw invalid("请填写个人资料");
        String name = validateName(request.getName());
        CampusUser user = requireUser(userMapper.findCredentialsByIdForUpdate(userId));
        userMapper.updateName(userId, name);
        user.setName(name);
        return toProfile(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Long userId = UserHolder.getUserId();
        if (request == null || request.getOldPassword() == null || request.getOldPassword().isBlank()
                || request.getOldPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid("请填写有效的原密码");
        }
        validateNewPassword(request.getNewPassword());
        CampusUser user = requireUser(userMapper.findCredentialsByIdForUpdate(userId));
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()
                || !passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "OLD_PASSWORD_INCORRECT", "原密码错误");
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw invalid("新密码不能与原密码相同");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(request.getNewPassword()));
        // Authentication compares the stored credential fingerprint on each request.
        // A committed password change invalidates all previous tokens without scanning Redis.
    }

    private CampusUser requireUser(CampusUser user) {
        if (user == null) throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "用户不存在，请重新登录");
        return user;
    }

    private LoginUser toProfile(CampusUser user) {
        return new LoginUser(user.getId(), user.getStudentNo(), user.getName(), user.getRole());
    }

    private String validateName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 50) {
            throw invalid("姓名不能为空，且最多50个字符");
        }
        return name.trim();
    }

    private void validateNewPassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalid("密码至少8个字符，UTF-8编码后最多72字节");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }
}
