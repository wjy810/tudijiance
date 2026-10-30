package cn.tudijiance.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import cn.tudijiance.common.ApiException;
import cn.tudijiance.security.Roles;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder, Clock clock) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public User findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    public User get(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw ApiException.notFound("用户");
        }
        return user;
    }

    public List<User> list() {
        return userMapper.selectList(Wrappers.<User>lambdaQuery().orderByAsc(User::getId));
    }

    public long count() {
        return userMapper.selectCount(null);
    }

    public User create(String username, String displayName, String role, String password) {
        if (!Roles.ALL.contains(role)) {
            throw ApiException.badRequest("角色只能是 SYS_ADMIN、TEACHER、STUDENT 或 GUEST");
        }
        if (findByUsername(username) != null) {
            throw ApiException.conflict("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now(clock));
        userMapper.insert(user);
        return user;
    }

    public void setPassword(Long id, String password) {
        User user = get(id);
        user.setPasswordHash(passwordEncoder.encode(password));
        userMapper.updateById(user);
    }

    public void setEnabled(Long id, boolean enabled) {
        User user = get(id);
        user.setEnabled(enabled);
        userMapper.updateById(user);
    }

    public boolean passwordMatches(User user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }
}
