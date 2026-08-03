-- Baseline schema, generated from the local dev DB (pg_dump --schema-only) at the point
-- Flyway was introduced. Reflects everything Hibernate's ddl-auto=update had produced,
-- plus the indexes/extension that used to be created at boot by ProjectSearchIndexRunner
-- and UserIndexRunner (both removed - this migration replaces them).

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

--
-- Name: association; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.association (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    target_id bigint,
    target_type character varying(255),
    type character varying(255),
    source_item_id bigint,
    CONSTRAINT association_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['ITEM'::character varying, 'TRAIL'::character varying])::text[]))),
    CONSTRAINT association_type_check CHECK (((type)::text = ANY ((ARRAY['REQUIRES'::character varying, 'ELABORATES'::character varying, 'CONTRADICTS'::character varying, 'EXAMPLE_OF'::character varying, 'RELATED'::character varying])::text[])))
);

--
-- Name: association_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.association_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: blocked_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.blocked_user (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    blocked_id bigint,
    blocker_id bigint
);

--
-- Name: blocked_user_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.blocked_user_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: comment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comment (
    id bigint NOT NULL,
    content character varying(2000),
    created_date timestamp(6) without time zone,
    deleted boolean NOT NULL,
    author_id bigint,
    parent_id bigint,
    project_id bigint
);

--
-- Name: comment_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.comment_report (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    reason character varying(255),
    status character varying(255),
    comment_id bigint,
    reporter_id bigint
);

--
-- Name: comment_report_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.comment_report_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: comment_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.comment_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: email_verification_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.email_verification_token (
    id uuid NOT NULL,
    expires_at timestamp(6) with time zone,
    token character varying(255) NOT NULL,
    user_id bigint NOT NULL
);

--
-- Name: follow; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.follow (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    followed_id bigint,
    follower_id bigint
);

--
-- Name: follow_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.follow_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.item (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    modified_date timestamp(6) without time zone,
    title character varying(255),
    title_align character varying(255),
    type character varying(255),
    unfiled boolean,
    content_id bigint,
    project_id bigint
);

--
-- Name: item_content; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.item_content (
    id bigint NOT NULL,
    content text,
    updated_date timestamp(6) without time zone
);

--
-- Name: item_content_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.item_content_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: item_image_reference; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.item_image_reference (
    id bigint NOT NULL,
    url character varying(255) NOT NULL,
    item_id bigint
);

--
-- Name: item_image_reference_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.item_image_reference_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: item_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.item_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: moderation_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.moderation_log (
    id bigint NOT NULL,
    action character varying(255),
    created_date timestamp(6) without time zone,
    reason character varying(255),
    target_id bigint,
    target_type character varying(255),
    admin_id bigint
);

--
-- Name: moderation_log_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.moderation_log_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: notification; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification (
    id bigint NOT NULL,
    badge_code character varying(255),
    badge_name character varying(255),
    count integer NOT NULL,
    created_date timestamp(6) without time zone,
    read boolean NOT NULL,
    type character varying(255),
    updated_date timestamp(6) without time zone,
    actor_id bigint,
    project_id bigint,
    recipient_id bigint
);

--
-- Name: notification_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.notification_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: password_reset_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.password_reset_token (
    id uuid NOT NULL,
    expires_at timestamp(6) with time zone,
    token character varying(255) NOT NULL,
    user_id bigint NOT NULL
);

--
-- Name: patreon_connect_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.patreon_connect_token (
    id uuid NOT NULL,
    expires_at timestamp(6) with time zone,
    token character varying(255) NOT NULL,
    user_id bigint NOT NULL
);

--
-- Name: payment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment (
    id bigint NOT NULL,
    amount numeric(38,2),
    currency character varying(255),
    description character varying(255),
    provider character varying(255)
);

--
-- Name: payment_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.payment_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: pending_image_deletion; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pending_image_deletion (
    id bigint NOT NULL,
    owner_id bigint,
    requested_at timestamp(6) without time zone,
    url character varying(255)
);

--
-- Name: pending_image_deletion_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.pending_image_deletion_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan (
    id bigint NOT NULL,
    active boolean NOT NULL,
    billing_interval integer NOT NULL,
    description character varying(255),
    name character varying(255),
    price numeric(38,2)
);

--
-- Name: plan_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.plan_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: project; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project (
    id bigint NOT NULL,
    creation_date timestamp(6) without time zone,
    description character varying(255),
    featured boolean DEFAULT false,
    first_published_date timestamp(6) without time zone,
    last_edited_date timestamp(6) without time zone,
    modified_date timestamp(6) without time zone,
    tags character varying(255),
    thumbnail text,
    title character varying(255),
    view_count bigint DEFAULT 0,
    visibility character varying(255),
    forked_from_id bigint,
    user_id bigint,
    thumbnail_image_url text,
    thumbnail_type character varying(255),
    thumbnail_trail_id bigint,
    CONSTRAINT project_thumbnail_type_check CHECK (((thumbnail_type)::text = ANY ((ARRAY['NONE'::character varying, 'GRAPH'::character varying, 'PROJECT_IMAGE'::character varying, 'DEDICATED'::character varying])::text[])))
);

--
-- Name: project_bookmark; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_bookmark (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    project_id bigint,
    user_id bigint
);

--
-- Name: project_bookmark_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.project_bookmark_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: project_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_report (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    reason character varying(255),
    status character varying(255),
    project_id bigint,
    reporter_id bigint
);

--
-- Name: project_report_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.project_report_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: project_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.project_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: project_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_snapshot (
    id bigint NOT NULL,
    content text,
    created_date timestamp(6) without time zone,
    trigger character varying(255),
    version integer,
    project_id bigint
);

--
-- Name: project_snapshot_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.project_snapshot_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: project_tag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_tag (
    project_id bigint NOT NULL,
    tag_id bigint NOT NULL
);

--
-- Name: project_view; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_view (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    viewer_key character varying(255),
    project_id bigint
);

--
-- Name: project_view_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.project_view_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: project_vote; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_vote (
    id bigint NOT NULL,
    created_date timestamp(6) without time zone,
    device_id character varying(255),
    voter_ip character varying(255),
    project_id bigint,
    user_id bigint
);

--
-- Name: project_vote_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.project_vote_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: refresh_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refresh_token (
    id uuid NOT NULL,
    expires_at timestamp(6) with time zone,
    revoked boolean NOT NULL,
    token character varying(255) NOT NULL,
    user_id bigint NOT NULL
);

--
-- Name: subscription; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscription (
    id bigint NOT NULL,
    end_date timestamp(6) without time zone,
    start_date timestamp(6) without time zone,
    status character varying(255),
    plan_id bigint,
    user_id bigint
);

--
-- Name: subscription_payment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subscription_payment (
    subscription_id bigint NOT NULL,
    payment_id bigint NOT NULL
);

--
-- Name: subscription_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.subscription_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: tag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tag (
    id bigint NOT NULL,
    created_by bigint,
    name character varying(255) NOT NULL,
    official boolean DEFAULT false NOT NULL,
    usage_count bigint DEFAULT 0 NOT NULL
);

--
-- Name: tag_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tag_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: trail; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trail (
    id bigint NOT NULL,
    creation_date timestamp(6) without time zone,
    description text,
    modified_date timestamp(6) without time zone,
    title character varying(255),
    version integer NOT NULL,
    visibility character varying(255),
    forked_from_trail_id bigint,
    project_id bigint
);

--
-- Name: trail_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trail_item (
    id bigint NOT NULL,
    annotation text,
    order_index integer NOT NULL,
    association_id bigint,
    item_id bigint,
    trail_id bigint
);

--
-- Name: trail_item_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.trail_item_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: trail_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.trail_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: upload_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.upload_record (
    id bigint NOT NULL,
    bytes bigint NOT NULL,
    created_date timestamp(6) without time zone,
    object_key character varying(255),
    project_id bigint,
    user_id bigint
);

--
-- Name: upload_record_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.upload_record_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: user_badge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_badge (
    id bigint NOT NULL,
    badge_code character varying(255),
    earned_at timestamp(6) without time zone,
    user_id bigint
);

--
-- Name: user_badge_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.user_badge_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    allow_forks boolean,
    banned boolean DEFAULT false,
    banner_url text,
    bio text,
    comments_policy character varying(255),
    created_at timestamp(6) without time zone,
    editor_tour_seen boolean,
    email character varying(255),
    email_digest_frequency character varying(255),
    email_verified boolean DEFAULT true,
    first_name character varying(255),
    image_url text,
    last_name character varying(255),
    password character varying(255),
    patreon_access_token text,
    patreon_refresh_token text,
    patreon_user_id character varying(255),
    phone character varying(255),
    role smallint,
    show_upvotes boolean,
    updated_at timestamp(6) without time zone,
    username character varying(255),
    visibility boolean,
    age integer,
    location text,
    show_age boolean,
    website text,
    birth_date date,
    selected_badge character varying(255),
    CONSTRAINT users_role_check CHECK (((role >= 0) AND (role <= 1)))
);

--
-- Name: users_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: association association_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.association
    ADD CONSTRAINT association_pkey PRIMARY KEY (id);

--
-- Name: blocked_user blocked_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.blocked_user
    ADD CONSTRAINT blocked_user_pkey PRIMARY KEY (id);

--
-- Name: comment comment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment
    ADD CONSTRAINT comment_pkey PRIMARY KEY (id);

--
-- Name: comment_report comment_report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment_report
    ADD CONSTRAINT comment_report_pkey PRIMARY KEY (id);

--
-- Name: email_verification_token email_verification_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_verification_token
    ADD CONSTRAINT email_verification_token_pkey PRIMARY KEY (id);

--
-- Name: follow follow_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.follow
    ADD CONSTRAINT follow_pkey PRIMARY KEY (id);

--
-- Name: item_content item_content_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_content
    ADD CONSTRAINT item_content_pkey PRIMARY KEY (id);

--
-- Name: item_image_reference item_image_reference_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_image_reference
    ADD CONSTRAINT item_image_reference_pkey PRIMARY KEY (id);

--
-- Name: item item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item
    ADD CONSTRAINT item_pkey PRIMARY KEY (id);

--
-- Name: moderation_log moderation_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.moderation_log
    ADD CONSTRAINT moderation_log_pkey PRIMARY KEY (id);

--
-- Name: notification notification_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT notification_pkey PRIMARY KEY (id);

--
-- Name: password_reset_token password_reset_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT password_reset_token_pkey PRIMARY KEY (id);

--
-- Name: patreon_connect_token patreon_connect_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patreon_connect_token
    ADD CONSTRAINT patreon_connect_token_pkey PRIMARY KEY (id);

--
-- Name: payment payment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment
    ADD CONSTRAINT payment_pkey PRIMARY KEY (id);

--
-- Name: pending_image_deletion pending_image_deletion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_image_deletion
    ADD CONSTRAINT pending_image_deletion_pkey PRIMARY KEY (id);

--
-- Name: plan plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan
    ADD CONSTRAINT plan_pkey PRIMARY KEY (id);

--
-- Name: project_bookmark project_bookmark_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_bookmark
    ADD CONSTRAINT project_bookmark_pkey PRIMARY KEY (id);

--
-- Name: project project_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project
    ADD CONSTRAINT project_pkey PRIMARY KEY (id);

--
-- Name: project_report project_report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_report
    ADD CONSTRAINT project_report_pkey PRIMARY KEY (id);

--
-- Name: project_snapshot project_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_snapshot
    ADD CONSTRAINT project_snapshot_pkey PRIMARY KEY (id);

--
-- Name: project_tag project_tag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_tag
    ADD CONSTRAINT project_tag_pkey PRIMARY KEY (project_id, tag_id);

--
-- Name: project_view project_view_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_view
    ADD CONSTRAINT project_view_pkey PRIMARY KEY (id);

--
-- Name: project_vote project_vote_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_vote
    ADD CONSTRAINT project_vote_pkey PRIMARY KEY (id);

--
-- Name: refresh_token refresh_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT refresh_token_pkey PRIMARY KEY (id);

--
-- Name: subscription subscription_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription
    ADD CONSTRAINT subscription_pkey PRIMARY KEY (id);

--
-- Name: tag tag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tag
    ADD CONSTRAINT tag_pkey PRIMARY KEY (id);

--
-- Name: trail_item trail_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trail_item
    ADD CONSTRAINT trail_item_pkey PRIMARY KEY (id);

--
-- Name: trail trail_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trail
    ADD CONSTRAINT trail_pkey PRIMARY KEY (id);

--
-- Name: tag uk1wdpsed5kna2y38hnbgrnhi5b; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tag
    ADD CONSTRAINT uk1wdpsed5kna2y38hnbgrnhi5b UNIQUE (name);

--
-- Name: follow uk2nmykh3xviqhkkudaafoik969; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.follow
    ADD CONSTRAINT uk2nmykh3xviqhkkudaafoik969 UNIQUE (follower_id, followed_id);

--
-- Name: project_vote uk2urj4axscjpgxweqppgx6ckj7; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_vote
    ADD CONSTRAINT uk2urj4axscjpgxweqppgx6ckj7 UNIQUE (project_id, user_id);

--
-- Name: blocked_user uk3j6l2qbrhsithles2n57iuxsu; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.blocked_user
    ADD CONSTRAINT uk3j6l2qbrhsithles2n57iuxsu UNIQUE (blocker_id, blocked_id);

--
-- Name: users uk633dde3ucv7ro08xgkrwyd52a; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk633dde3ucv7ro08xgkrwyd52a UNIQUE (patreon_user_id);

--
-- Name: project_view uk6b9umemqo7r3i1o36exqx7ll7; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_view
    ADD CONSTRAINT uk6b9umemqo7r3i1o36exqx7ll7 UNIQUE (project_id, viewer_key);

--
-- Name: subscription uk8lmcunrss2bll2ydkw4lptdnh; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription
    ADD CONSTRAINT uk8lmcunrss2bll2ydkw4lptdnh UNIQUE (plan_id);

--
-- Name: pending_image_deletion uk_pending_image_deletion_url; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pending_image_deletion
    ADD CONSTRAINT uk_pending_image_deletion_url UNIQUE (url);

--
-- Name: upload_record uk_upload_record_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.upload_record
    ADD CONSTRAINT uk_upload_record_key UNIQUE (object_key);

--
-- Name: item ukaksqu75ynt63m3j9cul9ftyip; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item
    ADD CONSTRAINT ukaksqu75ynt63m3j9cul9ftyip UNIQUE (content_id);

--
-- Name: association ukfc746v3cty59aexb9inqlyi5f; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.association
    ADD CONSTRAINT ukfc746v3cty59aexb9inqlyi5f UNIQUE (source_item_id, target_type, target_id);

--
-- Name: password_reset_token ukg0guo4k8krgpwuagos61oc06j; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT ukg0guo4k8krgpwuagos61oc06j UNIQUE (token);

--
-- Name: patreon_connect_token ukgwt3s8pjd3ba2q46odihanjkt; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patreon_connect_token
    ADD CONSTRAINT ukgwt3s8pjd3ba2q46odihanjkt UNIQUE (token);

--
-- Name: email_verification_token ukidu2ippaks8bn6vcsq62khvdu; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_verification_token
    ADD CONSTRAINT ukidu2ippaks8bn6vcsq62khvdu UNIQUE (token);

--
-- Name: project_bookmark ukkvphudq3t8fcdp2xvebhpdy4d; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_bookmark
    ADD CONSTRAINT ukkvphudq3t8fcdp2xvebhpdy4d UNIQUE (project_id, user_id);

--
-- Name: subscription_payment ukn7k8vd73pnqaqdcc310wb70q6; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_payment
    ADD CONSTRAINT ukn7k8vd73pnqaqdcc310wb70q6 UNIQUE (payment_id);

--
-- Name: user_badge ukqxua5s0fdlvh2ypwts445lmmj; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_badge
    ADD CONSTRAINT ukqxua5s0fdlvh2ypwts445lmmj UNIQUE (user_id, badge_code);

--
-- Name: users ukr43af9ap4edm43mmtq01oddj6; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT ukr43af9ap4edm43mmtq01oddj6 UNIQUE (username);

--
-- Name: refresh_token ukr4k4edos30bx9neoq81mdvwph; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT ukr4k4edos30bx9neoq81mdvwph UNIQUE (token);

--
-- Name: upload_record upload_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.upload_record
    ADD CONSTRAINT upload_record_pkey PRIMARY KEY (id);

--
-- Name: user_badge user_badge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_badge
    ADD CONSTRAINT user_badge_pkey PRIMARY KEY (id);

--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

--
-- Name: idx_association_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_association_target ON public.association USING btree (target_type, target_id);

--
-- Name: idx_blocked_user_blocked; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_blocked_user_blocked ON public.blocked_user USING btree (blocked_id);

--
-- Name: idx_comment_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_comment_project ON public.comment USING btree (project_id);

--
-- Name: idx_follow_followed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_follow_followed ON public.follow USING btree (followed_id);

--
-- Name: idx_item_image_reference_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_item_image_reference_item ON public.item_image_reference USING btree (item_id);

--
-- Name: idx_item_image_reference_url; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_item_image_reference_url ON public.item_image_reference USING btree (url);

--
-- Name: idx_item_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_item_project ON public.item USING btree (project_id);

--
-- Name: idx_notification_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_recipient ON public.notification USING btree (recipient_id);

--
-- Name: idx_notification_recipient_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_recipient_unread ON public.notification USING btree (recipient_id, read);

--
-- Name: idx_project_bookmark_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_bookmark_user ON public.project_bookmark USING btree (user_id);

--
-- Name: idx_project_description_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_description_trgm ON public.project USING gin (lower((description)::text) public.gin_trgm_ops);

--
-- Name: idx_project_forked_from; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_forked_from ON public.project USING btree (forked_from_id);

--
-- Name: idx_project_owner_visibility; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_owner_visibility ON public.project USING btree (user_id, visibility);

--
-- Name: idx_project_snapshot_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_snapshot_project ON public.project_snapshot USING btree (project_id);

--
-- Name: idx_project_title_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_title_trgm ON public.project USING gin (lower((title)::text) public.gin_trgm_ops);

--
-- Name: idx_project_visibility; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_visibility ON public.project USING btree (visibility);

--
-- Name: idx_project_vote_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_project_vote_user ON public.project_vote USING btree (user_id);

--
-- Name: idx_refresh_token_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refresh_token_user ON public.refresh_token USING btree (user_id);

--
-- Name: idx_subscription_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_subscription_user_status ON public.subscription USING btree (user_id, status);

--
-- Name: idx_tag_name_trgm; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tag_name_trgm ON public.tag USING gin (lower((name)::text) public.gin_trgm_ops);

--
-- Name: idx_trail_item_association; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trail_item_association ON public.trail_item USING btree (association_id);

--
-- Name: idx_trail_item_item; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trail_item_item ON public.trail_item USING btree (item_id);

--
-- Name: idx_trail_item_trail; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trail_item_trail ON public.trail_item USING btree (trail_id);

--
-- Name: idx_trail_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trail_project ON public.trail USING btree (project_id);

--
-- Name: idx_upload_record_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_record_project ON public.upload_record USING btree (project_id);

--
-- Name: idx_upload_record_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_record_user ON public.upload_record USING btree (user_id);

--
-- Name: idx_user_badge_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_badge_user ON public.user_badge USING btree (user_id);

--
-- Name: idx_users_email_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_users_email_unique ON public.users USING btree (email);

--
-- Name: idx_users_lower_username; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_lower_username ON public.users USING btree (lower((username)::text));

--
-- Name: trail fk4542d5ch2yr0br16qgefkpiqf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trail
    ADD CONSTRAINT fk4542d5ch2yr0br16qgefkpiqf FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: project_bookmark fk4qbm4e3mmwdw2clu8p22nr6ti; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_bookmark
    ADD CONSTRAINT fk4qbm4e3mmwdw2clu8p22nr6ti FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: project_tag fk519h89u5tkrcmyquqgr5lh3y2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_tag
    ADD CONSTRAINT fk519h89u5tkrcmyquqgr5lh3y2 FOREIGN KEY (tag_id) REFERENCES public.tag(id);

--
-- Name: blocked_user fk5whfbxxc2yiqdcanmw3l7086j; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.blocked_user
    ADD CONSTRAINT fk5whfbxxc2yiqdcanmw3l7086j FOREIGN KEY (blocker_id) REFERENCES public.users(id);

--
-- Name: subscription_payment fk66b5ph31cubuycw4uhijbjlrn; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_payment
    ADD CONSTRAINT fk66b5ph31cubuycw4uhijbjlrn FOREIGN KEY (subscription_id) REFERENCES public.subscription(id);

--
-- Name: item fk706wyq39ps5qxp4ndq7wrbk3t; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item
    ADD CONSTRAINT fk706wyq39ps5qxp4ndq7wrbk3t FOREIGN KEY (content_id) REFERENCES public.item_content(id);

--
-- Name: project_snapshot fk72vpt7glqljdqaii9uccgwxx9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_snapshot
    ADD CONSTRAINT fk72vpt7glqljdqaii9uccgwxx9 FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: password_reset_token fk83nsrttkwkb6ym0anu051mtxn; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.password_reset_token
    ADD CONSTRAINT fk83nsrttkwkb6ym0anu051mtxn FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: blocked_user fk8lh8imrdshs8qqyerok5iy1lf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.blocked_user
    ADD CONSTRAINT fk8lh8imrdshs8qqyerok5iy1lf FOREIGN KEY (blocked_id) REFERENCES public.users(id);

--
-- Name: subscription fk8lkqdq029r91j37wwfkc4k5ci; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription
    ADD CONSTRAINT fk8lkqdq029r91j37wwfkc4k5ci FOREIGN KEY (plan_id) REFERENCES public.plan(id);

--
-- Name: project_bookmark fk8oan9ub3qqq3se8ov4qnpt907; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_bookmark
    ADD CONSTRAINT fk8oan9ub3qqq3se8ov4qnpt907 FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: comment_report fk8ugevhla12t9n0uw4o0rkvnth; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment_report
    ADD CONSTRAINT fk8ugevhla12t9n0uw4o0rkvnth FOREIGN KEY (comment_id) REFERENCES public.comment(id);

--
-- Name: association fk9f3tw2o3lviv1r9344keijnar; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.association
    ADD CONSTRAINT fk9f3tw2o3lviv1r9344keijnar FOREIGN KEY (source_item_id) REFERENCES public.item(id);

--
-- Name: follow fk9o03ye7x353hojmg9iaaybem2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.follow
    ADD CONSTRAINT fk9o03ye7x353hojmg9iaaybem2 FOREIGN KEY (followed_id) REFERENCES public.users(id);

--
-- Name: user_badge fka96xbjeyiemypugmi9g8x8fiu; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_badge
    ADD CONSTRAINT fka96xbjeyiemypugmi9g8x8fiu FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: subscription_payment fkarhkav8pijw5r4cvc76f6r6yn; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription_payment
    ADD CONSTRAINT fkarhkav8pijw5r4cvc76f6r6yn FOREIGN KEY (payment_id) REFERENCES public.payment(id);

--
-- Name: comment fkb5kenf6fjka6ck0snroeb5tmh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment
    ADD CONSTRAINT fkb5kenf6fjka6ck0snroeb5tmh FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: project fkb6t9jfnhvago0anp0qmievny2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project
    ADD CONSTRAINT fkb6t9jfnhvago0anp0qmievny2 FOREIGN KEY (thumbnail_trail_id) REFERENCES public.trail(id);

--
-- Name: project_view fkbfprq2ig33gm0abjk8ugqxcxr; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_view
    ADD CONSTRAINT fkbfprq2ig33gm0abjk8ugqxcxr FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: trail_item fkbviyqubjpma2ryyy8bg6j4q0b; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trail_item
    ADD CONSTRAINT fkbviyqubjpma2ryyy8bg6j4q0b FOREIGN KEY (association_id) REFERENCES public.association(id);

--
-- Name: patreon_connect_token fkcpauu268atowrh3rtm9bbq00w; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.patreon_connect_token
    ADD CONSTRAINT fkcpauu268atowrh3rtm9bbq00w FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: comment fkde3rfu96lep00br5ov0mdieyt; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment
    ADD CONSTRAINT fkde3rfu96lep00br5ov0mdieyt FOREIGN KEY (parent_id) REFERENCES public.comment(id);

--
-- Name: project fkf1va93taog6qokr6sqmtgr93h; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project
    ADD CONSTRAINT fkf1va93taog6qokr6sqmtgr93h FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: item fkf60hnjyqgladtp0jw5o0n4e9u; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item
    ADD CONSTRAINT fkf60hnjyqgladtp0jw5o0n4e9u FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: notification fkfcyn9rsga73dqnorl7owfyl4a; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT fkfcyn9rsga73dqnorl7owfyl4a FOREIGN KEY (recipient_id) REFERENCES public.users(id);

--
-- Name: moderation_log fkhose48wft0jcokbdme3rg2td; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.moderation_log
    ADD CONSTRAINT fkhose48wft0jcokbdme3rg2td FOREIGN KEY (admin_id) REFERENCES public.users(id);

--
-- Name: notification fki35sfx0x08fonfxf2l8cp2xcp; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT fki35sfx0x08fonfxf2l8cp2xcp FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: comment fkir20vhrx08eh4itgpbfxip0s1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment
    ADD CONSTRAINT fkir20vhrx08eh4itgpbfxip0s1 FOREIGN KEY (author_id) REFERENCES public.users(id);

--
-- Name: follow fkjikg34txcxnhcky26w14fvfcc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.follow
    ADD CONSTRAINT fkjikg34txcxnhcky26w14fvfcc FOREIGN KEY (follower_id) REFERENCES public.users(id);

--
-- Name: refresh_token fkjtx87i0jvq2svedphegvdwcuy; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refresh_token
    ADD CONSTRAINT fkjtx87i0jvq2svedphegvdwcuy FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: project_tag fkk3ccabfs72wkx2008pn7tij9b; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_tag
    ADD CONSTRAINT fkk3ccabfs72wkx2008pn7tij9b FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: project_vote fkkh4q34td3lr9ttsto19xjhj4u; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_vote
    ADD CONSTRAINT fkkh4q34td3lr9ttsto19xjhj4u FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: email_verification_token fkknax5in7pcatm2uf9uyple35x; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.email_verification_token
    ADD CONSTRAINT fkknax5in7pcatm2uf9uyple35x FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: project fkmncuk1e0rce7rdhn6npdr5ykm; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project
    ADD CONSTRAINT fkmncuk1e0rce7rdhn6npdr5ykm FOREIGN KEY (forked_from_id) REFERENCES public.project(id);

--
-- Name: comment_report fkn7ue556scerw6fa5epexg2g4j; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.comment_report
    ADD CONSTRAINT fkn7ue556scerw6fa5epexg2g4j FOREIGN KEY (reporter_id) REFERENCES public.users(id);

--
-- Name: project_vote fknfb9y89e5utvca82srhmm07or; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_vote
    ADD CONSTRAINT fknfb9y89e5utvca82srhmm07or FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: project_report fkonjlf4bsxqnu9mwx6jcbu1srm; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_report
    ADD CONSTRAINT fkonjlf4bsxqnu9mwx6jcbu1srm FOREIGN KEY (project_id) REFERENCES public.project(id);

--
-- Name: trail fkoyg22463avp06oy2m3v4dhkgr; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trail
    ADD CONSTRAINT fkoyg22463avp06oy2m3v4dhkgr FOREIGN KEY (forked_from_trail_id) REFERENCES public.trail(id);

--
-- Name: trail_item fkpgh65sww8bmb2fkqqumiklhof; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trail_item
    ADD CONSTRAINT fkpgh65sww8bmb2fkqqumiklhof FOREIGN KEY (trail_id) REFERENCES public.trail(id);

--
-- Name: notification fkqhkbe4e4w4pom5rn4qm6h3imb; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT fkqhkbe4e4w4pom5rn4qm6h3imb FOREIGN KEY (actor_id) REFERENCES public.users(id);

--
-- Name: subscription fkqwd9pkhbsmapx9poug5wnnpkc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subscription
    ADD CONSTRAINT fkqwd9pkhbsmapx9poug5wnnpkc FOREIGN KEY (user_id) REFERENCES public.users(id);

--
-- Name: item_image_reference fksi499886c6jdkumcc32cnxyw4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.item_image_reference
    ADD CONSTRAINT fksi499886c6jdkumcc32cnxyw4 FOREIGN KEY (item_id) REFERENCES public.item(id);

--
-- Name: trail_item fksv7lthad6q8yhwq36qnhc2wjs; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trail_item
    ADD CONSTRAINT fksv7lthad6q8yhwq36qnhc2wjs FOREIGN KEY (item_id) REFERENCES public.item(id);

--
-- Name: project_report fktlx7gx8cwim6iqenut87vgt6v; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_report
    ADD CONSTRAINT fktlx7gx8cwim6iqenut87vgt6v FOREIGN KEY (reporter_id) REFERENCES public.users(id);

--
--
