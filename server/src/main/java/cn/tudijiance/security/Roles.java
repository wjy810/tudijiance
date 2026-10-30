package cn.tudijiance.security;

import java.util.Set;

/**
 * 四级用户（需求分析 3.1）和接口上用的权限表达式。
 *
 * <pre>
 * 系统管理员：用户管理、功能模块管理、系统维护；不能录入或修改数据
 * 老师：录入、查看、修改、删除数据；设置实验田、小区、品种；导入
 * 学生：录入和查看数据
 * 游客：只能看大屏
 * </pre>
 */
public final class Roles {

    public static final String SYS_ADMIN = "SYS_ADMIN";
    public static final String TEACHER = "TEACHER";
    public static final String STUDENT = "STUDENT";
    public static final String GUEST = "GUEST";

    public static final Set<String> ALL = Set.of(SYS_ADMIN, TEACHER, STUDENT, GUEST);

    /** 用户管理、设备登记等系统管理 */
    public static final String IS_SYS_ADMIN = "hasRole('SYS_ADMIN')";
    /** 设置实验田、品种、录入项目；修改和删除数据 */
    public static final String IS_TEACHER = "hasRole('TEACHER')";
    /** 录入和查看数据 */
    public static final String CAN_RECORD = "hasAnyRole('TEACHER', 'STUDENT')";
    /** 查看实验田、设备等基础信息（系统管理员登记设备时要选实验田） */
    public static final String CAN_VIEW_SETUP = "hasAnyRole('TEACHER', 'STUDENT', 'SYS_ADMIN')";
    /** 大屏 */
    public static final String CAN_VIEW_SCREEN = "hasAnyRole('TEACHER', 'STUDENT', 'SYS_ADMIN', 'GUEST')";

    private Roles() {
    }
}
