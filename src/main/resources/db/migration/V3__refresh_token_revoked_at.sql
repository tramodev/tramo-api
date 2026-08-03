ALTER TABLE public.refresh_token ADD COLUMN revoked_at timestamp(6) with time zone;
