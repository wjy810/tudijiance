package cn.tudijiance.user;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 用户管理，只有管理员能用。 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserView> list() {
        return userService.list().stream().map(UserView::of).toList();
    }

    @PostMapping
    public UserView create(@Valid @RequestBody CreateUserRequest req) {
        return UserView.of(userService.create(req.username(), req.displayName(), req.role(), req.password()));
    }

    @PutMapping("/{id}/password")
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest req) {
        userService.setPassword(id, req.password());
    }

    @PutMapping("/{id}/enabled")
    public void setEnabled(@PathVariable Long id, @Valid @RequestBody EnabledRequest req) {
        userService.setEnabled(id, req.enabled());
    }

    public record UserView(Long id, String username, String displayName, String role, Boolean enabled) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.getEnabled());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 64) String username,
            @Size(max = 64) String displayName,
            @NotBlank String role,
            @NotBlank @Size(min = 6, max = 64) String password) {
    }

    public record PasswordRequest(@NotBlank @Size(min = 6, max = 64) String password) {
    }

    public record EnabledRequest(@NotNull Boolean enabled) {
    }
}
