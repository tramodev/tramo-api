DELETE FROM public.refresh_token;
DELETE FROM public.password_reset_token;

ALTER TABLE public.refresh_token RENAME COLUMN token TO token_hash;
ALTER TABLE public.refresh_token ALTER COLUMN token_hash TYPE varchar(64);
ALTER TABLE public.refresh_token DROP COLUMN replacement_token;

ALTER TABLE public.password_reset_token RENAME COLUMN token TO token_hash;
ALTER TABLE public.password_reset_token ALTER COLUMN token_hash TYPE varchar(64);
