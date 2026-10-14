ALTER TABLE public.users ADD COLUMN google_sub varchar(255);
ALTER TABLE public.users ADD CONSTRAINT users_google_sub_unique UNIQUE (google_sub);
