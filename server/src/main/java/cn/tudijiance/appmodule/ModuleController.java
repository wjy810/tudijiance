package cn.tudijiance.appmodule;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.tudijiance.appmodule.ModuleService.LinkRequest;
import cn.tudijiance.security.CurrentUser;
import cn.tudijiance.security.Roles;

@RestController
@RequestMapping("/api/modules")
public class ModuleController {

    private final ModuleService modules;

    public ModuleController(ModuleService modules) {
        this.modules = modules;
    }

    public record EnabledRequest(Boolean enabled) {
    }

    /**
     * 登录了就能看：客户端按它显示菜单。系统管理员看到全部；
     * 其他人看到全部内置模块的开关，和给自己角色、开着的链接
     */
    @GetMapping
    public List<AppModule> list(@AuthenticationPrincipal Jwt jwt) {
        String role = CurrentUser.role(jwt);
        if (Roles.SYS_ADMIN.equals(role)) {
            return modules.list();
        }
        return modules.list().stream()
                .filter(m -> !AppModule.LINK.equals(m.getKind()) || (m.isOn() && ModuleService.visibleTo(m, role)))
                .toList();
    }

    @PutMapping("/{code}/enabled")
    @PreAuthorize(Roles.IS_SYS_ADMIN)
    public AppModule setEnabled(@AuthenticationPrincipal Jwt jwt, @PathVariable String code,
                                @RequestBody EnabledRequest req) {
        return modules.setEnabled(code, Boolean.TRUE.equals(req.enabled()), CurrentUser.id(jwt));
    }

    @PostMapping("/links")
    @PreAuthorize(Roles.IS_SYS_ADMIN)
    public AppModule createLink(@AuthenticationPrincipal Jwt jwt, @RequestBody LinkRequest req) {
        return modules.createLink(req, CurrentUser.id(jwt));
    }

    @PutMapping("/links/{code}")
    @PreAuthorize(Roles.IS_SYS_ADMIN)
    public AppModule updateLink(@AuthenticationPrincipal Jwt jwt, @PathVariable String code,
                                @RequestBody LinkRequest req) {
        return modules.updateLink(code, req, CurrentUser.id(jwt));
    }

    @DeleteMapping("/links/{code}")
    @PreAuthorize(Roles.IS_SYS_ADMIN)
    public void deleteLink(@PathVariable String code) {
        modules.deleteLink(code);
    }
}
