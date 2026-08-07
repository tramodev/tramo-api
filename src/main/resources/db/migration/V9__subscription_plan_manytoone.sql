ALTER TABLE subscription DROP CONSTRAINT IF EXISTS uk8lmcunrss2bll2ydkw4lptdnh;

CREATE INDEX IF NOT EXISTS idx_subscription_plan ON subscription (plan_id);
