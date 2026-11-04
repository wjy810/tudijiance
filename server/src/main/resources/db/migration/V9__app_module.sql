-- 功能模块管理（需求分析 3.1：系统管理员增加、删除、停用功能模块）
-- BUILTIN 内置模块：代码里已有的功能，可以停用、启用，不能删；required 的（平面图录入、用户管理等）不能停用
-- LINK 链接模块：管理员自己加的菜单链接（例如学校教务系统、气象网站），可以增、删、改、停用
CREATE TABLE app_module (
    code        VARCHAR(32)  NOT NULL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    description VARCHAR(200),
    kind        VARCHAR(16)  NOT NULL,               -- BUILTIN / LINK
    required    BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    url         VARCHAR(500),                        -- 链接模块的地址
    roles       VARCHAR(100),                        -- 链接模块给哪些角色看，逗号分隔
    sort_order  INT          NOT NULL DEFAULT 0,
    updated_by  BIGINT,
    updated_at  DATETIME
);

INSERT INTO app_module (code, name, description, kind, required, enabled, sort_order) VALUES
    ('field_map',   '实验田平面图和录入', '平面图、布置试验、编辑地块，电脑和手机上选小区录入。系统的核心，不能停用', 'BUILTIN', TRUE, TRUE, 10),
    ('varieties',   '品种', '试验用的品种和对照品种', 'BUILTIN', TRUE, TRUE, 20),
    ('traits',      '录入项目', '标准项目、自定义项目、重要标签、导入时的表头', 'BUILTIN', TRUE, TRUE, 30),
    ('data',        '数据查看', '按条件找录入的数据、导出 Excel，老师修改和删除、看修改记录', 'BUILTIN', FALSE, TRUE, 40),
    ('stats',       '统计分析', '品种主要性状汇总表、品种对比、历年变化', 'BUILTIN', FALSE, TRUE, 50),
    ('analysis',    '自助分析', '自己选范围、分组和指标做图表，可以保存和导出', 'BUILTIN', FALSE, TRUE, 60),
    ('record_book', '试验记载本', '河南省小麦品种试验记载本：基本情况、气象资料、品种评价，导出 Excel 记载本', 'BUILTIN', FALSE, TRUE, 70),
    ('import',      '导入历史表格', '把以前 Excel 里的数据导进来', 'BUILTIN', FALSE, TRUE, 80),
    ('screen',      '数据大屏', '给大屏用的展示页面，游客只能看这一页', 'BUILTIN', FALSE, TRUE, 90),
    ('devices',     '设备接入（第二期预留）', '传感器上报接口和数据模拟器；第一期不接传感器时可以停用', 'BUILTIN', FALSE, TRUE, 100),
    ('users',       '用户管理', '增删账号、分配角色、重置密码', 'BUILTIN', TRUE, TRUE, 900),
    ('modules',     '功能模块管理', '就是这一页', 'BUILTIN', TRUE, TRUE, 910);
