package com.campus.ticket.context;

import com.campus.ticket.dto.LoginUser;
import com.campus.ticket.exception.BusinessException;
import org.springframework.http.HttpStatus;

public final class UserHolder {

    private static final ThreadLocal<LoginUser> USER = new ThreadLocal<>();

    public static void requireAdmin() {
        // 未登录时，先抛出 401
        getUserId();

        LoginUser user = USER.get();

        if (!"ADMIN".equals(user.getRole())) {
            throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "FORBIDDEN",
                    "需要管理员权限"
            );
        }
    }

    private UserHolder() {
    }

    public static void saveUser(LoginUser user) {
        USER.set(user);
    }

    public static LoginUser getUser() {
        return USER.get();
    }

    public static Long getUserId() {
        LoginUser user = USER.get();

        if (user == null) {
            throw new BusinessException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "请先登录"
            );
        }

        return user.getId();
    }

    public static void removeUser() {
        USER.remove();
    }
}