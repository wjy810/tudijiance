-- 育种：季、地块（属于某一季的实验田）、品系（带系谱的品种）
-- 品系存在 variety 表里：line_kind 不为空的是品系。原来的品种比较只看 line_kind 为空或 FOUNDER 的，照常能用。

-- 季：一次种植周期，如"2025-2026 年度大田""2026 夏繁"；一年可以有两季
CREATE TABLE season (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    start_date DATE         NOT NULL,
    end_date   DATE,
    remark     VARCHAR(500),
    created_at DATETIME     NOT NULL,
    CONSTRAINT uk_season_name UNIQUE (name)
);

-- 这一季接着哪一季（或几季）：种植时下拉框最前面列出这些季种过的品系
CREATE TABLE season_follow (
    season_id      BIGINT NOT NULL,
    prev_season_id BIGINT NOT NULL,
    PRIMARY KEY (season_id, prev_season_id),
    CONSTRAINT fk_follow_season FOREIGN KEY (season_id) REFERENCES season (id) ON DELETE CASCADE,
    CONSTRAINT fk_follow_prev FOREIGN KEY (prev_season_id) REFERENCES season (id) ON DELETE CASCADE
);

-- 地块：属于某一季的实验田（地块 → 区块 → 小区块，区块布局沿用 field.layout）。为空的是原来品种比较用的实验田
ALTER TABLE field ADD COLUMN season_id BIGINT;
ALTER TABLE field ADD CONSTRAINT fk_field_season FOREIGN KEY (season_id) REFERENCES season (id);
CREATE INDEX idx_field_season ON field (season_id);

-- 品系
ALTER TABLE variety ADD COLUMN line_kind VARCHAR(16);         -- FOUNDER 原始材料 / CROSS 杂交组合 / SELECTION 选株后代
ALTER TABLE variety ADD COLUMN line_code VARCHAR(64);         -- 编号：建好后不变，系谱靠它连接
ALTER TABLE variety ADD COLUMN mother_id BIGINT;              -- 杂交组合的母本
ALTER TABLE variety ADD COLUMN father_id BIGINT;              -- 杂交组合的父本
ALTER TABLE variety ADD COLUMN parent_id BIGINT;              -- 选株后代：从哪个品系里选出来的
ALTER TABLE variety ADD COLUMN line_status VARCHAR(16);       -- ACTIVE 在选 / KEPT 保留 / DROPPED 淘汰 / RELEASED 已育成
ALTER TABLE variety ADD COLUMN former_names VARCHAR(1000);    -- 曾用名，用"、"隔开
ALTER TABLE variety ADD COLUMN origin_season_id BIGINT;       -- 杂交、选株是在哪一季做的
ALTER TABLE variety ADD COLUMN origin_field_id BIGINT;        -- 选株：从哪块地块选的
ALTER TABLE variety ADD COLUMN origin_block_id VARCHAR(32);   -- 选株：从哪个区块选的
ALTER TABLE variety ADD COLUMN origin_plot_id BIGINT;         -- 选株：从哪个小区块选的（整块选时为空）
ALTER TABLE variety ADD COLUMN seq_no INT;                    -- 杂交组合：当年第几个；选株后代：从父本里选出的第几株
ALTER TABLE variety ADD COLUMN cross_year INT;                -- 杂交组合：杂交年份
ALTER TABLE variety ADD COLUMN tag_no VARCHAR(32);            -- 选株：田间挂牌号（不放进编号）
CREATE UNIQUE INDEX uk_variety_line_code ON variety (line_code);
CREATE INDEX idx_variety_mother ON variety (mother_id);
CREATE INDEX idx_variety_father ON variety (father_id);
CREATE INDEX idx_variety_parent ON variety (parent_id);
ALTER TABLE variety ADD CONSTRAINT fk_variety_mother FOREIGN KEY (mother_id) REFERENCES variety (id);
ALTER TABLE variety ADD CONSTRAINT fk_variety_father FOREIGN KEY (father_id) REFERENCES variety (id);
ALTER TABLE variety ADD CONSTRAINT fk_variety_parent FOREIGN KEY (parent_id) REFERENCES variety (id);
ALTER TABLE variety ADD CONSTRAINT fk_variety_origin_season FOREIGN KEY (origin_season_id) REFERENCES season (id);

-- 世代：杂交后代这一季种的是 F 几（记在每季的种植记录上；原始材料为空）
ALTER TABLE planting ADD COLUMN generation INT;

-- 选出时的单株数据：选株时当场量的这一株的数据，记在子代名下，不进大田的汇总和统计
CREATE TABLE line_plant_value (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id     BIGINT        NOT NULL,
    trait_id    BIGINT        NOT NULL,
    value_num   DECIMAL(14, 4),
    value_text  VARCHAR(500),
    value_date  DATE,
    recorded_by BIGINT        NOT NULL,
    recorded_at DATETIME      NOT NULL,
    CONSTRAINT uk_line_plant_value UNIQUE (line_id, trait_id),
    CONSTRAINT fk_lpv_line FOREIGN KEY (line_id) REFERENCES variety (id) ON DELETE CASCADE,
    CONSTRAINT fk_lpv_trait FOREIGN KEY (trait_id) REFERENCES trait_def (id),
    CONSTRAINT fk_lpv_user FOREIGN KEY (recorded_by) REFERENCES app_user (id)
);

INSERT INTO app_module (code, name, description, kind, required, enabled, sort_order) VALUES
    ('breeding', '育种', '季和地块、品系库、系谱图：按品系管理种植和数据，选株、杂交', 'BUILTIN', FALSE, TRUE, 5);
