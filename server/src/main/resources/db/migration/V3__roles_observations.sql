-- 第一期：四级用户、录入的数据、操作记录、小区异常标记

-- 四级用户：SYS_ADMIN 系统管理员 / TEACHER 老师 / STUDENT 学生 / GUEST 游客
UPDATE app_user SET role = 'SYS_ADMIN' WHERE role = 'ADMIN';
UPDATE app_user SET role = 'STUDENT' WHERE role = 'RECORDER';

-- 小区异常标记（平面图右键"标记异常"）
ALTER TABLE planting ADD COLUMN abnormal BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE planting ADD COLUMN abnormal_note VARCHAR(200);

-- 录入的数据：某个种植记录（试验 + 小区）某一天某个项目的值
CREATE TABLE observation (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_uuid VARCHAR(36)    NOT NULL,          -- 客户端生成，重复提交不会产生重复数据
    planting_id BIGINT         NOT NULL,
    trait_id    BIGINT         NOT NULL,
    observed_on DATE           NOT NULL,          -- 调查或操作的日期
    value_num   DECIMAL(14, 4),                   -- 数值、分级、农事用量
    value_text  VARCHAR(500),                     -- 选项、文字、农事类型
    value_date  DATE,                             -- 日期型项目（如出苗期）
    note        VARCHAR(500),
    source      VARCHAR(16)    NOT NULL,          -- MANUAL 手动 / IMPORT 导入 / SENSOR 自动采集
    recorded_by BIGINT         NOT NULL,
    recorded_at DATETIME       NOT NULL,
    updated_by  BIGINT,
    updated_at  DATETIME,
    version     INT            NOT NULL DEFAULT 1,
    deleted     BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_observation_uuid UNIQUE (client_uuid),
    CONSTRAINT fk_obs_planting FOREIGN KEY (planting_id) REFERENCES planting (id) ON DELETE CASCADE,
    CONSTRAINT fk_obs_trait FOREIGN KEY (trait_id) REFERENCES trait_def (id),
    CONSTRAINT fk_obs_recorder FOREIGN KEY (recorded_by) REFERENCES app_user (id)
);
CREATE INDEX idx_obs_planting_trait ON observation (planting_id, trait_id, observed_on);
CREATE INDEX idx_obs_trait_date ON observation (trait_id, observed_on);
CREATE INDEX idx_obs_recorded_at ON observation (recorded_at);

-- 修改和删除的记录
CREATE TABLE audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity     VARCHAR(32)  NOT NULL,
    entity_id  BIGINT       NOT NULL,
    action     VARCHAR(16)  NOT NULL,             -- CREATE / UPDATE / DELETE
    old_value  VARCHAR(600),
    new_value  VARCHAR(600),
    user_id    BIGINT       NOT NULL,
    created_at DATETIME     NOT NULL
);
CREATE INDEX idx_audit_entity ON audit_log (entity, entity_id);

-- 农事操作：类型从列表里选，用量可选填，药剂名称等写在备注里
INSERT INTO trait_def (code, name, category, data_type, unit, precision_digits, agg_method, survey_time, std_clause,
                       is_standard, is_computed, enabled, sort_order)
VALUES ('op_fertilize', '施肥', 'OPERATION', 'OPERATION', 'kg/亩', 1, 'NONE', '选择肥料种类，填用量', '附录 B', TRUE, FALSE, TRUE, 10),
       ('op_irrigate', '灌溉', 'OPERATION', 'OPERATION', 'm³/亩', 0, 'NONE', '选择灌溉方式，填水量', '附录 B', TRUE, FALSE, TRUE, 20),
       ('op_pest_control', '病虫害防治', 'OPERATION', 'OPERATION', NULL, NULL, 'NONE', '选择类型，药剂名称写在备注里；区域试验不得对病害用药', '6.5', TRUE, FALSE, TRUE, 30),
       ('op_weeding', '中耕除草', 'OPERATION', 'OPERATION', NULL, NULL, 'NONE', '选择方式', '附录 B', TRUE, FALSE, TRUE, 40),
       ('op_thinning', '定苗/间苗', 'OPERATION', 'OPERATION', NULL, NULL, 'NONE', '3 叶期按留苗密度定苗', '6.4', TRUE, FALSE, TRUE, 50),
       ('op_harvest', '收获', 'OPERATION', 'OPERATION', NULL, NULL, 'NONE', '成熟后全区收获', '第 7 章', TRUE, FALSE, TRUE, 60),
       ('op_other', '其他农事', 'OPERATION', 'TEXT', NULL, NULL, 'NONE', '写明做了什么', NULL, TRUE, FALSE, TRUE, 70);

INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '尿素', '尿素', 1 FROM trait_def WHERE code = 'op_fertilize';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '复合肥', '复合肥', 2 FROM trait_def WHERE code = 'op_fertilize';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '磷酸二铵', '磷酸二铵', 3 FROM trait_def WHERE code = 'op_fertilize';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '硫酸钾', '硫酸钾', 4 FROM trait_def WHERE code = 'op_fertilize';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '有机肥', '有机肥', 5 FROM trait_def WHERE code = 'op_fertilize';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '其他', '其他', 6 FROM trait_def WHERE code = 'op_fertilize';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '漫灌', '漫灌', 1 FROM trait_def WHERE code = 'op_irrigate';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '喷灌', '喷灌', 2 FROM trait_def WHERE code = 'op_irrigate';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '滴灌', '滴灌', 3 FROM trait_def WHERE code = 'op_irrigate';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '杀虫', '杀虫', 1 FROM trait_def WHERE code = 'op_pest_control';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '杀菌', '杀菌', 2 FROM trait_def WHERE code = 'op_pest_control';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '一喷三防', '一喷三防', 3 FROM trait_def WHERE code = 'op_pest_control';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '其他', '其他', 4 FROM trait_def WHERE code = 'op_pest_control';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '人工除草', '人工除草', 1 FROM trait_def WHERE code = 'op_weeding';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '化学除草', '化学除草', 2 FROM trait_def WHERE code = 'op_weeding';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '中耕', '中耕', 3 FROM trait_def WHERE code = 'op_weeding';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '定苗', '定苗', 1 FROM trait_def WHERE code = 'op_thinning';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '间苗', '间苗', 2 FROM trait_def WHERE code = 'op_thinning';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '人工收获', '人工收获', 1 FROM trait_def WHERE code = 'op_harvest';
INSERT INTO trait_option (trait_id, value_code, label, sort_order)
SELECT id, '机械收获', '机械收获', 2 FROM trait_def WHERE code = 'op_harvest';
