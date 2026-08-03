CREATE TABLE public.patreon_webhook_signature (
    id uuid NOT NULL,
    signature character varying(255) NOT NULL,
    processed_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT patreon_webhook_signature_pkey PRIMARY KEY (id),
    CONSTRAINT patreon_webhook_signature_signature_key UNIQUE (signature)
);
