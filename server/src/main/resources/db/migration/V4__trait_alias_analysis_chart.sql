-- 录入项目和导入表格联动、自助分析

-- 录入项目的表头别名：历史表格里的表头写法（如"株高(cm)""穗数"）对应哪个录入项目。
-- 导入时按别名自动认出各列；导入时确认过的对应关系会自动记下来，也可以在"录入项目"里修改。
-- alias 存规范化后的写法：全角转半角、去掉空白、英文字母小写
CREATE TABLE trait_alias (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    trait_id   BIGINT      NOT NULL,
    alias      VARCHAR(64) NOT NULL,
    created_at DATETIME    NOT NULL,
    CONSTRAINT uk_trait_alias UNIQUE (alias),
    CONSTRAINT fk_alias_trait FOREIGN KEY (trait_id) REFERENCES trait_def (id) ON DELETE CASCADE
);
CREATE INDEX idx_alias_trait ON trait_alias (trait_id);

-- 自助分析里保存的图表。config 是图表设置（范围、字段、图表类型），由客户端定义，服务器原样保存
CREATE TABLE analysis_chart (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    config     TEXT         NOT NULL,
    created_by BIGINT       NOT NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    CONSTRAINT fk_chart_user FOREIGN KEY (created_by) REFERENCES app_user (id)
);
