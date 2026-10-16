UPDATE subscription
SET status = 'CANCELED'
WHERE status = 'ACTIVE' AND end_date <= CURRENT_TIMESTAMP;

WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY user_id
        ORDER BY end_date DESC NULLS FIRST, start_date DESC NULLS LAST, id DESC
    ) AS position
    FROM subscription
    WHERE status = 'ACTIVE'
)
UPDATE subscription
SET status = 'CANCELED', end_date = CURRENT_TIMESTAMP
WHERE id IN (SELECT id FROM ranked WHERE position > 1);

CREATE UNIQUE INDEX idx_subscription_active_user ON subscription(user_id) WHERE status = 'ACTIVE';
