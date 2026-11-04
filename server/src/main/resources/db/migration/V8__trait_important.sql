-- 录入项目的"重要"标签。《河南省小麦品种试验记载本》里要填的项目都标为重要：
-- 录入时排在前面、标星；数据质量检查重点看重要项目有没有漏录。老师可以给任何项目加上或去掉，自定义项目新建时可以设
ALTER TABLE trait_def ADD COLUMN important BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE trait_def SET important = TRUE WHERE code IN (
    -- 基本情况、生育期
    'sowing_date', 'emergence_date', 'heading_date', 'maturity_date', 'whole_growth_days',
    -- 四、田间记载
    'seedling_habit', 'basic_seedlings', 'max_tillers', 'effective_spikes', 'spike_rate', 'plant_height',
    'winter_freeze_date', 'winter_freeze_grade', 'spring_freeze_date', 'spring_freeze_grade', 'overwinter_rate',
    'drought_date', 'drought_grade', 'dry_hot_date', 'dry_hot_grade', 'wet_date', 'wet_grade',
    'lodging_first_date', 'lodging_first_grade', 'lodging_first_area',
    'lodging_final_date', 'lodging_final_grade', 'lodging_final_area',
    'stripe_rust_reaction', 'stripe_rust_severity', 'stripe_rust_incidence', 'powdery_mildew',
    'leaf_rust_reaction', 'leaf_rust_severity', 'leaf_rust_incidence', 'scab_severity', 'scab_spike_rate',
    'sharp_eyespot', 'sharp_eyespot_whitehead', 'root_rot_leaf', 'root_rot_spike', 'leaf_blight',
    'sprouting_grade', 'shattering', 'maturity_appearance', 'off_type_count', 'plot_yield', 'field_evaluation',
    -- 六、室内考种
    'spike_shape', 'glume_color', 'awn', 'grains_per_spike', 'grain_color', 'plumpness', 'grain_texture',
    'black_germ_rate', 'thousand_grain_weight', 'test_weight', 'off_type_rate',
    -- 九、产量表
    'yield_per_mu'
);
