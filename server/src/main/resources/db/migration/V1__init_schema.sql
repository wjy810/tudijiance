-- 小麦品种实验田数字化管理系统 初始表结构
-- 写法同时兼容 MySQL 8 和 H2（MySQL 模式），H2 用于测试和本地快速体验

-- 用户
CREATE TABLE app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64),
    role          VARCHAR(16)  NOT NULL,             -- ADMIN 管理员 / RECORDER 录入员
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME     NOT NULL,
    CONSTRAINT uk_user_username UNIQUE (username)
);

-- 实验田：物理地块，划分成 row_count × col_count 的小区网格
CREATE TABLE field (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    location      VARCHAR(200),
    latitude      DECIMAL(9, 6),
    longitude     DECIMAL(9, 6),
    altitude_m    DECIMAL(7, 1),
    area_mu       DECIMAL(10, 2),
    row_count     INT          NOT NULL,
    col_count     INT          NOT NULL,
    soil_type     VARCHAR(50),
    irrigated     BOOLEAN,                             -- 水地 true / 旱地 false
    previous_crop VARCHAR(50),
    remark        VARCHAR(500),
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL
);

-- 小区：实验田网格中的一格（行号、列号从 1 开始）
CREATE TABLE plot (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    field_id       BIGINT      NOT NULL,
    row_no         INT         NOT NULL,
    col_no         INT         NOT NULL,
    code           VARCHAR(32),
    kind           VARCHAR(16) NOT NULL DEFAULT 'PLOT', -- PLOT 试验小区 / GUARD 保护区 / PATH 走道
    length_m       DECIMAL(6, 2),
    width_m        DECIMAL(6, 2),
    planting_rows  INT,
    row_spacing_cm DECIMAL(5, 1),
    CONSTRAINT uk_plot_position UNIQUE (field_id, row_no, col_no),
    CONSTRAINT uk_plot_code UNIQUE (field_id, code),
    CONSTRAINT fk_plot_field FOREIGN KEY (field_id) REFERENCES field (id) ON DELETE CASCADE
);

-- 品种
CREATE TABLE variety (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    code         VARCHAR(50),
    breeder      VARCHAR(200),
    ecotype      VARCHAR(16),          -- SPRING 春性 / WEAK_SPRING 弱春性 / SEMI_WINTER 半冬性 / WINTER 冬性
    quality_type VARCHAR(16),          -- STRONG 强筋 / MEDIUM_STRONG 中强筋 / MEDIUM 中筋 / WEAK 弱筋
    control_type VARCHAR(16),          -- 空 不是对照 / YIELD 丰产对照 / QUALITY 优质对照 / RESISTANCE 抗性对照
    remark       VARCHAR(500),
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_variety_name UNIQUE (name)
);

-- 试验：某一年在某块实验田上的布局
CREATE TABLE trial (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    field_id        BIGINT       NOT NULL,
    season_year     INT          NOT NULL,
    name            VARCHAR(100) NOT NULL,
    trial_type      VARCHAR(16)  NOT NULL,   -- REGIONAL 区域试验 / PRODUCTION 生产试验 / COMPARISON 品比试验 / OBSERVATION 观察圃
    wheat_zone      VARCHAR(8),              -- 麦区 Q1–Q11
    replicate_count INT,
    remark          VARCHAR(500),
    created_at      DATETIME     NOT NULL,
    CONSTRAINT fk_trial_field FOREIGN KEY (field_id) REFERENCES field (id)
);

-- 种植记录：试验 + 小区 + 品种 + 重复号
CREATE TABLE planting (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id     BIGINT NOT NULL,
    plot_id      BIGINT NOT NULL,
    variety_id   BIGINT,
    replicate_no INT,
    CONSTRAINT uk_planting UNIQUE (trial_id, plot_id),
    CONSTRAINT fk_planting_trial FOREIGN KEY (trial_id) REFERENCES trial (id) ON DELETE CASCADE,
    CONSTRAINT fk_planting_plot FOREIGN KEY (plot_id) REFERENCES plot (id),
    CONSTRAINT fk_planting_variety FOREIGN KEY (variety_id) REFERENCES variety (id)
);

-- 对照关系：平面图上用虚线连接的一组小区
CREATE TABLE control_link (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id   BIGINT      NOT NULL,
    label      VARCHAR(50) NOT NULL,
    created_at DATETIME    NOT NULL,
    CONSTRAINT fk_link_trial FOREIGN KEY (trial_id) REFERENCES trial (id) ON DELETE CASCADE
);

CREATE TABLE control_link_plot (
    link_id    BIGINT  NOT NULL,
    plot_id    BIGINT  NOT NULL,
    is_control BOOLEAN NOT NULL DEFAULT FALSE,     -- 是否是对照小区
    PRIMARY KEY (link_id, plot_id),
    CONSTRAINT fk_link_plot_link FOREIGN KEY (link_id) REFERENCES control_link (id) ON DELETE CASCADE,
    CONSTRAINT fk_link_plot_plot FOREIGN KEY (plot_id) REFERENCES plot (id)
);

-- 性状定义：采用 NY/T 1301—2025 附录 D 的数据描述，预设和自定义都在这里
CREATE TABLE trait_def (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    category         VARCHAR(16)  NOT NULL,  -- PHENOLOGY 物候期 / MORPHOLOGY 形态 / GROWTH 生育动态 / STRESS 抗逆 / DISEASE 病虫害 / LAB 考种 / YIELD 产量
    data_type        VARCHAR(16)  NOT NULL,  -- DATE / NUMBER / GRADE 分级 / OPTION 选项 / TEXT
    unit             VARCHAR(16),
    precision_digits INT,
    agg_method       VARCHAR(16)  NOT NULL,  -- 多个重复的汇总方法：NONE / AVG / MAX / MIN / MODE
    survey_time      VARCHAR(200),           -- 调查时间和方法
    std_clause       VARCHAR(32),            -- 标准条款号
    is_standard      BOOLEAN      NOT NULL DEFAULT TRUE,
    is_computed      BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order       INT          NOT NULL DEFAULT 0,
    description      VARCHAR(500),
    CONSTRAINT uk_trait_code UNIQUE (code)
);

CREATE TABLE trait_option (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trait_id    BIGINT      NOT NULL,
    value_code  VARCHAR(16) NOT NULL,
    label       VARCHAR(50) NOT NULL,
    description VARCHAR(200),
    sort_order  INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_trait_option UNIQUE (trait_id, value_code),
    CONSTRAINT fk_option_trait FOREIGN KEY (trait_id) REFERENCES trait_def (id) ON DELETE CASCADE
);

-- 传感器设备（硬件预留）
CREATE TABLE device (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL,          -- 设备编号，也是 MQTT 主题里的设备名
    name         VARCHAR(100) NOT NULL,
    device_type  VARCHAR(32)  NOT NULL,          -- SOIL_3IN1 土壤三合一（温度、湿度、pH）
    field_id     BIGINT,
    pos_row      DECIMAL(6, 2),                  -- 在平面图上的位置，可以落在两个小区之间
    pos_col      DECIMAL(6, 2),
    depth_cm     INT,
    token_hash   VARCHAR(64),                    -- HTTP 上报用的设备令牌（只存哈希）
    simulated    BOOLEAN      NOT NULL DEFAULT FALSE,
    status       VARCHAR(16)  NOT NULL DEFAULT 'NEVER', -- NEVER 从未上报 / ONLINE / OFFLINE
    last_seen_at DATETIME,
    remark       VARCHAR(500),
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_device_code UNIQUE (code),
    CONSTRAINT fk_device_field FOREIGN KEY (field_id) REFERENCES field (id) ON DELETE SET NULL
);

-- 测量项：阶段一启用土壤温度、湿度、pH，其余为以后加传感器预留
CREATE TABLE sensor_metric (
    code             VARCHAR(32) PRIMARY KEY,
    name             VARCHAR(50) NOT NULL,
    unit             VARCHAR(16),
    min_valid        DECIMAL(10, 3),
    max_valid        DECIMAL(10, 3),
    precision_digits INT         NOT NULL,
    enabled          BOOLEAN     NOT NULL,
    sort_order       INT         NOT NULL
);

INSERT INTO sensor_metric (code, name, unit, min_valid, max_valid, precision_digits, enabled, sort_order) VALUES
    ('soil_temp',     '土壤温度',           '℃',     -30, 70,    1, TRUE,  1),
    ('soil_moisture', '土壤湿度（体积含水量）', '%',      0,   100,   1, TRUE,  2),
    ('soil_ph',       '土壤 pH',            NULL,     3,   10,    2, TRUE,  3),
    ('air_temp',      '空气温度',           '℃',     -40, 60,    1, FALSE, 4),
    ('air_humidity',  '空气相对湿度',        '%',      0,   100,   1, FALSE, 5),
    ('soil_ec',       '土壤电导率',          'μS/cm',  0,   20000, 0, FALSE, 6),
    ('soil_n',        '土壤氮',             'mg/kg',  0,   2000,  0, FALSE, 7),
    ('soil_p',        '土壤磷',             'mg/kg',  0,   2000,  0, FALSE, 8),
    ('soil_k',        '土壤钾',             'mg/kg',  0,   2000,  0, FALSE, 9);

-- 传感器读数。同一设备、同一测量项、同一时刻只保留一条，DTU 断线重发时不会重复
CREATE TABLE sensor_reading (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id    BIGINT         NOT NULL,
    metric       VARCHAR(32)    NOT NULL,
    metric_value DECIMAL(10, 3) NOT NULL,
    measured_at  DATETIME       NOT NULL,
    received_at  DATETIME       NOT NULL,
    source       VARCHAR(16)    NOT NULL,         -- MQTT / HTTP / SIMULATOR
    out_of_range BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_reading UNIQUE (device_id, metric, measured_at),
    CONSTRAINT fk_reading_device FOREIGN KEY (device_id) REFERENCES device (id) ON DELETE CASCADE,
    CONSTRAINT fk_reading_metric FOREIGN KEY (metric) REFERENCES sensor_metric (code)
);
