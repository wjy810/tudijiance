package cn.tudijiance.security;

import java.util.List;

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

    /** 当前用户的角色，例如 TEACHER */
    public static String role(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.isEmpty()) {
            throw new IllegalStateException("登录令牌里没有角色");
        }
        return roles.getFirst();
    }
}
