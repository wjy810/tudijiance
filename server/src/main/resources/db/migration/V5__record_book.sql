-- 合并《河南省小麦品种试验记载本（2024-2025）》：试验的基本情况、气象资料、品种评价，以及计产用的收获面积。
-- 记载本里的田间记载、考种、产量各列对应到已有的录入项目，表头别名和缺的项目由 V6 写入。

-- 计产用的收获面积（m²）。记载本：收获面积 = 收获长 ×［(小区行数 − 1) × 行距 + 畦埂宽］。
-- 不为空时折合亩产、标准面积小区产量按它算，否则按小区的长 × 宽算
ALTER TABLE trial ADD COLUMN harvest_area_m2 DECIMAL(8, 2);

-- 试验记载本：一个试验一份。内容都是"字段 → 值"的 JSON，字段由客户端按记载本定义（client/src/shared/recordBook.ts）
--   info        封面、试验设计、栽培管理、产量计算参数、联系信息、气象分析
--   weather     气象资料表：键为"行|月|cur 或 norm"（当年、常年）
--   evaluations 品种评价：键为品种 id
CREATE TABLE trial_book (
    trial_id    BIGINT   NOT NULL PRIMARY KEY,
    info        TEXT,
    weather     TEXT,
    evaluations TEXT,
    updated_by  BIGINT,
    updated_at  DATETIME,
    CONSTRAINT fk_book_trial FOREIGN KEY (trial_id) REFERENCES trial (id) ON DELETE CASCADE,
    CONSTRAINT fk_book_user FOREIGN KEY (updated_by) REFERENCES app_user (id)
);
