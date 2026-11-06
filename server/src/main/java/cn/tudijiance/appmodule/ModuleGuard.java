package cn.tudijiance.appmodule;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import cn.tudijiance.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 停用的功能模块，服务器也拒绝它的接口，不只是前端把菜单藏起来。
 * 按路径对应到模块；平面图录入、品种、录入项目、用户这些必需模块不在这里（停不掉）。
 */
@Component
public class ModuleGuard implements HandlerInterceptor {

    private record Rule(Pattern path, String method, String module) {
    }

    /** 按顺序找第一个对上的；method 为 null 表示不管 GET 还是 POST */
    private static final Rule[] RULES = {
            new Rule(Pattern.compile("^/api/screen$"), null, "screen"),
            new Rule(Pattern.compile("^/api/stats/record-book/.*"), null, "record_book"),
            new Rule(Pattern.compile("^/api/trials/\\d+/book$"), null, "record_book"),
            new Rule(Pattern.compile("^/api/stats/.*"), null, "stats"),
            new Rule(Pattern.compile("^/api/analysis(/.*)?$"), null, "analysis"),
            new Rule(Pattern.compile("^/api/imports(/.*)?$"), null, "import"),
            new Rule(Pattern.compile("^/api/(devices|sensor-metrics|ingest)(/.*)?$"), null, "devices"),
            // 数据查看只管列表和导出；录入（POST）、平面图上看小区详情不受影响
            new Rule(Pattern.compile("^/api/observations(/export)?$"), "GET", "data"),
            new Rule(Pattern.compile("^/api/quality(/.*)?$"), null, "quality"),
            new Rule(Pattern.compile("^/api/corrections(/.*)?$"), null, "corrections"),
            new Rule(Pattern.compile("^/api/(seasons|lines|breeding)(/.*)?$"), null, "breeding"),
    };

    private final ModuleService modules;

    public ModuleGuard(ModuleService modules) {
        this.modules = modules;
    }

    /** 这个请求属于哪个模块，不属于可停用的模块时返回 null */
    static String moduleOf(String method, String path) {
        for (Rule r : RULES) {
            if ((r.method() == null || r.method().equalsIgnoreCase(method)) && r.path().matcher(path).matches()) {
                return r.module();
            }
        }
        return null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String module = moduleOf(request.getMethod(), path);
        if (module != null && !modules.isOn(module)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "“" + modules.nameOf(module) + "”已被系统管理员停用");
        }
        return true;
    }
}
