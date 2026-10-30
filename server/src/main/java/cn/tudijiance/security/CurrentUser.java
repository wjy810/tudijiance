package cn.tudijiance.security;

import org.springframework.security.oauth2.jwt.Jwt;

/** 从登录令牌里取当前用户。 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        Object uid = jwt.getClaim("uid");
        if (uid instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalStateException("登录令牌里没有用户编号");
    }
}
