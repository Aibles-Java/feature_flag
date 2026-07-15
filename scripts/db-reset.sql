-- scripts/db-reset.sql — fast, non-destructive reset of the feature_flag dev DB.
--
-- TRUNCATEs every table in the `public` schema EXCEPT Liquibase's history tables
-- (databasechangelog, databasechangeloglock). The app does NOT re-run migrations
-- afterwards — it just finds the business tables empty — so no app restart is
-- needed between test runs.
--
-- This lives in feature_flag because it encodes exactly one piece of *our* schema
-- knowledge: that we use Liquibase, whose history lives in those two tables. Every
-- business table is discovered from pg_tables at runtime, so adding a table needs
-- no edit here. If we ever switch migration tools, the spared set changes here,
-- next to the schema — not in a repo that only borrows the database.
DO $$
DECLARE
  r RECORD;
BEGIN
  FOR r IN
    SELECT tablename
    FROM pg_tables
    WHERE schemaname = 'public'
      AND tablename NOT IN ('databasechangelog', 'databasechangeloglock')
  LOOP
    EXECUTE format('TRUNCATE TABLE public.%I RESTART IDENTITY CASCADE', r.tablename);
  END LOOP;
END $$;
