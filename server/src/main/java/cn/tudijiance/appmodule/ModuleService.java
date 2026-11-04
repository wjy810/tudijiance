package cn.tudijiance.appmodule;

import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.security.Roles;

/**
 * 功能模块的开关。模块只有十几个，全部放在内存里，改的时候刷新；
 * 每个请求由 ModuleGuard 按路径查一下对应模块开没开。
 */
@Service
public class ModuleService {

    private static final Set<String> LINK_ROLES = Set.of(Roles.SYS_ADMIN, Roles.TEACHER, Roles.STUDENT, Roles.GUEST);

    private final AppModuleMapper mapper;
    private final Clock clock;
    private volatile Map<String, AppModule> cache;

    public ModuleService(AppModuleMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public record LinkRequest(String name, String url, String description, List<String> roles, Boolean enabled) {
    }

    private Map<String, AppModule> modules() {
        Map<String, AppModule> c = cache;
        if (c == null) {
            c = mapper.selectList(null).stream()
                    .sorted(Comparator.comparing(AppModule::getSortOrder).thenComparing(AppModule::getCode))
                    .collect(Collectors.toMap(AppModule::getCode, m -> m, (a, b) -> a, LinkedHashMap::new));
            cache = c;
        }
        return c;
    }

    private void refresh() {
        cache = null;
    }

    public List<AppModule> list() {
        return List.copyOf(modules().values());
    }

    /** 没登记的模块（例如以后新加的功能还没写迁移）当作开着 */
    public boolean isOn(String code) {
        AppModule m = modules().get(code);
        return m == null || m.isOn();
    }

    public String nameOf(String code) {
        AppModule m = modules().get(code);
        return m == null ? code : m.getName();
    }

    private AppModule get(String code) {
        AppModule m = mapper.selectById(code);
        if (m == null) {
            throw ApiException.notFound("功能模块");
        }
        return m;
    }

    public AppModule setEnabled(String code, boolean enabled, long userId) {
        AppModule m = get(code);
        if (!enabled && Boolean.TRUE.equals(m.getRequired())) {
            throw ApiException.badRequest("“" + m.getName() + "”是系统必需的功能，不能停用");
        }
        m.setEnabled(enabled);
        m.setUpdatedBy(userId);
        m.setUpdatedAt(AppModule.now(clock));
        mapper.updateById(m);
        refresh();
        return m;
    }

    public AppModule createLink(LinkRequest req, long userId) {
        AppModule m = new AppModule();
        m.setCode("link_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        m.setKind(AppModule.LINK);
        m.setRequired(false);
        m.setEnabled(req.enabled() == null || req.enabled());
        int max = modules().values().stream().filter(x -> AppModule.LINK.equals(x.getKind()))
                .mapToInt(AppModule::getSortOrder).max().orElse(1000);
        m.setSortOrder(Math.max(max, 1000) + 10);
        applyLink(m, req, userId);
        mapper.insert(m);
        refresh();
        return m;
    }

    public AppModule updateLink(String code, LinkRequest req, long userId) {
        AppModule m = get(code);
        if (!AppModule.LINK.equals(m.getKind())) {
            throw ApiException.badRequest("内置模块只能启用或停用");
        }
        if (req.enabled() != null) {
            m.setEnabled(req.enabled());
        }
        applyLink(m, req, userId);
        mapper.updateById(m);
        refresh();
        return m;
    }

    public void deleteLink(String code) {
        AppModule m = get(code);
        if (!AppModule.LINK.equals(m.getKind())) {
            throw ApiException.badRequest("内置模块不能删除，可以停用");
        }
        mapper.deleteById(code);
        refresh();
    }

    private void applyLink(AppModule m, LinkRequest req, long userId) {
        String name = req.name() == null ? "" : req.name().strip();
        if (name.isEmpty() || name.length() > 20) {
            throw ApiException.badRequest("链接名称要填，不超过 20 个字");
        }
        String url = req.url() == null ? "" : req.url().strip();
        if (!url.matches("(?i)^https?://\\S{3,490}$")) {
            throw ApiException.badRequest("链接地址要以 http:// 或 https:// 开头");
        }
        String desc = req.description() == null ? null : req.description().strip();
        if (desc != null && desc.length() > 200) {
            throw ApiException.badRequest("说明不能超过 200 个字");
        }
        List<String> roles = req.roles() == null || req.roles().isEmpty()
                ? List.of(Roles.TEACHER, Roles.STUDENT) : req.roles();
        if (!LINK_ROLES.containsAll(roles)) {
            throw ApiException.badRequest("角色不对");
        }
        m.setName(name);
        m.setUrl(url);
        m.setDescription(desc == null || desc.isEmpty() ? null : desc);
        m.setRoles(String.join(",", roles.stream().distinct().toList()));
        m.setUpdatedBy(userId);
        m.setUpdatedAt(AppModule.now(clock));
    }

    /** 链接模块对这个角色可不可见 */
    public static boolean visibleTo(AppModule m, String role) {
        return m.getRoles() == null || Arrays.asList(m.getRoles().split(",")).contains(role);
    }
}
