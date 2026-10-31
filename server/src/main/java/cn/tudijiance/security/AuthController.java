package cn.tudijiance.security;

import java.time.Instant;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.user.User;
import cn.tudijiance.user.UserController.UserView;
import cn.tudijiance.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        User user = userService.findByUsername(req.username());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !userService.passwordMatches(user, req.password())) {
            throw ApiException.unauthorized("用户名或密码不正确");
        }
        JwtService.IssuedToken token = jwtService.issue(user, "MOBILE".equals(req.client()));
        return new LoginResponse(token.token(), token.expiresAt(), UserView.of(user));
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        return UserView.of(currentUser(jwt));
    }

    @PutMapping("/password")
    public void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
        User user = currentUser(jwt);
        if (!userService.passwordMatches(user, req.oldPassword())) {
            throw ApiException.badRequest("原密码不正确");
        }
        userService.setPassword(user.getId(), req.newPassword());
    }

    private User currentUser(Jwt jwt) {
        User user = userService.findByUsername(jwt.getSubject());
        if (user == null) {
            throw ApiException.unauthorized("用户不存在");
        }
        return user;
    }

    /** @param client 客户端类型：MOBILE 表示手机端（令牌有效期更长），不填表示电脑端 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String client) {
    }

    public record LoginResponse(String token, Instant expiresAt, UserView user) {
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword,
                                        @NotBlank @Size(min = 6, max = 64) String newPassword) {
    }
}
