-- 实验田的区块布局（实验田 → 区块 → 小区），JSON，见 FieldLayout。
-- 为空的是以前按行列建的实验田：读的时候当作整块地一个区块，保存过布局之后按布局管理小区
ALTER TABLE field ADD COLUMN layout MEDIUMTEXT;
