-- 数据质量检查：录入项目的常见范围、老师核实过的问题

-- 常见范围：录入时超出会提醒"确认没录错？"，质量检查列为"超出常见范围"。不是硬限制，超出也能保存
ALTER TABLE trait_def ADD COLUMN min_value DECIMAL(14, 4);
ALTER TABLE trait_def ADD COLUMN max_value DECIMAL(14, 4);

UPDATE trait_def SET min_value = 30, max_value = 150 WHERE code = 'plant_height';
UPDATE trait_def SET min_value = 5, max_value = 50 WHERE code = 'basic_seedlings';
UPDATE trait_def SET min_value = 20, max_value = 200 WHERE code = 'max_tillers';
UPDATE trait_def SET min_value = 15, max_value = 80 WHERE code = 'effective_spikes';
UPDATE trait_def SET min_value = 15, max_value = 100 WHERE code = 'spike_rate';
UPDATE trait_def SET min_value = 15, max_value = 70 WHERE code = 'grains_per_spike';
UPDATE trait_def SET min_value = 25, max_value = 65 WHERE code = 'thousand_grain_weight';
UPDATE trait_def SET min_value = 650, max_value = 860 WHERE code = 'test_weight';
UPDATE trait_def SET min_value = 0, max_value = 30 WHERE code = 'black_germ_rate';
UPDATE trait_def SET min_value = 0, max_value = 50 WHERE code = 'off_type_count';
UPDATE trait_def SET min_value = 0, max_value = 20 WHERE code = 'off_type_rate';
UPDATE trait_def SET min_value = 150, max_value = 1000 WHERE code = 'yield_per_mu';
UPDATE trait_def SET min_value = 200, max_value = 290 WHERE code = 'whole_growth_days';
UPDATE trait_def SET min_value = 180, max_value = 280 WHERE code = 'growth_days';
UPDATE trait_def SET min_value = 0, max_value = 100 WHERE code IN (
    'overwinter_rate', 'lodging_first_area', 'lodging_final_area', 'stripe_rust_severity', 'stripe_rust_incidence',
    'leaf_rust_severity', 'leaf_rust_incidence', 'stem_rust_severity', 'stem_rust_incidence', 'scab_spike_rate',
    'sharp_eyespot_whitehead', 'crown_rot_whitehead', 'yellow_dwarf_incidence', 'sprouting_rate');

-- 老师核实过、确认没错的问题（例如倒伏小区株高确实矮），以后检查不再算进待处理
CREATE TABLE quality_ack (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    trial_id  BIGINT       NOT NULL,
    issue_key VARCHAR(200) NOT NULL,
    note      VARCHAR(200),
    acked_by  BIGINT       NOT NULL,
    acked_at  DATETIME     NOT NULL,
    CONSTRAINT uk_quality_ack UNIQUE (trial_id, issue_key),
    CONSTRAINT fk_ack_trial FOREIGN KEY (trial_id) REFERENCES trial (id) ON DELETE CASCADE,
    CONSTRAINT fk_ack_user FOREIGN KEY (acked_by) REFERENCES app_user (id)
);

INSERT INTO app_module (code, name, description, kind, required, enabled, sort_order) VALUES
    ('quality', '数据质量检查', '按标准第 9 章检查：重要项目漏录、超出常见范围、日期先后不对、产量误差变异系数等', 'BUILTIN', FALSE, TRUE, 85);
