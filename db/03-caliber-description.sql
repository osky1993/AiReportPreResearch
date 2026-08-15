-- =====================================================================
-- 03：caliber_asset 增补 description 列（存量环境补丁，2026-07-29）
--   · 新环境无需执行——00-init.sql 的建表定义已含该列。
--   · MySQL 8 不支持 ADD COLUMN IF NOT EXISTS：重复执行会报
--     Duplicate column name 'description'，可安全忽略。
--   · 列为 NULL 表示「沉淀时未生成描述」（旧资产 / 反翻译失败），
--     问数前端会回落到按需「生成口径说明」按钮，不影响召回与直取。
-- 执行：mysql -h127.0.0.1 -P23306 -uroot -p reportbi < db/03-caliber-description.sql
-- =====================================================================
ALTER TABLE caliber_asset
  ADD COLUMN description VARCHAR(1000) NULL
    COMMENT '中文口径描述（核验采纳时 AI 反翻译 MQL 生成，失败为 NULL）'
    AFTER mql_json;
