-- Subscription.plan was @OneToOne, so Hibernate generated UNIQUE (plan_id). Every Patreon
-- supporter shares the single "Patreon Supporter" plan row, so the second one ever to
-- subscribe violated it. It's a @ManyToOne now; the unique goes, a plain FK index takes over.
ALTER TABLE subscription DROP CONSTRAINT IF EXISTS uk8lmcunrss2bll2ydkw4lptdnh;

CREATE INDEX IF NOT EXISTS idx_subscription_plan ON subscription (plan_id);
