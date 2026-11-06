-- 实验田并进季和地块、品种并进品系库以后（见 V13），功能模块跟着合并：
-- 季和地块、品系库、系谱图是核心，不能停用；原来单独的"品种"模块并进品系库
UPDATE app_module SET name = '季和地块、平面图和录入',
    description = '季、地块、平面图：种植、选株、杂交、布置试验，电脑和手机上选小区块录入。系统的核心，不能停用'
    WHERE code = 'field_map';
UPDATE app_module SET name = '品系库和系谱图', required = TRUE, enabled = TRUE, sort_order = 20,
    description = '品种和品系（原始材料、杂交组合、选株后代）、编号和系谱式、系谱图'
    WHERE code = 'breeding';
DELETE FROM app_module WHERE code = 'varieties';
