--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: maestro_distributed_lock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.maestro_distributed_lock (
    lock_key character varying(512) NOT NULL,
    token character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    acquired_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: maestro_leader_election; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.maestro_leader_election (
    election_key character varying(512) NOT NULL,
    candidate_id character varying(255) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    acquired_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: maestro_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.maestro_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: maestro_workflow_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.maestro_workflow_event (
    id uuid NOT NULL,
    workflow_instance_id uuid NOT NULL,
    sequence_number integer NOT NULL,
    event_type character varying(50) NOT NULL,
    step_name character varying(255),
    payload jsonb,
    created_at timestamp with time zone NOT NULL,
    args_hash character varying(64)
);


--
-- Name: COLUMN maestro_workflow_event.args_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.maestro_workflow_event.args_hash IS 'SHA-256 hex of the activity arguments (ACTIVITY_COMPLETED/ACTIVITY_FAILED only); NULL = unverified';


--
-- Name: maestro_workflow_instance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.maestro_workflow_instance (
    id uuid NOT NULL,
    workflow_id character varying(255) NOT NULL,
    run_id uuid NOT NULL,
    workflow_type character varying(255) NOT NULL,
    task_queue character varying(255) NOT NULL,
    status character varying(50) NOT NULL,
    input jsonb,
    output jsonb,
    service_name character varying(255) NOT NULL,
    event_sequence integer DEFAULT 0 NOT NULL,
    started_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    version integer DEFAULT 0 NOT NULL,
    lease_epoch bigint DEFAULT 0 NOT NULL
);


--
-- Name: maestro_workflow_signal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.maestro_workflow_signal (
    id uuid NOT NULL,
    workflow_instance_id uuid,
    workflow_id character varying(255) NOT NULL,
    signal_name character varying(255) NOT NULL,
    payload jsonb,
    consumed boolean DEFAULT false NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    trace_context character varying(128),
    message_id text
);


--
-- Name: COLUMN maestro_workflow_signal.message_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.maestro_workflow_signal.message_id IS 'Producer-assigned identity of the logical signal; unique per (workflow_id, signal_name) when not NULL; NULL = no identity';


--
-- Name: maestro_workflow_timer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.maestro_workflow_timer (
    id uuid NOT NULL,
    workflow_instance_id uuid NOT NULL,
    workflow_id character varying(255) NOT NULL,
    timer_id character varying(255) NOT NULL,
    fire_at timestamp with time zone NOT NULL,
    status character varying(50) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--
-- Data for Name: maestro_distributed_lock; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: maestro_leader_election; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: maestro_schema_history; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', 'create maestro schema', 'SQL', 'V1__create_maestro_schema.sql', 1894477780, 'test', '2026-11-20 18:29:19.4045', 20, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (2, '2', 'create indexes', 'SQL', 'V2__create_indexes.sql', 1117887476, 'test', '2026-11-20 18:29:19.439885', 15, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (3, '3', 'timer lookup index', 'SQL', 'V3__timer_lookup_index.sql', -511219852, 'test', '2026-11-20 18:29:19.466647', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (4, '4', 'signal trace context', 'SQL', 'V4__signal_trace_context.sql', -444138901, 'test', '2026-11-20 18:29:19.477421', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (5, '5', 'signal received at default', 'SQL', 'V5__signal_received_at_default.sql', -1319518420, 'test', '2026-11-20 18:29:19.487451', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (6, '6', 'instance lease epoch', 'SQL', 'V6__instance_lease_epoch.sql', -1501028209, 'test', '2026-11-20 18:29:19.496178', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (7, '7', 'workflow event args hash', 'SQL', 'V7__workflow_event_args_hash.sql', 1141881341, 'test', '2026-11-20 18:29:19.505958', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (8, '8', 'retention indexes', 'SQL', 'V8__retention_indexes.sql', 1576234141, 'test', '2026-11-20 18:29:19.515412', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (9, '9', 'signal message id', 'SQL', 'V9__signal_message_id.sql', 168428632, 'test', '2026-11-20 18:29:19.528772', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (10, '100', 'maestro lock postgres', 'SQL', 'V100__maestro_lock_postgres.sql', 1037217973, 'test', '2026-11-20 18:29:19.540169', 7, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (11, '101', 'recovery scan index', 'SQL', 'V101__recovery_scan_index.sql', 9197330, 'test', '2026-11-20 18:29:19.553209', 2, true);


--
-- Data for Name: maestro_workflow_event; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('24d31dcb-2291-4865-af46-bce665860a13', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-completed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-20 16:29:19.796656+00', '61120492bde41a623abae6da6acef1477c1c44ac5ccf3e854f20c04625ce8fad');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b575b816-37b1-4ba5-b97b-5b7a7c1274f5', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-20T16:29:19.825420Z"', '2026-11-20 16:29:19.825662+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dc6704b6-e0a8-47f1-9623-a88b4d4aab7c', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"9ef91b25-bdc8-4b98-8be5-e7d395601106"', '2026-11-20 16:29:19.845461+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b2c9a21d-b445-407f-8711-9051709305ec', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-20 16:29:19.867543+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5c0408ff-3a56-4ac5-ae8c-f4e05ee9ba33', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-20 16:29:19.886993+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ec75451d-2879-4551-8223-656a81f5ad26', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-20 16:29:19.913658+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6ceca27b-618e-483d-9400-31f78c7fd1ce', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-20 16:29:19.913855+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d18d7c52-2c79-40e5-a376-40e067a34160', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-completed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:19.94286+00', '2ecf14aafdc0c4016c28daa53e863728959afca02f9b5503a122173ab2f30a04');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dc0125d3-16ca-45cd-9af0-40da172c00c0', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-20 16:29:19.970775+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('809e7e00-614e-4e81-9c08-d0d3f13d6aa6', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-20 16:29:21.083417+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('136eb73c-f9a9-4de0-8695-5a7b1f9bfc98', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-20T16:29:22.174225Z", "signalName": "express"}', '2026-11-20 16:29:21.183379+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b2eb8ac8-500e-4d81-a662-bd18d2bafad8', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-20 16:29:22.236669+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('02a2c93a-46a6-476b-ac9e-d58445a8e851', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-20 16:29:22.290957+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e5718efb-1d88-4599-9d03-3a626ee699f0', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-20 16:29:22.381945+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c8715917-fca5-4357-9bec-5faeabe17953', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-17T16:29:22.435332Z", "signalName": "approval"}', '2026-11-20 16:29:22.444266+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('795cb297-6389-4b03-8198-a97571a95e30', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-20 16:29:22.516309+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('21ae4cbb-8507-428f-b889-c3b1706b4c3e', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8010, 'ACTIVITY_COMPLETED', 'seed-order.charge', '{"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}', '2026-11-20 16:29:22.569406+00', '4f7957db6f7b556032ab536a356340cca8b51fb6d1726e046db09ede5858e8e5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7c935763-ac65-4dde-9128-b6909cefdf99', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8011, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:note', '"note for seed-completed"', '2026-11-20 16:29:22.594543+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('aa48f3d1-c9bf-436c-b4ac-f0ce03704d9d', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 8012, 'WORKFLOW_COMPLETED', NULL, '{"note": "note for seed-completed", "token": "9ef91b25-bdc8-4b98-8be5-e7d395601106", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-20T16:29:19.825420Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', '2026-11-20 16:29:22.61503+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e776a31e-af8b-40b9-8b0a-061b8499eb28', '60290201-0404-40b3-8304-c73669774370', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-approval", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-20 16:29:22.737488+00', 'b6195227c62ead98ed1b3b177bfa8f2bb53caca058a40f3326a52ea2b6c24a86');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('649a0e98-e46e-45ea-bffb-5ffee7dcb312', '60290201-0404-40b3-8304-c73669774370', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-20T16:29:22.759960Z"', '2026-11-20 16:29:22.760014+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0e5f4925-4575-441f-8b36-298b803ddac8', '60290201-0404-40b3-8304-c73669774370', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"a41bda14-fbb4-4302-83e7-86c0066709b4"', '2026-11-20 16:29:22.819296+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2024fd34-9d33-442a-bf62-748f2a230b9e', '60290201-0404-40b3-8304-c73669774370', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-20 16:29:22.850518+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c8baf500-ea8e-4b81-830e-1693a5ee94b1', '60290201-0404-40b3-8304-c73669774370', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-20 16:29:22.878464+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2a71593d-75e7-4be1-97c5-a9b944ac28a1', '60290201-0404-40b3-8304-c73669774370', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-20 16:29:22.908964+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e44d597d-8989-45fa-8f26-82a98b5147d5', '60290201-0404-40b3-8304-c73669774370', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-20 16:29:22.909124+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ddd8e61e-b8bb-4610-bb84-7e002ee8367c', '60290201-0404-40b3-8304-c73669774370', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-approval", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:22.939232+00', 'c2607e49b4ab277032d58069d85787ad440a6041aefa57dab52b717a1cc9b16f');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fc842787-fa0c-4906-afd0-d34b7155c2bd', '60290201-0404-40b3-8304-c73669774370', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-20 16:29:22.971009+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4ac994b6-ce2e-4f86-8f9a-d71559fe68af', '60290201-0404-40b3-8304-c73669774370', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-20 16:29:24.047147+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7fb7504b-74c2-4ef0-8014-51293e508880', '60290201-0404-40b3-8304-c73669774370', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-20T16:29:25.095874Z", "signalName": "express"}', '2026-11-20 16:29:24.102877+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8647ba00-c157-47f7-ac62-c04464c41eb4', '60290201-0404-40b3-8304-c73669774370', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-20 16:29:25.122229+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('98da7f44-fb09-497f-a006-46f55848dfe1', '60290201-0404-40b3-8304-c73669774370', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-20 16:29:25.173305+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2c4325af-a609-4787-a028-13e2adc5e765', '60290201-0404-40b3-8304-c73669774370', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-20 16:29:25.227792+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b48db430-850a-4538-b8e3-9a7df709fdbe', '60290201-0404-40b3-8304-c73669774370', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-17T16:29:25.277072Z", "signalName": "approval"}', '2026-11-20 16:29:25.28484+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('89f79cc3-6a44-4d09-8a41-d7a3bf58089b', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-terminated", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-20 16:29:25.567765+00', '785d82a01f4a3938f2f8d5ae7601a6d490bf7cfd73d10ee4ce728ea649d991cb');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('da0a876e-4105-4a4d-853d-0dc7e58a78c9', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-20T16:29:25.587269Z"', '2026-11-20 16:29:25.587325+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bb22f4aa-f307-460d-94c0-b743cf0a591a', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"e2b453b6-163f-4c47-a497-c80e40598447"', '2026-11-20 16:29:25.608322+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8de9e91c-222d-4a2b-9256-06da7ffc2d63', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-20 16:29:25.626962+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b7fe674c-8024-4634-ba97-617e317a4ae0', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-20 16:29:25.651473+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('806cce44-b56e-4e0e-90b5-06afcdb761a1', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-20 16:29:25.670718+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('63f27ab6-843f-4591-bf4c-a1a2c87e5b64', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-20 16:29:25.670715+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('604a0852-3d84-4c14-b793-ba4cdeb92052', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-terminated", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:25.690719+00', 'aaf06686d0cc7c8cba8970dc79c375fc1ce1be0e307c7e044520e11bbf08b1bc');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('75e3f404-6b48-45c7-9bd6-651228b8de45', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-20 16:29:25.715834+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('106d2e43-5270-473d-a308-97f8bc9ece19', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-20 16:29:26.811549+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('aa3b4ce6-742c-4ef8-a2f8-b4375e2bde87', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-20T16:29:27.858137Z", "signalName": "express"}', '2026-11-20 16:29:26.86539+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dc7cd886-561d-4da9-9d4d-9845a1789c84', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-20 16:29:27.897695+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5d456b6f-de57-4239-92bd-7fb7d5cef42b', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-20 16:29:27.941332+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('eb659015-0c18-4e02-9edc-5410fdf516ad', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-20 16:29:28.068394+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b69ccf4b-35ec-41eb-ac79-77fea0c2de6a', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-17T16:29:28.126104Z", "signalName": "approval"}', '2026-11-20 16:29:28.137768+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7b6190d9-8612-4d98-9bd6-a82ca063789e', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-crashed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-20 16:29:28.499276+00', 'e00fde43b12ebd50fb69f3fe435e050d9a6300f7fc0a13f99bebde6e91498fa5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7b532fb7-abff-4717-b6e1-3a8e135734be', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-20T16:29:28.516404Z"', '2026-11-20 16:29:28.516453+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('34b2399c-5056-4465-bad7-4fc22d50f564', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"f6361566-8fe5-436b-b9ce-4718d2e1f82b"', '2026-11-20 16:29:28.532227+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a636e4da-168f-4154-b8b1-4d2e1103debc', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-20 16:29:28.549225+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('357140f6-9080-4686-9747-b456c7ee2d51', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-20 16:29:28.564328+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2083c05a-dc87-4c09-9bc6-a5640b570081', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-20 16:29:28.58169+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('42c7b5a1-7843-46e2-81b3-de4755bfa0e4', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-20 16:29:28.58169+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('757e1e2d-b2f9-46b3-98ad-8c5bb03d08a8', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-crashed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:28.601679+00', '9afaaca3ce7e4299920c56b10762cde56e24090ad65cc2266e61c3d248a41109');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('22f619dd-8d19-430d-a849-72c5f017c10d', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-20 16:29:28.626459+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d0b4ee35-19a4-4a08-a803-357ca002ff4b', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-20 16:29:29.81593+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3c885896-7e32-4327-b31c-b95e1af232df', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-20T16:29:30.875922Z", "signalName": "express"}', '2026-11-20 16:29:29.913954+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f6a6ca9f-9160-4f95-90bc-1547d12df621', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-20 16:29:30.923309+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2eeed809-73f3-49b7-8d06-091ce7c24dd9', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-20 16:29:30.976639+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3ef359ed-498f-4c8a-93cf-ef3c2a2b498e', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-20 16:29:31.024776+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7f8faea7-80d0-4fac-9df8-dd8621c67be8', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-17T16:29:31.084606Z", "signalName": "approval"}', '2026-11-20 16:29:31.095169+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('da397f38-ff8a-44c5-98aa-be56e57daa30', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-20 16:29:31.130681+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2fb1f735-08e3-4cc2-bad9-13a177c2e216', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"plain"', '2026-11-20 16:29:31.273069+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('739e9a18-980e-48ab-ad47-e2d93437b725', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"plain"', '2026-11-20 16:29:31.28967+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5748af76-f7f7-4fe5-ade3-19f0e61681f1', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for plain", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:31.305516+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ede9f209-e11b-47db-970f-a0bc3c4b53c1', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-20 16:29:31.339396+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4f366a54-d295-4452-85d1-7499078b87d7', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-20 16:29:31.378349+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('68f1d00b-1305-4dae-8f9b-16eaecc44dc9', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-20 16:29:31.386039+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('20c3f43e-d9f7-43ec-9f76-74bb7de4b16f', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-20 16:29:31.414407+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7bf11ab1-16a8-467b-9431-755500dd9efa', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-20 16:29:31.421419+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bb883795-dbe3-43e6-a85f-cf6f9cf401ce', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', NULL, '2026-11-20 16:29:31.434273+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8155f498-ff58-4702-a3e0-cb8568d1d9da', '8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-20 16:29:31.451329+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2e577b71-64eb-4796-bc25-8cf5eb702393', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"fail-refund:x"', '2026-11-20 16:29:31.540789+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5bb01b05-1a7d-4759-bfb4-fa11a871c836', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"fail-refund:x"', '2026-11-20 16:29:31.556074+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9880f6ff-bed2-4164-bdd5-78842187177b', 'ea29d965-8d12-45d6-957d-f0de5960629b', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-20 16:29:34.046972+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0da13e90-4444-4a9a-a607-df66da31a765', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:31.572261+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('314c9cb1-4ab0-4a74-a501-12b6dd38e9b1', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-20 16:29:31.610261+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1b345348-69b2-403c-9b6d-c55f98f4abe4', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 5001, 'ACTIVITY_FAILED', 'seed-saga.refund', '{"message": "refund rejected for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:31.645738+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e78f28af-87b8-4ad4-adc8-1757489b6b9c', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 5000, 'COMPENSATION_STEP_FAILED', 'seed-saga.refund', '{"message": "Activity ''seed-saga.refund'' failed in workflow ''seed-saga-compensation-failed'': refund rejected for fail-refund:x", "stepName": "seed-saga.refund", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-20 16:29:31.656667+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ff19600a-34af-4a1a-93f4-117f45cddf66', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-20 16:29:31.689807+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1b66f73d-1e95-40ab-b333-f1511175d3dd', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-20 16:29:31.697202+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3b33fe5d-8f9c-4bb4-ab86-7a591ee59f50', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', '{"failedSteps": ["seed-saga.refund"], "totalCompensations": 2}', '2026-11-20 16:29:31.71133+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f0cbb2c1-092e-40ba-ac22-e80d4823d544', '5ec2cf40-583b-4493-b58a-e3da5e62a782', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-20 16:29:31.727429+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4d8b7fc2-b55d-493b-b671-5ad313ec4243', '00f74aa6-c8e3-4232-add2-cc2ee0dfcc4b', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"crash-compensation"', '2026-11-20 16:29:31.811758+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1001f2f6-cd7d-4e41-8a7d-0168513ee585', '00f74aa6-c8e3-4232-add2-cc2ee0dfcc4b', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"crash-compensation"', '2026-11-20 16:29:31.829042+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7c016af2-dca4-4521-9ef0-af82901ca4ac', '00f74aa6-c8e3-4232-add2-cc2ee0dfcc4b', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for crash-compensation", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:31.845765+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('91d4cd8e-d585-48fd-b360-7736f6408177', '00f74aa6-c8e3-4232-add2-cc2ee0dfcc4b', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-20 16:29:31.878933+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a102ac4b-a0e9-4ff3-8ec8-1d7c458831b9', '00f74aa6-c8e3-4232-add2-cc2ee0dfcc4b', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-20 16:29:31.910442+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e03d8251-49b9-4b4e-83b0-2e95a92de596', '00f74aa6-c8e3-4232-add2-cc2ee0dfcc4b', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-20 16:29:31.917818+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8c424f6c-36bf-4d6f-9ced-c01538006265', 'b876efd5-d3e9-4ac0-b900-7381bef842f2', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:32.495795+00', 'ae55ac050582d8056dcfc24a102fc318460211527edd40f09779fa2b2de4f2ec');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('57a6da8c-e9c9-46cf-8344-81f1a285b342', 'b876efd5-d3e9-4ac0-b900-7381bef842f2', 2, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-20 16:29:32.518764+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bf773a7e-68f2-4d96-9625-ff2d61a17754', 'c89646a6-c065-4f9f-b4b6-d483e1d3c4c7', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:32.708198+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f798d8b7-8f05-4e02-841b-ca159b9c4016', 'c89646a6-c065-4f9f-b4b6-d483e1d3c4c7', 2, 'ACTIVITY_COMPLETED', 'seed-failing.alert', '"alerted:seed-alert-failed"', '2026-11-20 16:29:32.726633+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('548add9e-bb3a-4963-bf8f-07565bd26573', 'c89646a6-c065-4f9f-b4b6-d483e1d3c4c7', 3, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-20 16:29:32.748631+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('260c05b2-4037-4dd4-8aa6-156697e7a5ca', '6ebfc904-3223-4521-8543-9fc3b058690f', 1, 'ACTIVITY_COMPLETED', 'seed-typed.exactAmount', '12345678901234567890.123456789', '2026-11-20 16:29:32.864412+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e49f9f63-08a4-439e-824f-5b561aa62699', '409d67b8-dd22-47d7-87c6-3bdf7dac6762', 1, 'ACTIVITY_COMPLETED', 'seed-typed.localStamp', '"2026-11-12T10:00:00+02:00"', '2026-11-20 16:29:32.885325+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9be307b0-2a08-494b-8564-719cc6621238', 'e0f24df9-70fe-4fbf-979c-1dad4cac655d', 1, 'ACTIVITY_COMPLETED', 'seed-typed.tags', '["gamma", "beta", "delta", "alpha"]', '2026-11-20 16:29:32.905227+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3746b909-f70c-4955-ab52-9071b0106ec4', '6ebfc904-3223-4521-8543-9fc3b058690f', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-17T16:29:32.913349Z", "signalName": "go"}', '2026-11-20 16:29:32.925454+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('02c2b635-21ac-4a57-8d2a-d59ea830e657', '409d67b8-dd22-47d7-87c6-3bdf7dac6762', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-17T16:29:32.925512Z", "signalName": "go"}', '2026-11-20 16:29:32.933855+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7d55ddf7-33cc-4d1d-bfc3-089bdbae985b', 'e0f24df9-70fe-4fbf-979c-1dad4cac655d', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-17T16:29:32.945325Z", "signalName": "go"}', '2026-11-20 16:29:32.955012+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('643120f7-6cd6-43ed-94e3-40f1b2d84fcd', 'b4329264-768a-4b24-bf05-31d6b63eb09e', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"parked"', '2026-11-20 16:29:33.649388+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('37997919-5ae7-47e8-9923-366f6814e9ac', 'b4329264-768a-4b24-bf05-31d6b63eb09e', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"parked"', '2026-11-20 16:29:33.674152+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('52c43f8f-be43-4e9e-b4fe-9fd6e319a1f5', 'b4329264-768a-4b24-bf05-31d6b63eb09e', 3, 'TIMER_SCHEDULED', '$maestro:awaitSignal:ship-now', '{"timeout": "PT87600H", "timerId": "await-signal-3", "deadline": "2036-11-17T16:29:33.712829Z", "signalName": "ship-now"}', '2026-11-20 16:29:33.723592+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0b81a985-5e42-44cd-be80-2e8d16aad3c7', 'ea29d965-8d12-45d6-957d-f0de5960629b', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-timer", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-20 16:29:33.965744+00', '558ccdf6e173c89faa47667cf30f6a9d89ab597ce095afe18363ca028ba5882d');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9671d530-1492-47b6-9242-096b64269960', 'ea29d965-8d12-45d6-957d-f0de5960629b', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-20T16:29:33.983623Z"', '2026-11-20 16:29:33.983683+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6aef6a99-57f6-4149-8fda-a2c8f3e27de2', 'ea29d965-8d12-45d6-957d-f0de5960629b', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"18a7300c-7f01-411c-804d-5a9a8aaf4357"', '2026-11-20 16:29:33.999048+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a6a2cf4b-c4f9-4707-b6b6-dff5f383d437', 'ea29d965-8d12-45d6-957d-f0de5960629b', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-20 16:29:34.012926+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5909791b-ace6-44d0-a134-014b6b673fb0', 'ea29d965-8d12-45d6-957d-f0de5960629b', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-20 16:29:34.02816+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9c2a48af-1b85-4d8a-96e5-922e7c7b82cc', 'ea29d965-8d12-45d6-957d-f0de5960629b', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-20 16:29:34.045781+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9c9c569f-71c6-4eae-8579-75a1df4188b9', 'ea29d965-8d12-45d6-957d-f0de5960629b', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-timer", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:34.062102+00', 'd0eae109e2ce1eb172aa9c675c463121146e996ead38dee0886c8252d36a77ac');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6833000d-4577-4bca-9c44-519e90ffd961', 'ea29d965-8d12-45d6-957d-f0de5960629b', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT10M"}', '2026-11-20 16:29:34.088654+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0567727e-901f-40c9-a7ee-812624f60f53', '0efe222a-0c4b-469c-87da-f442032434c3', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-express", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-20 16:29:34.187028+00', '354fbdbc6a8e2e95b3863f5a5582854a39445fa66416df79e67c44532ca4e457');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('023b6439-ad41-4578-a3e0-d0243cc7abf3', '0efe222a-0c4b-469c-87da-f442032434c3', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-20T16:29:34.207272Z"', '2026-11-20 16:29:34.207344+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ab665d46-e3f1-41d3-ab65-18a205402ba9', '0efe222a-0c4b-469c-87da-f442032434c3', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"70a625d6-9b2b-4fca-98a8-5b060e2aa525"', '2026-11-20 16:29:34.222795+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a0acce41-0649-40d6-839a-107d68098d12', '0efe222a-0c4b-469c-87da-f442032434c3', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-20 16:29:34.237898+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fa009ba3-89dd-4c64-ba44-0574c51a10a4', '0efe222a-0c4b-469c-87da-f442032434c3', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-20 16:29:34.251144+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('074921ae-71cf-4c69-80b0-a4a197777576', '0efe222a-0c4b-469c-87da-f442032434c3', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-20 16:29:34.272285+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9a1511bc-1126-4dea-b2ce-86021e466053', '0efe222a-0c4b-469c-87da-f442032434c3', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-20 16:29:34.273215+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5c3b17bd-5374-481e-8dc5-96ac7e56bd69', '0efe222a-0c4b-469c-87da-f442032434c3', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-express", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_2.SeededHistoryWorkflows$SeedFailure"}', '2026-11-20 16:29:34.290755+00', 'e487ac6f64544e0840cd7f51f5490bd78e0b48293e7d15f91f57aa2ca64c9100');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b1435d22-3f09-4bbb-8056-4937d6441212', '0efe222a-0c4b-469c-87da-f442032434c3', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-20 16:29:34.315977+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1b4c89a0-3dea-456a-ab91-39bbe2f5cc13', '0efe222a-0c4b-469c-87da-f442032434c3', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-20 16:29:35.3463+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('30c4dfc3-09cd-4fe6-bdc4-3e8a38f1447b', '0efe222a-0c4b-469c-87da-f442032434c3', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT10M", "timerId": "await-signal-8004", "deadline": "2026-11-20T16:39:35.406419Z", "signalName": "express"}', '2026-11-20 16:29:35.413492+00', NULL);


--
-- Data for Name: maestro_workflow_instance; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('b4a9f01a-3f7d-4aa2-89d9-424587562464', 'seed-completed', 'cf4ba061-19c3-4b92-a11c-9cfe79b64b94', 'SeedOrderWorkflow', 'default', 'COMPLETED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-completed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"note": "note for seed-completed", "token": "9ef91b25-bdc8-4b98-8be5-e7d395601106", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-20T16:29:19.825420Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', 'upgrade-fixture', 8011, '2026-11-20 16:29:19.70189+00', '2026-11-20 16:29:22.61503+00', '2026-11-20 16:29:22.61503+00', 9, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('60290201-0404-40b3-8304-c73669774370', 'seed-parked-approval', '89056705-33a7-4197-acc7-d8f1137bf224', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-approval", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-20 16:29:22.706562+00', NULL, '2026-11-20 16:29:25.301948+00', 7, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('6dc4fc4d-50fc-4452-9769-85b254217d6c', 'seed-terminated', '181a6ae3-804c-4ef0-9ff0-be7568d4634b', 'SeedOrderWorkflow', 'default', 'TERMINATED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-terminated", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"reason": "seeded termination", "terminatedBy": "admin"}', 'upgrade-fixture', 0, '2026-11-20 16:29:25.533011+00', '2026-11-20 16:29:28.383637+00', '2026-11-20 16:29:28.383637+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 'seed-crashed', '59973020-877f-4202-a2cd-d0ca3c1360fb', 'SeedOrderWorkflow', 'default', 'RUNNING', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-crashed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-20 16:29:28.468195+00', NULL, '2026-11-20 16:29:31.156596+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('8ddf4dcf-0c4b-4784-b7b1-0bfb3cb0eed9', 'seed-saga-compensated', '273b56a3-010f-48ff-8ddc-7c4a59a209ce', 'SeedSagaWorkflow', 'default', 'FAILED', '"plain"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-20 16:29:31.244558+00', '2026-11-20 16:29:31.451329+00', '2026-11-20 16:29:31.451329+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('5ec2cf40-583b-4493-b58a-e3da5e62a782', 'seed-saga-compensation-failed', '956c4e3a-0255-46ea-a956-1a3f5c560194', 'SeedSagaWorkflow', 'default', 'FAILED', '"fail-refund:x"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-20 16:29:31.516459+00', '2026-11-20 16:29:31.727429+00', '2026-11-20 16:29:31.727429+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('00f74aa6-c8e3-4232-add2-cc2ee0dfcc4b', 'seed-compensating-crashed', 'a8d274ac-0d9f-44f8-ba06-0e22ce68caef', 'SeedSagaWorkflow', 'default', 'COMPENSATING', '"crash-compensation"', NULL, 'upgrade-fixture', 0, '2026-11-20 16:29:31.786549+00', NULL, '2026-11-20 16:29:31.869628+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('b876efd5-d3e9-4ac0-b900-7381bef842f2', 'seed-activity-failed', '8be80dc4-8433-41f7-99a5-9a43ba2f5e6f', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-activity-failed", "alertBeforeRethrow": false}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 1, '2026-11-20 16:29:32.299176+00', '2026-11-20 16:29:32.518764+00', '2026-11-20 16:29:32.518764+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('c89646a6-c065-4f9f-b4b6-d483e1d3c4c7', 'seed-alert-failed', 'a7128596-bc16-4a08-91ea-53a398156a3d', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-alert-failed", "alertBeforeRethrow": true}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 2, '2026-11-20 16:29:32.577744+00', '2026-11-20 16:29:32.748631+00', '2026-11-20 16:29:32.748631+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('6ebfc904-3223-4521-8543-9fc3b058690f', 'seed-wp1-decimal', 'd0e95c97-35d5-46a9-ac7b-863e8de053cc', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "DECIMAL"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-20 16:29:32.833183+00', NULL, '2026-11-20 16:29:32.944926+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('409d67b8-dd22-47d7-87c6-3bdf7dac6762', 'seed-wp1-offset', 'b331bb78-fa84-447c-ae07-12782995d2b5', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "OFFSET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-20 16:29:32.856547+00', NULL, '2026-11-20 16:29:32.957308+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('e0f24df9-70fe-4fbf-979c-1dad4cac655d', 'seed-wp1-set', '6bb4b0b0-ea25-4921-8e7f-ac67cd929180', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "SET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-20 16:29:32.876446+00', NULL, '2026-11-20 16:29:32.978253+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('b4329264-768a-4b24-bf05-31d6b63eb09e', 'seed-saga-parked', 'cb1a5f32-cdfc-4c8e-bb60-08da3320dad0', 'SeedParkedSagaWorkflow', 'default', 'WAITING_SIGNAL', '"parked"', NULL, 'upgrade-fixture-saga', 0, '2026-11-20 16:29:33.587423+00', NULL, '2026-11-20 16:29:33.740803+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('ea29d965-8d12-45d6-957d-f0de5960629b', 'seed-parked-timer', '295a2e1a-90da-4149-ae52-7baa89762faf', 'SeedOrderWorkflow', 'default', 'WAITING_TIMER', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-timer", "napMillis": 600000, "expressWaitMillis": 1000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-20 16:29:33.940907+00', NULL, '2026-11-20 16:29:34.109547+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('0efe222a-0c4b-469c-87da-f442032434c3', 'seed-parked-express', '768e374e-1d41-40e8-a957-a38e3838a07e', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-express", "napMillis": 1000, "expressWaitMillis": 600000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-20 16:29:34.160853+00', NULL, '2026-11-20 16:29:35.429221+00', 3, 0);


--
-- Data for Name: maestro_workflow_signal; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('5e5c6b76-58fe-4d68-b23d-8b6f8c48f5e7', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 'seed-completed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-20 16:29:22.496037+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('cd462d02-2121-49c9-82c6-1b6864a28e79', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 'seed-completed', 'note', '"note for seed-completed"', true, '2026-11-20 16:29:19.78398+00', NULL, 'client:seed-completed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('15e85e95-ca65-4d17-9e9b-f1cda7ef3d9c', '60290201-0404-40b3-8304-c73669774370', 'seed-parked-approval', 'note', '"note for seed-parked-approval"', false, '2026-11-20 16:29:22.747756+00', NULL, 'client:seed-parked-approval:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('2dabbb84-1f05-4993-b4f9-89d90cc20675', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 'seed-terminated', 'note', '"note for seed-terminated"', false, '2026-11-20 16:29:25.576602+00', NULL, 'client:seed-terminated:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('df7b87a5-8cc6-45b1-ac8f-7173e653af19', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 'seed-crashed', 'note', '"note for seed-crashed"', false, '2026-11-20 16:29:28.506667+00', NULL, 'client:seed-crashed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('190d43f5-d788-4406-a565-3ff5c501649e', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 'seed-crashed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-20 16:29:31.126895+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('8579edb0-025d-46ab-94d8-a0f206ff1bfb', NULL, 'seed-orphan-target', 'approval', '{"level": 1, "approver": "capture"}', false, '2026-11-20 16:29:32.812614+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('ad636559-170d-4e8e-a7aa-118a6f51b153', NULL, 'seed-orphan-target', 'note', '"note for seed-orphan-target"', false, '2026-11-20 16:29:32.830802+00', NULL, 'client:seed-orphan-target:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('0e606548-5881-4741-beb9-3e4eba71f2f3', 'ea29d965-8d12-45d6-957d-f0de5960629b', 'seed-parked-timer', 'note', '"note for seed-parked-timer"', false, '2026-11-20 16:29:33.973064+00', NULL, 'client:seed-parked-timer:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('b47b2641-5e89-4f79-ba63-7e1c215d6f47', '0efe222a-0c4b-469c-87da-f442032434c3', 'seed-parked-express', 'note', '"note for seed-parked-express"', false, '2026-11-20 16:29:34.195566+00', NULL, 'client:seed-parked-express:note');


--
-- Data for Name: maestro_workflow_timer; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('dad25b5a-9922-46a5-8765-0f6f2d2ef303', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 'seed-completed', 'sleep-8002', '2026-11-20 16:29:20.961538+00', 'FIRED', '2026-11-20 16:29:19.961753+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('0397b71c-2b36-44c6-b17b-34d908777032', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 'seed-completed', 'await-signal-8004', '2026-11-20 16:29:22.174225+00', 'FIRED', '2026-11-20 16:29:21.174263+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('b5860e77-b2e4-4ef6-a580-9e4359f7df07', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 'seed-completed', 'sleep-8006', '2026-11-21 16:29:22.28174+00', 'CANCELLED', '2026-11-20 16:29:22.281745+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('bea57483-a5e8-45b6-86df-60210b78c379', 'b4a9f01a-3f7d-4aa2-89d9-424587562464', 'seed-completed', 'await-signal-8008', '2036-11-17 16:29:22.435332+00', 'CANCELLED', '2026-11-20 16:29:22.435349+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('ad3f75c1-0e04-464e-bfe9-0fd658d2d378', '60290201-0404-40b3-8304-c73669774370', 'seed-parked-approval', 'sleep-8002', '2026-11-20 16:29:23.961092+00', 'FIRED', '2026-11-20 16:29:22.961098+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('6ff9b361-b01e-4527-a731-bd8ff34b98e3', '60290201-0404-40b3-8304-c73669774370', 'seed-parked-approval', 'await-signal-8004', '2026-11-20 16:29:25.095874+00', 'FIRED', '2026-11-20 16:29:24.095895+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('21a12d0f-9b2a-4ef6-9049-ca691fe834f9', '60290201-0404-40b3-8304-c73669774370', 'seed-parked-approval', 'sleep-8006', '2026-11-21 16:29:25.164916+00', 'CANCELLED', '2026-11-20 16:29:25.16492+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('4754ede0-ee8c-491e-9bd1-66531ddbc134', '60290201-0404-40b3-8304-c73669774370', 'seed-parked-approval', 'await-signal-8008', '2036-11-17 16:29:25.277072+00', 'PENDING', '2026-11-20 16:29:25.277096+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('fb4bb5de-a3f0-4a12-9538-549cd2d591fa', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 'seed-terminated', 'sleep-8002', '2026-11-20 16:29:26.708362+00', 'FIRED', '2026-11-20 16:29:25.708367+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('033a6a4f-8d35-4df6-83c5-f887bbb841c2', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 'seed-terminated', 'await-signal-8004', '2026-11-20 16:29:27.858137+00', 'FIRED', '2026-11-20 16:29:26.858151+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('bd238de3-8e78-4d92-a4d4-8e65f2a23373', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 'seed-terminated', 'sleep-8006', '2026-11-21 16:29:27.934165+00', 'CANCELLED', '2026-11-20 16:29:27.934169+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('2af0b23c-8725-421c-afa2-48f256d1019b', '6dc4fc4d-50fc-4452-9769-85b254217d6c', 'seed-terminated', 'await-signal-8008', '2036-11-17 16:29:28.126104+00', 'PENDING', '2026-11-20 16:29:28.126136+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3cae0c98-df38-4f94-b8cb-3baa3cbb6778', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 'seed-crashed', 'sleep-8002', '2026-11-20 16:29:29.618737+00', 'FIRED', '2026-11-20 16:29:28.618742+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3833379b-c3c9-4ce6-b56d-112eb92f806d', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 'seed-crashed', 'await-signal-8004', '2026-11-20 16:29:30.875922+00', 'FIRED', '2026-11-20 16:29:29.875944+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('b5e80ffa-2974-4f46-b09f-c18346130f68', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 'seed-crashed', 'sleep-8006', '2026-11-21 16:29:30.967661+00', 'CANCELLED', '2026-11-20 16:29:30.967668+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('8f2d9b97-f9f4-4cb8-9ab8-997d12cf379c', 'f3ba6a2b-0d09-4b03-9da4-84b140b8ec02', 'seed-crashed', 'await-signal-8008', '2036-11-17 16:29:31.084606+00', 'CANCELLED', '2026-11-20 16:29:31.084623+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('7adf6bc4-3b9c-411c-b5b9-87cbe543ccdf', '6ebfc904-3223-4521-8543-9fc3b058690f', 'seed-wp1-decimal', 'await-signal-2', '2036-11-17 16:29:32.913349+00', 'PENDING', '2026-11-20 16:29:32.91337+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('53e208bb-f639-40d3-86f8-7d39eb97ba98', '409d67b8-dd22-47d7-87c6-3bdf7dac6762', 'seed-wp1-offset', 'await-signal-2', '2036-11-17 16:29:32.925512+00', 'PENDING', '2026-11-20 16:29:32.925524+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('0e2e8df0-e6af-4de3-b8a0-606ec35ac00b', 'e0f24df9-70fe-4fbf-979c-1dad4cac655d', 'seed-wp1-set', 'await-signal-2', '2036-11-17 16:29:32.945325+00', 'PENDING', '2026-11-20 16:29:32.945345+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('c292546b-b154-457d-870d-332ae68c850e', 'b4329264-768a-4b24-bf05-31d6b63eb09e', 'seed-saga-parked', 'await-signal-3', '2036-11-17 16:29:33.712829+00', 'PENDING', '2026-11-20 16:29:33.712851+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('949fb172-3957-43ae-896c-8056fbbbbbcf', 'ea29d965-8d12-45d6-957d-f0de5960629b', 'seed-parked-timer', 'sleep-8002', '2026-11-20 16:39:34.081478+00', 'PENDING', '2026-11-20 16:29:34.081484+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('97e2977a-0d37-4cc9-90ae-f1788b19fe9a', '0efe222a-0c4b-469c-87da-f442032434c3', 'seed-parked-express', 'sleep-8002', '2026-11-20 16:29:35.308044+00', 'FIRED', '2026-11-20 16:29:34.30805+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3648cf23-0775-4f65-982b-b97e6df32290', '0efe222a-0c4b-469c-87da-f442032434c3', 'seed-parked-express', 'await-signal-8004', '2026-11-20 16:39:35.406419+00', 'PENDING', '2026-11-20 16:29:35.406437+00');


--
-- Name: maestro_distributed_lock maestro_distributed_lock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_distributed_lock
    ADD CONSTRAINT maestro_distributed_lock_pkey PRIMARY KEY (lock_key);


--
-- Name: maestro_leader_election maestro_leader_election_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_leader_election
    ADD CONSTRAINT maestro_leader_election_pkey PRIMARY KEY (election_key);


--
-- Name: maestro_schema_history maestro_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_schema_history
    ADD CONSTRAINT maestro_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: maestro_workflow_event maestro_workflow_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_event
    ADD CONSTRAINT maestro_workflow_event_pkey PRIMARY KEY (id);


--
-- Name: maestro_workflow_instance maestro_workflow_instance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_instance
    ADD CONSTRAINT maestro_workflow_instance_pkey PRIMARY KEY (id);


--
-- Name: maestro_workflow_instance maestro_workflow_instance_run_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_instance
    ADD CONSTRAINT maestro_workflow_instance_run_id_key UNIQUE (run_id);


--
-- Name: maestro_workflow_instance maestro_workflow_instance_workflow_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_instance
    ADD CONSTRAINT maestro_workflow_instance_workflow_id_key UNIQUE (workflow_id);


--
-- Name: maestro_workflow_signal maestro_workflow_signal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_signal
    ADD CONSTRAINT maestro_workflow_signal_pkey PRIMARY KEY (id);


--
-- Name: maestro_workflow_timer maestro_workflow_timer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_timer
    ADD CONSTRAINT maestro_workflow_timer_pkey PRIMARY KEY (id);


--
-- Name: idx_dist_lock_expires; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dist_lock_expires ON public.maestro_distributed_lock USING btree (expires_at);


--
-- Name: idx_wf_event_replay; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_wf_event_replay ON public.maestro_workflow_event USING btree (workflow_instance_id, sequence_number);


--
-- Name: idx_wf_instance_recoverable; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wf_instance_recoverable ON public.maestro_workflow_instance USING btree (status) WHERE ((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'WAITING_SIGNAL'::character varying, 'WAITING_TIMER'::character varying, 'COMPENSATING'::character varying])::text[]));


--
-- Name: idx_wf_instance_recovery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wf_instance_recovery ON public.maestro_workflow_instance USING btree (service_name, started_at, id) WHERE ((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'WAITING_SIGNAL'::character varying, 'WAITING_TIMER'::character varying, 'COMPENSATING'::character varying])::text[]));


--
-- Name: idx_wf_instance_terminal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wf_instance_terminal ON public.maestro_workflow_instance USING btree (status, completed_at) WHERE (completed_at IS NOT NULL);


--
-- Name: idx_wf_signal_instance; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wf_signal_instance ON public.maestro_workflow_signal USING btree (workflow_instance_id);


--
-- Name: idx_wf_signal_message_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_wf_signal_message_id ON public.maestro_workflow_signal USING btree (workflow_id, signal_name, message_id) WHERE (message_id IS NOT NULL);


--
-- Name: idx_wf_signal_orphan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wf_signal_orphan ON public.maestro_workflow_signal USING btree (workflow_id, consumed) WHERE ((workflow_instance_id IS NULL) AND (consumed = false));


--
-- Name: idx_wf_signal_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wf_signal_pending ON public.maestro_workflow_signal USING btree (workflow_id, signal_name, consumed) WHERE (consumed = false);


--
-- Name: idx_wf_timer_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wf_timer_due ON public.maestro_workflow_timer USING btree (fire_at, status) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_wf_timer_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_wf_timer_lookup ON public.maestro_workflow_timer USING btree (workflow_instance_id, timer_id);


--
-- Name: maestro_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX maestro_schema_history_s_idx ON public.maestro_schema_history USING btree (success);


--
-- Name: maestro_workflow_event maestro_workflow_event_workflow_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_event
    ADD CONSTRAINT maestro_workflow_event_workflow_instance_id_fkey FOREIGN KEY (workflow_instance_id) REFERENCES public.maestro_workflow_instance(id);


--
-- Name: maestro_workflow_signal maestro_workflow_signal_workflow_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_signal
    ADD CONSTRAINT maestro_workflow_signal_workflow_instance_id_fkey FOREIGN KEY (workflow_instance_id) REFERENCES public.maestro_workflow_instance(id);


--
-- Name: maestro_workflow_timer maestro_workflow_timer_workflow_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.maestro_workflow_timer
    ADD CONSTRAINT maestro_workflow_timer_workflow_instance_id_fkey FOREIGN KEY (workflow_instance_id) REFERENCES public.maestro_workflow_instance(id);


--
-- PostgreSQL database dump complete
--


