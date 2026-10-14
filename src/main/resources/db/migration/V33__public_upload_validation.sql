CREATE TABLE public.public_upload (
    id uuid PRIMARY KEY,
    owner_id bigint NOT NULL,
    upload_record_id bigint NOT NULL UNIQUE REFERENCES public.upload_record(id) ON DELETE CASCADE,
    kind varchar(255) NOT NULL CHECK (kind IN ('avatar', 'banner', 'thumbnail')),
    content_type varchar(255) NOT NULL,
    content_hash varchar(255) NOT NULL,
    state varchar(255) NOT NULL CHECK (state IN ('PENDING', 'READY', 'DELETING')),
    requested_at timestamp NOT NULL,
    temporary_cleaned boolean NOT NULL DEFAULT false,
    UNIQUE (owner_id, kind, content_hash)
);
CREATE INDEX idx_public_upload_cleanup ON public.public_upload(state, requested_at);
