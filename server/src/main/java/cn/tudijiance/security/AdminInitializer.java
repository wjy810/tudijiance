package cn.tudijiance.security;

import java.security.SecureRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import cn.tudijiance.common.AppProperties;
import cn.tudijiance.user.User;
import cn.tudijiance.user.UserService;

/**
 * 数据库里还没有用户时，创建管理员 admin。
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final UserService userService;
    private final AppProperties properties;

    public AdminInitializer(UserService userService, AppProperties properties) {
        this.userService = userService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userService.count() > 0) {
            return;
        }
        String password = properties.admin().initialPassword();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = randomPassword();
        }
        userService.create("admin", "管理员", User.ROLE_ADMIN, password);
        if (generated) {
            log.warn("已创建管理员账号 admin，初始密码：{}（只显示这一次，登录后请修改）", password);
        } else {
            log.info("已创建管理员账号 admin，密码为配置的 ADMIN_INITIAL_PASSWORD");
        }
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
