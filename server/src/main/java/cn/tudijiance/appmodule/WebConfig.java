package cn.tudijiance.appmodule;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ModuleGuard moduleGuard;

    public WebConfig(ModuleGuard moduleGuard) {
        this.moduleGuard = moduleGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(moduleGuard).addPathPatterns("/api/**");
    }
}
