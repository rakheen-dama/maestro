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

INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', 'create maestro schema', 'SQL', 'V1__create_maestro_schema.sql', 1894477780, 'test', '2026-11-22 15:37:22.884065', 14, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (2, '2', 'create indexes', 'SQL', 'V2__create_indexes.sql', 1117887476, 'test', '2026-11-22 15:37:22.917443', 18, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (3, '3', 'timer lookup index', 'SQL', 'V3__timer_lookup_index.sql', -511219852, 'test', '2026-11-22 15:37:22.948518', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (4, '4', 'signal trace context', 'SQL', 'V4__signal_trace_context.sql', -444138901, 'test', '2026-11-22 15:37:22.959326', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (5, '5', 'signal received at default', 'SQL', 'V5__signal_received_at_default.sql', -1319518420, 'test', '2026-11-22 15:37:22.969597', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (6, '6', 'instance lease epoch', 'SQL', 'V6__instance_lease_epoch.sql', -1501028209, 'test', '2026-11-22 15:37:22.977818', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (7, '7', 'workflow event args hash', 'SQL', 'V7__workflow_event_args_hash.sql', 1141881341, 'test', '2026-11-22 15:37:22.988941', 8, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (8, '8', 'retention indexes', 'SQL', 'V8__retention_indexes.sql', 1576234141, 'test', '2026-11-22 15:37:23.003988', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (9, '9', 'signal message id', 'SQL', 'V9__signal_message_id.sql', 168428632, 'test', '2026-11-22 15:37:23.017525', 5, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (10, '100', 'maestro lock postgres', 'SQL', 'V100__maestro_lock_postgres.sql', 1037217973, 'test', '2026-11-22 15:37:23.030312', 6, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (11, '101', 'recovery scan index', 'SQL', 'V101__recovery_scan_index.sql', 9197330, 'test', '2026-11-22 15:37:23.046125', 3, true);


--
-- Data for Name: maestro_workflow_event; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('42f0ef51-f229-44e2-938e-a20571fcfd94', '462e1284-a044-4302-8744-1169bc392218', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-completed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-22 13:37:23.316945+00', '61120492bde41a623abae6da6acef1477c1c44ac5ccf3e854f20c04625ce8fad');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2307bf2b-6c26-49ab-952e-84d4a30b75db', '462e1284-a044-4302-8744-1169bc392218', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-22T13:37:23.399755Z"', '2026-11-22 13:37:23.399925+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('08697f68-a269-4c17-b2cc-a93411d592cd', '462e1284-a044-4302-8744-1169bc392218', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"df4e61c8-25f8-4de2-8696-176d32d58b6e"', '2026-11-22 13:37:23.424487+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d926e592-08fa-4f43-82be-29b7fe14cbe5', '462e1284-a044-4302-8744-1169bc392218', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-22 13:37:23.453049+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('54dba634-c5c3-4818-b3e5-5a5c4a63dc40', '462e1284-a044-4302-8744-1169bc392218', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-22 13:37:23.478064+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4717af77-bfbc-49c6-ba4e-fb5b29d11f2f', '462e1284-a044-4302-8744-1169bc392218', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-22 13:37:23.515439+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6631b381-8612-4616-9db5-26be9dfd4115', '462e1284-a044-4302-8744-1169bc392218', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-22 13:37:23.515699+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a2ebbc66-9e98-4aea-b10b-d7a89a999fbc', '462e1284-a044-4302-8744-1169bc392218', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-completed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:23.549796+00', '2ecf14aafdc0c4016c28daa53e863728959afca02f9b5503a122173ab2f30a04');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a2095be3-e50a-4b87-b30e-9714acb57662', '462e1284-a044-4302-8744-1169bc392218', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-22 13:37:23.621351+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('806d4429-d723-41dd-a813-f59c713bb584', '462e1284-a044-4302-8744-1169bc392218', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-22 13:37:24.692311+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dac9cb6d-f069-4d3d-a2d1-fb3ffbd9e203', '462e1284-a044-4302-8744-1169bc392218', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-22T13:37:25.770129Z", "signalName": "express"}', '2026-11-22 13:37:24.780885+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a3510075-76fd-4ec8-b97b-1ff2108628ac', '462e1284-a044-4302-8744-1169bc392218', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-22 13:37:25.870842+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('37d82cff-6c77-4886-be58-7809340aee4d', '462e1284-a044-4302-8744-1169bc392218', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-22 13:37:26.064903+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('36dd3754-c29c-4aa8-b5c7-534956ca4c2c', '462e1284-a044-4302-8744-1169bc392218', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-22 13:37:26.187559+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8be7bf5b-e385-4524-beb1-dba96b4af1fb', '462e1284-a044-4302-8744-1169bc392218', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-19T13:37:26.280595Z", "signalName": "approval"}', '2026-11-22 13:37:26.293577+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9885c406-b5ad-4ffb-95c5-e5ce15df73d9', '462e1284-a044-4302-8744-1169bc392218', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-22 13:37:26.383546+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c3bc2e32-71d4-43c0-b71e-5482f9e14e33', '462e1284-a044-4302-8744-1169bc392218', 8010, 'ACTIVITY_COMPLETED', 'seed-order.charge', '{"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}', '2026-11-22 13:37:26.483752+00', '4f7957db6f7b556032ab536a356340cca8b51fb6d1726e046db09ede5858e8e5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ea3c2b9f-d535-437b-b6e4-46f9fd22a408', '462e1284-a044-4302-8744-1169bc392218', 8011, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:note', '"note for seed-completed"', '2026-11-22 13:37:26.533209+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1a84f579-038b-4e21-8d18-c6113909a441', '462e1284-a044-4302-8744-1169bc392218', 8012, 'WORKFLOW_COMPLETED', NULL, '{"note": "note for seed-completed", "token": "df4e61c8-25f8-4de2-8696-176d32d58b6e", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-22T13:37:23.399755Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', '2026-11-22 13:37:26.567537+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f593d328-8270-49c4-89f9-35fd77be5e1f', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-approval", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-22 13:37:26.661869+00', 'b6195227c62ead98ed1b3b177bfa8f2bb53caca058a40f3326a52ea2b6c24a86');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('64253c11-d715-41a8-a089-f6cf1f850bdb', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-22T13:37:26.683765Z"', '2026-11-22 13:37:26.683821+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f0d3fb1a-b1fb-4c08-9a56-5ecc136a51b8', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"1e3754c1-3b41-4677-8535-d42613bf7891"', '2026-11-22 13:37:26.702845+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('574075b8-3bb8-4bfc-87ca-596959abb192', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-22 13:37:26.722104+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('882db3fd-662f-4b71-9590-a25bd974041a', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-22 13:37:26.740562+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f2da8fe7-4a9f-493c-8b2d-eb850ca97117', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-22 13:37:26.761692+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('df657188-b89e-4cb6-85ef-83cfc7361a03', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-22 13:37:26.761689+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('77de3b59-a6e1-42b4-bd34-778c33bda03a', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-approval", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:26.78243+00', 'c2607e49b4ab277032d58069d85787ad440a6041aefa57dab52b717a1cc9b16f');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4ac6a634-74c9-43f3-8bc4-1b85806e1597', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-22 13:37:26.809744+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('83039ce9-daa9-4501-a292-d991c5afcce8', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-22 13:37:27.986663+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1765aece-f518-46d4-9986-c0f3f59f3de4', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-22T13:37:29.052920Z", "signalName": "express"}', '2026-11-22 13:37:28.063446+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8ee8d8e1-6c9f-4251-95e4-967d53cb2648', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-22 13:37:29.156793+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b58741b3-e2b8-4713-bef6-abfa2364565b', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-22 13:37:29.23675+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1e8468a6-d0c0-44f8-87b8-0eb75cee2888', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-22 13:37:29.367118+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8810a313-58d6-47b2-8a4b-750fc3a82404', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-19T13:37:29.473999Z", "signalName": "approval"}', '2026-11-22 13:37:29.485266+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d1e0ea96-102b-4828-9d8c-670eb5b87e29', 'b68a812e-ee3c-46b7-983c-339f95a00274', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-terminated", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-22 13:37:29.712739+00', '785d82a01f4a3938f2f8d5ae7601a6d490bf7cfd73d10ee4ce728ea649d991cb');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dae58cc3-c96d-4a36-9bc1-abd0a0d4a5d0', 'b68a812e-ee3c-46b7-983c-339f95a00274', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-22T13:37:29.730815Z"', '2026-11-22 13:37:29.730882+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('018727c0-7b1c-4af6-b51a-df844046d666', 'b68a812e-ee3c-46b7-983c-339f95a00274', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"d4797536-7564-48af-a9b9-79e39365caad"', '2026-11-22 13:37:29.74983+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0ffd3cfb-4f40-4363-8ae7-edb762687663', 'b68a812e-ee3c-46b7-983c-339f95a00274', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-22 13:37:29.768215+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6a4ee402-e108-40bc-8146-ed126c50a005', 'b68a812e-ee3c-46b7-983c-339f95a00274', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-22 13:37:29.784806+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c2fd5552-7004-4bb4-922f-b19d041edd1a', 'b68a812e-ee3c-46b7-983c-339f95a00274', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-22 13:37:29.802694+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('72052972-5477-49b4-aa24-0df6df503d49', 'b68a812e-ee3c-46b7-983c-339f95a00274', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-22 13:37:29.802713+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3dbd3b40-e655-4f82-ac17-5c4b35b08a2f', 'b68a812e-ee3c-46b7-983c-339f95a00274', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-terminated", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:29.820789+00', 'aaf06686d0cc7c8cba8970dc79c375fc1ce1be0e307c7e044520e11bbf08b1bc');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('013ac618-fc59-4f79-bd59-4f15014102c5', 'b68a812e-ee3c-46b7-983c-339f95a00274', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-22 13:37:29.84512+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('72884a71-49d0-4f98-9c71-1a1f57583f07', 'b68a812e-ee3c-46b7-983c-339f95a00274', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-22 13:37:31.072693+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7fa11de2-25ac-4d77-96f4-3244d1fff1ea', 'b68a812e-ee3c-46b7-983c-339f95a00274', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-22T13:37:32.159876Z", "signalName": "express"}', '2026-11-22 13:37:31.171703+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('23d6efb3-fb17-4c28-b80c-7d899ef977c5', 'b68a812e-ee3c-46b7-983c-339f95a00274', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-22 13:37:32.209269+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('87e9b7c6-2e22-446e-b26f-a4334376f857', 'b68a812e-ee3c-46b7-983c-339f95a00274', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-22 13:37:32.25612+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9a7bf6a1-2bab-4d34-9b6b-805583775b33', 'b68a812e-ee3c-46b7-983c-339f95a00274', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-22 13:37:32.340955+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e144e269-01e2-4b37-876b-425210fd0f3e', 'b68a812e-ee3c-46b7-983c-339f95a00274', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-19T13:37:32.397656Z", "signalName": "approval"}', '2026-11-22 13:37:32.406178+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e8e36094-0f64-4eb9-b276-6001ffad70f5', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-crashed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-22 13:37:32.821637+00', 'e00fde43b12ebd50fb69f3fe435e050d9a6300f7fc0a13f99bebde6e91498fa5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5b90021b-5f75-4e2d-bfbf-044a29b32a38', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-22T13:37:32.845194Z"', '2026-11-22 13:37:32.845256+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5f8c27d0-3945-472d-a6e0-0e2e3bdb2760', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"3de2306c-f277-4c14-8e01-6d45c4dec066"', '2026-11-22 13:37:32.864426+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('551dee2d-2aee-4f35-9b44-e3dba9cb9553', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-22 13:37:32.889544+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4e9d34d6-4f2b-4815-b069-8c594e6b9d61', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-22 13:37:32.913557+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9dbc1291-3660-4ba9-9563-15df999dff75', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-22 13:37:32.972118+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ad793a1a-f64f-4b97-9051-79d187a26e5d', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-22 13:37:32.971364+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7c444380-ac28-4ebb-9567-06b1568109b6', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-crashed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:33.004951+00', '9afaaca3ce7e4299920c56b10762cde56e24090ad65cc2266e61c3d248a41109');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b4035b6d-65eb-4537-aa87-0052176dc69b', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-22 13:37:33.040903+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c90d20fb-b338-4831-86d8-bcf3cf4fad06', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-22 13:37:34.118594+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7a5f28fb-cdfb-40f3-83f4-cc67f8ae552d', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-22T13:37:35.172488Z", "signalName": "express"}', '2026-11-22 13:37:34.180873+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5a862536-845b-4275-8a2b-c6a7e84db274', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-22 13:37:35.221595+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d86799dd-8a2d-4ce9-befc-eafcc8b02257', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-22 13:37:35.27939+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9406e316-1acc-49ae-87fc-a929bbba8a06', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-22 13:37:35.39842+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b1f875d5-0752-4e72-967c-99a9e7cf7cd2', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-19T13:37:35.453758Z", "signalName": "approval"}', '2026-11-22 13:37:35.462366+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('27d4f66f-ad6b-4358-8841-d0669a64591b', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-22 13:37:35.533349+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('58a7fc70-9b46-45b8-ab0d-16198accaad3', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"plain"', '2026-11-22 13:37:35.672978+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0e85682d-9c6c-4ec3-8d15-5b0a2c38fe33', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"plain"', '2026-11-22 13:37:35.689992+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('903d863b-fbe9-4017-af01-00f5c0b767d1', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for plain", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:35.707979+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0cf3771d-a40f-4dab-bdd3-1be7d2a75be8', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-22 13:37:35.748503+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3abc63ca-981d-4a06-92bc-6ebed69c6ac8', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-22 13:37:35.79237+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('caebd8c7-9bef-4136-8b93-2aab7dc8a209', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-22 13:37:35.806702+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cbd7edac-0698-4ee2-948d-3a84823a6ab5', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-22 13:37:35.875073+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1ffe75c7-f719-45b3-98e6-4ad160443fb9', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-22 13:37:35.88264+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1641dcfa-307d-47b2-9ca2-1c8ecf94d47d', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', NULL, '2026-11-22 13:37:35.903966+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8109e541-6507-4f80-b5c6-cddc8ae89cd1', 'c8381dc4-404c-43a7-8941-da88fdfdc2a9', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-22 13:37:35.931739+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('645e37e9-2c3d-4ef7-b6c7-65e0bbad1417', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"fail-refund:x"', '2026-11-22 13:37:36.038493+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('32280efb-8123-4d54-abd2-d0797332123c', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"fail-refund:x"', '2026-11-22 13:37:36.055764+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('feefe4e2-3dd1-4717-895c-926af75030dd', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-22 13:37:38.406804+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('127ce3c6-17cc-47dc-afdb-0b3cba297f04', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:36.075021+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('96d833ac-910b-492a-95ed-4c10adca016e', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-22 13:37:36.112288+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('deb98653-86ba-4169-abff-129cfbfb3140', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 5001, 'ACTIVITY_FAILED', 'seed-saga.refund', '{"message": "refund rejected for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:36.148917+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('614d56ee-5e0c-453b-832a-7e2eba1abe31', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 5000, 'COMPENSATION_STEP_FAILED', 'seed-saga.refund', '{"message": "Activity ''seed-saga.refund'' failed in workflow ''seed-saga-compensation-failed'': refund rejected for fail-refund:x", "stepName": "seed-saga.refund", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-22 13:37:36.160549+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('727098f3-1ef9-4d4f-b5ca-8226aaceb8fe', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-22 13:37:36.19226+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8df294d0-69f9-4f8d-a2b3-923fd258ec11', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-22 13:37:36.199586+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5c46673b-7294-4f49-9377-803cf2be0d22', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', '{"failedSteps": ["seed-saga.refund"], "totalCompensations": 2}', '2026-11-22 13:37:36.213719+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d3a68b1c-3373-4cec-910f-3254d54d086e', 'fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-22 13:37:36.23025+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('404dee92-c0db-4b91-a94f-28e68da05b14', 'e1279d4e-369a-4317-84a0-0ba005763800', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"crash-compensation"', '2026-11-22 13:37:36.337386+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('35746663-615d-4092-baae-db0ac59646b3', 'e1279d4e-369a-4317-84a0-0ba005763800', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"crash-compensation"', '2026-11-22 13:37:36.364343+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f4853e57-1f47-49f1-81e5-fbc6924fd914', 'e1279d4e-369a-4317-84a0-0ba005763800', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for crash-compensation", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:36.385939+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d4a3a163-27ce-4e74-a2c2-e4d1cefb645f', 'e1279d4e-369a-4317-84a0-0ba005763800', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-22 13:37:36.421712+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('11895ee2-d400-4544-852a-c1169585a674', 'e1279d4e-369a-4317-84a0-0ba005763800', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-22 13:37:36.46039+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('535835d0-ad3d-45f4-90d0-e41dc509ec3e', 'e1279d4e-369a-4317-84a0-0ba005763800', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-22 13:37:36.469528+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b213bae9-d5a9-41cf-9f18-4948188cf299', '570aad20-3d5f-4b11-b018-93fa062ca106', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:36.707721+00', 'ae55ac050582d8056dcfc24a102fc318460211527edd40f09779fa2b2de4f2ec');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8f0f499d-ae12-4c76-b52b-6ba9d655e829', '570aad20-3d5f-4b11-b018-93fa062ca106', 2, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-22 13:37:36.732407+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('48cb7a37-0c1e-4e31-9752-9cf1cac888c8', '0e6b97fe-883e-42d0-aded-31d1d534da33', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:36.943448+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ea1bc9db-7d04-443a-9d01-ec35e6efa2cb', '0e6b97fe-883e-42d0-aded-31d1d534da33', 2, 'ACTIVITY_COMPLETED', 'seed-failing.alert', '"alerted:seed-alert-failed"', '2026-11-22 13:37:36.973263+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6d78d4d8-6091-45f9-80fd-6378c89c07b5', '0e6b97fe-883e-42d0-aded-31d1d534da33', 3, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-22 13:37:37.003301+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('62e68c4b-f9f6-4a83-99e9-27706fca2bc0', '02e0f99d-ea26-40bf-bbb9-db1e5f381426', 1, 'ACTIVITY_COMPLETED', 'seed-typed.exactAmount', '12345678901234567890.123456789', '2026-11-22 13:37:37.173492+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3667e721-d429-4670-97fb-efa04ab32d73', 'bb1b593a-b1ee-4e8e-a0f3-9a1624bb59c9', 1, 'ACTIVITY_COMPLETED', 'seed-typed.localStamp', '"2026-11-12T10:00:00+02:00"', '2026-11-22 13:37:37.197684+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5fa2c072-8e74-41a8-ad58-1e58e390d981', '2a35e8b8-78aa-4b18-99bb-fb7599690890', 1, 'ACTIVITY_COMPLETED', 'seed-typed.tags', '["alpha", "gamma", "beta", "delta"]', '2026-11-22 13:37:37.221079+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a92037e0-abe6-45de-a5b9-d3643061c6f8', '02e0f99d-ea26-40bf-bbb9-db1e5f381426', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-19T13:37:37.231624Z", "signalName": "go"}', '2026-11-22 13:37:37.241696+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d6233857-efc1-44e1-b91c-cda94e782788', 'bb1b593a-b1ee-4e8e-a0f3-9a1624bb59c9', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-19T13:37:37.241097Z", "signalName": "go"}', '2026-11-22 13:37:37.252668+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c98f618f-8697-422a-bc49-b9910d0afc6c', '2a35e8b8-78aa-4b18-99bb-fb7599690890', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-19T13:37:37.265809Z", "signalName": "go"}', '2026-11-22 13:37:37.279078+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5ac3aa4c-c256-419c-b769-c995613ae89b', '6be89d30-82cb-4e72-a64c-7fd022f2152d', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"parked"', '2026-11-22 13:37:37.955403+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9cafa859-6fb0-4654-9cd7-a19627e22752', '6be89d30-82cb-4e72-a64c-7fd022f2152d', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"parked"', '2026-11-22 13:37:37.982909+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3e06cea1-d6ad-4c24-8f9b-f661552019c0', '6be89d30-82cb-4e72-a64c-7fd022f2152d', 3, 'TIMER_SCHEDULED', '$maestro:awaitSignal:ship-now', '{"timeout": "PT87600H", "timerId": "await-signal-3", "deadline": "2036-11-19T13:37:38.041427Z", "signalName": "ship-now"}', '2026-11-22 13:37:38.05826+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bee3bc12-24ff-4049-9a06-f11e9e66b501', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-timer", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-22 13:37:38.31478+00', '558ccdf6e173c89faa47667cf30f6a9d89ab597ce095afe18363ca028ba5882d');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0bebd14b-3737-41a8-a8f9-153f9457a087', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-22T13:37:38.331381Z"', '2026-11-22 13:37:38.331441+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('de076dd6-a9fb-4dd6-bb1d-d9e575f0a5a9', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"f7567b8f-a1a7-4a5d-a9a9-a8e9260a1607"', '2026-11-22 13:37:38.349018+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b73aead4-0dca-4627-aaa3-d0c38bb84729', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-22 13:37:38.365082+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('15ccf57f-f024-4114-a4a5-f7acf19fb737', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-22 13:37:38.387398+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('73a56cd1-35df-4540-8d07-b8a200c7eb25', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-22 13:37:38.40682+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4f37f0ae-182e-48bc-a574-105a887a3223', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-timer", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:38.427701+00', 'd0eae109e2ce1eb172aa9c675c463121146e996ead38dee0886c8252d36a77ac');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('47441f50-8333-4b76-b689-7ef8b2c1f088', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT10M"}', '2026-11-22 13:37:38.458326+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c8806f04-3b6a-42f3-af33-6b3c4cc7c951', '59e6f7e5-5384-4f03-8316-cef199daffda', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-express", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-22 13:37:38.542956+00', '354fbdbc6a8e2e95b3863f5a5582854a39445fa66416df79e67c44532ca4e457');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3c6c4c22-245f-48d7-8d52-f58273d0c7b6', '59e6f7e5-5384-4f03-8316-cef199daffda', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-22T13:37:38.558956Z"', '2026-11-22 13:37:38.559024+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c750bbf1-be84-44c2-8be6-7ffcaff2fa3f', '59e6f7e5-5384-4f03-8316-cef199daffda', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"d8ecd1fa-9660-462e-b994-8482ba57dc06"', '2026-11-22 13:37:38.573223+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9909031f-71da-4731-b396-708e3f533b73', '59e6f7e5-5384-4f03-8316-cef199daffda', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-22 13:37:38.586971+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1cab8c38-8fe9-412d-a5ae-94ece53c4b96', '59e6f7e5-5384-4f03-8316-cef199daffda', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-22 13:37:38.601308+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dcc7e798-9273-4c42-ae84-106d2e147607', '59e6f7e5-5384-4f03-8316-cef199daffda', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-22 13:37:38.615548+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('991c4f60-273a-428f-98b0-2451b784d1d2', '59e6f7e5-5384-4f03-8316-cef199daffda', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-22 13:37:38.615708+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1264ef86-2584-41c1-854a-05f2ee278776', '59e6f7e5-5384-4f03-8316-cef199daffda', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-express", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_4.SeededHistoryWorkflows$SeedFailure"}', '2026-11-22 13:37:38.632036+00', 'e487ac6f64544e0840cd7f51f5490bd78e0b48293e7d15f91f57aa2ca64c9100');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7bf2df54-49a5-449b-aff5-e11e06780043', '59e6f7e5-5384-4f03-8316-cef199daffda', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-22 13:37:38.652862+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('67776fbf-0e99-403e-9d43-43bdda11c2af', '59e6f7e5-5384-4f03-8316-cef199daffda', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-22 13:37:39.860655+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2176f80a-01f0-4ff5-8b16-a5405befbefc', '59e6f7e5-5384-4f03-8316-cef199daffda', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT10M", "timerId": "await-signal-8004", "deadline": "2026-11-22T13:47:39.940758Z", "signalName": "express"}', '2026-11-22 13:37:39.952951+00', NULL);


--
-- Data for Name: maestro_workflow_instance; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('462e1284-a044-4302-8744-1169bc392218', 'seed-completed', '38328006-d077-4cc5-bc80-51d2fc9eb809', 'SeedOrderWorkflow', 'default', 'COMPLETED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-completed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"note": "note for seed-completed", "token": "df4e61c8-25f8-4de2-8696-176d32d58b6e", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-22T13:37:23.399755Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', 'upgrade-fixture', 8011, '2026-11-22 13:37:23.21569+00', '2026-11-22 13:37:26.567537+00', '2026-11-22 13:37:26.567537+00', 9, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('abcc53c1-48da-4549-b8bd-015eafacfe0a', 'seed-parked-approval', 'f6c5ee2f-4291-4260-8857-299fe26d6eba', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-approval", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-22 13:37:26.616686+00', NULL, '2026-11-22 13:37:29.505739+00', 7, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('b68a812e-ee3c-46b7-983c-339f95a00274', 'seed-terminated', '9b24f6aa-39b6-49cd-97bf-ee50e35534a6', 'SeedOrderWorkflow', 'default', 'TERMINATED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-terminated", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"reason": "seeded termination", "terminatedBy": "admin"}', 'upgrade-fixture', 0, '2026-11-22 13:37:29.682795+00', '2026-11-22 13:37:32.68209+00', '2026-11-22 13:37:32.68209+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('ead0bbff-cc58-448f-b9a0-957ecf000f8f', 'seed-crashed', '9899b686-786e-4816-b32e-0d3fda2c1125', 'SeedOrderWorkflow', 'default', 'RUNNING', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-crashed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-22 13:37:32.783251+00', NULL, '2026-11-22 13:37:35.56228+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('c8381dc4-404c-43a7-8941-da88fdfdc2a9', 'seed-saga-compensated', '77820db7-826c-450b-99ee-b8f15d694371', 'SeedSagaWorkflow', 'default', 'FAILED', '"plain"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-22 13:37:35.645615+00', '2026-11-22 13:37:35.931739+00', '2026-11-22 13:37:35.931739+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('fec2f7d8-7e78-4ed0-b8cf-012a2d9e9126', 'seed-saga-compensation-failed', '7b3eb6bf-35b4-46da-b05d-4d0f26f1fd17', 'SeedSagaWorkflow', 'default', 'FAILED', '"fail-refund:x"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-22 13:37:36.010743+00', '2026-11-22 13:37:36.23025+00', '2026-11-22 13:37:36.23025+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('e1279d4e-369a-4317-84a0-0ba005763800', 'seed-compensating-crashed', '108f7b19-48c1-4441-83b3-bdad97d543b8', 'SeedSagaWorkflow', 'default', 'COMPENSATING', '"crash-compensation"', NULL, 'upgrade-fixture', 0, '2026-11-22 13:37:36.300436+00', NULL, '2026-11-22 13:37:36.411981+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('570aad20-3d5f-4b11-b018-93fa062ca106', 'seed-activity-failed', '4811f09e-1f2d-421e-97be-9ea858c1305c', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-activity-failed", "alertBeforeRethrow": false}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 1, '2026-11-22 13:37:36.573789+00', '2026-11-22 13:37:36.732407+00', '2026-11-22 13:37:36.732407+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('0e6b97fe-883e-42d0-aded-31d1d534da33', 'seed-alert-failed', 'd903f343-b9bb-4de3-8500-da675fd52b31', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-alert-failed", "alertBeforeRethrow": true}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 2, '2026-11-22 13:37:36.801535+00', '2026-11-22 13:37:37.003301+00', '2026-11-22 13:37:37.003301+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('02e0f99d-ea26-40bf-bbb9-db1e5f381426', 'seed-wp1-decimal', 'abad8df6-f9c4-414b-9b59-7469c376a14b', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "DECIMAL"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-22 13:37:37.142952+00', NULL, '2026-11-22 13:37:37.268705+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('bb1b593a-b1ee-4e8e-a0f3-9a1624bb59c9', 'seed-wp1-offset', 'cc21ae4f-a3a4-473e-a7dc-529d004996f4', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "OFFSET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-22 13:37:37.166247+00', NULL, '2026-11-22 13:37:37.27845+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('2a35e8b8-78aa-4b18-99bb-fb7599690890', 'seed-wp1-set', '885c92ba-646f-4ee6-81a0-d3ffca20b541', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "SET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-22 13:37:37.18726+00', NULL, '2026-11-22 13:37:37.300859+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('6be89d30-82cb-4e72-a64c-7fd022f2152d', 'seed-saga-parked', 'c41dcc07-2626-4f92-a665-a45dedfc85f5', 'SeedParkedSagaWorkflow', 'default', 'WAITING_SIGNAL', '"parked"', NULL, 'upgrade-fixture-saga', 0, '2026-11-22 13:37:37.908511+00', NULL, '2026-11-22 13:37:38.084164+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 'seed-parked-timer', '0c4ed32d-150b-487e-ac85-abdfec809dca', 'SeedOrderWorkflow', 'default', 'WAITING_TIMER', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-timer", "napMillis": 600000, "expressWaitMillis": 1000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-22 13:37:38.288667+00', NULL, '2026-11-22 13:37:38.475459+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('59e6f7e5-5384-4f03-8316-cef199daffda', 'seed-parked-express', '73c8f0a4-2513-4e31-8dd1-f2a977cdb736', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-express", "napMillis": 1000, "expressWaitMillis": 600000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-22 13:37:38.520658+00', NULL, '2026-11-22 13:37:39.97859+00', 3, 0);


--
-- Data for Name: maestro_workflow_signal; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('7f56690d-9c43-4b17-bad8-d75abe05e8b1', '462e1284-a044-4302-8744-1169bc392218', 'seed-completed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-22 13:37:26.324112+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('01b04e28-6b51-430d-89aa-9d3f76646112', '462e1284-a044-4302-8744-1169bc392218', 'seed-completed', 'note', '"note for seed-completed"', true, '2026-11-22 13:37:23.373755+00', NULL, 'client:seed-completed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('66e50cc8-9553-4f2a-97ae-2dcec0c526a4', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 'seed-parked-approval', 'note', '"note for seed-parked-approval"', false, '2026-11-22 13:37:26.673395+00', NULL, 'client:seed-parked-approval:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('71327d30-b76a-49a5-8ff3-27b3197affd5', 'b68a812e-ee3c-46b7-983c-339f95a00274', 'seed-terminated', 'note', '"note for seed-terminated"', false, '2026-11-22 13:37:29.720385+00', NULL, 'client:seed-terminated:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('7410c61f-a90b-48e7-bc49-6721c51541eb', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 'seed-crashed', 'note', '"note for seed-crashed"', false, '2026-11-22 13:37:32.833055+00', NULL, 'client:seed-crashed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('e9dea1be-6752-4b37-9ce6-aba31de7f9ad', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 'seed-crashed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-22 13:37:35.513866+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('aeade257-118f-4ffd-a961-a622f8eb0b64', NULL, 'seed-orphan-target', 'approval', '{"level": 1, "approver": "capture"}', false, '2026-11-22 13:37:37.117476+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('936bd9d1-92f5-4a70-9e05-45fb15525767', NULL, 'seed-orphan-target', 'note', '"note for seed-orphan-target"', false, '2026-11-22 13:37:37.138852+00', NULL, 'client:seed-orphan-target:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('826b1abf-375f-4d10-8fdb-c1c63e8ae93b', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 'seed-parked-timer', 'note', '"note for seed-parked-timer"', false, '2026-11-22 13:37:38.320247+00', NULL, 'client:seed-parked-timer:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('f17263ab-3ddd-48d4-9b55-76642b252d73', '59e6f7e5-5384-4f03-8316-cef199daffda', 'seed-parked-express', 'note', '"note for seed-parked-express"', false, '2026-11-22 13:37:38.548164+00', NULL, 'client:seed-parked-express:note');


--
-- Data for Name: maestro_workflow_timer; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('1afdf9eb-1196-4a05-bb62-9aaf1ee39009', '462e1284-a044-4302-8744-1169bc392218', 'seed-completed', 'sleep-8002', '2026-11-22 13:37:24.601988+00', 'FIRED', '2026-11-22 13:37:23.602613+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('133a24a4-81ce-4173-97c6-9eb129185574', '462e1284-a044-4302-8744-1169bc392218', 'seed-completed', 'await-signal-8004', '2026-11-22 13:37:25.770129+00', 'FIRED', '2026-11-22 13:37:24.770165+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('d3240e94-b0e4-4040-be71-de24387b062d', '462e1284-a044-4302-8744-1169bc392218', 'seed-completed', 'sleep-8006', '2026-11-23 13:37:26.045467+00', 'CANCELLED', '2026-11-22 13:37:26.045471+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('6b5003df-1ef2-4897-994f-4b608f21fb7f', '462e1284-a044-4302-8744-1169bc392218', 'seed-completed', 'await-signal-8008', '2036-11-19 13:37:26.280595+00', 'CANCELLED', '2026-11-22 13:37:26.280615+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('e4a1c606-2b91-419d-8ea7-64dae02b2fd9', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 'seed-parked-approval', 'sleep-8002', '2026-11-22 13:37:27.799224+00', 'FIRED', '2026-11-22 13:37:26.799229+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('4360ae84-e7ef-4531-b2f3-a67ec0c3b236', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 'seed-parked-approval', 'await-signal-8004', '2026-11-22 13:37:29.05292+00', 'FIRED', '2026-11-22 13:37:28.05296+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('be9c2c49-c4b3-4690-9847-cbb3182a647f', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 'seed-parked-approval', 'sleep-8006', '2026-11-23 13:37:29.224294+00', 'CANCELLED', '2026-11-22 13:37:29.2243+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('1c085111-3a1d-425e-baad-f1452f98f62b', 'abcc53c1-48da-4549-b8bd-015eafacfe0a', 'seed-parked-approval', 'await-signal-8008', '2036-11-19 13:37:29.473999+00', 'PENDING', '2026-11-22 13:37:29.474017+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('f92fa1ab-8fa5-475f-8b24-496580e78625', 'b68a812e-ee3c-46b7-983c-339f95a00274', 'seed-terminated', 'sleep-8002', '2026-11-22 13:37:30.838079+00', 'FIRED', '2026-11-22 13:37:29.838083+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('a57dd095-9ac7-42df-a9b4-ca6ca33b8057', 'b68a812e-ee3c-46b7-983c-339f95a00274', 'seed-terminated', 'await-signal-8004', '2026-11-22 13:37:32.159876+00', 'FIRED', '2026-11-22 13:37:31.159896+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('c643acd7-2452-4146-8dea-6269c86737e4', 'b68a812e-ee3c-46b7-983c-339f95a00274', 'seed-terminated', 'sleep-8006', '2026-11-23 13:37:32.249005+00', 'CANCELLED', '2026-11-22 13:37:32.24901+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('b9a5ed63-12f0-4f7a-91ad-1dac6c3d8626', 'b68a812e-ee3c-46b7-983c-339f95a00274', 'seed-terminated', 'await-signal-8008', '2036-11-19 13:37:32.397656+00', 'PENDING', '2026-11-22 13:37:32.397679+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('0a87430b-9caa-4fb6-9dcb-0185a5b37341', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 'seed-crashed', 'sleep-8002', '2026-11-22 13:37:34.029764+00', 'FIRED', '2026-11-22 13:37:33.029789+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('8d09df7e-e2b1-44b1-9027-50281e6e6e22', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 'seed-crashed', 'await-signal-8004', '2026-11-22 13:37:35.172488+00', 'FIRED', '2026-11-22 13:37:34.172507+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('9fbd86be-fd04-40ea-9e99-c0ab8759a21f', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 'seed-crashed', 'sleep-8006', '2026-11-23 13:37:35.271079+00', 'CANCELLED', '2026-11-22 13:37:35.271089+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('40e167fd-25b4-4dbf-bcd3-44d90f41dde1', 'ead0bbff-cc58-448f-b9a0-957ecf000f8f', 'seed-crashed', 'await-signal-8008', '2036-11-19 13:37:35.453758+00', 'CANCELLED', '2026-11-22 13:37:35.453776+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('2c2c9ca6-d0ba-4d32-86a3-403173b780e2', '02e0f99d-ea26-40bf-bbb9-db1e5f381426', 'seed-wp1-decimal', 'await-signal-2', '2036-11-19 13:37:37.231624+00', 'PENDING', '2026-11-22 13:37:37.231643+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('a21bfa31-3f19-411f-91e4-6fddfa7a2aeb', 'bb1b593a-b1ee-4e8e-a0f3-9a1624bb59c9', 'seed-wp1-offset', 'await-signal-2', '2036-11-19 13:37:37.241097+00', 'PENDING', '2026-11-22 13:37:37.241112+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('ab96cddd-0315-42ac-9c40-45a572c3fb4d', '2a35e8b8-78aa-4b18-99bb-fb7599690890', 'seed-wp1-set', 'await-signal-2', '2036-11-19 13:37:37.265809+00', 'PENDING', '2026-11-22 13:37:37.265828+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('91075c37-4e87-4387-9174-f274ef1f1408', '6be89d30-82cb-4e72-a64c-7fd022f2152d', 'seed-saga-parked', 'await-signal-3', '2036-11-19 13:37:38.041427+00', 'PENDING', '2026-11-22 13:37:38.041446+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('b679eb09-370b-464b-95d4-02c29c7b629c', '2a3f9856-3a3d-49c7-a879-b5097cfdaf49', 'seed-parked-timer', 'sleep-8002', '2026-11-22 13:47:38.450828+00', 'PENDING', '2026-11-22 13:37:38.450835+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3ea626da-17b9-43f1-b11d-51c60917b1e1', '59e6f7e5-5384-4f03-8316-cef199daffda', 'seed-parked-express', 'sleep-8002', '2026-11-22 13:37:39.646816+00', 'FIRED', '2026-11-22 13:37:38.646823+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('8d843bc4-5ac5-429f-9236-edd2d9e8f717', '59e6f7e5-5384-4f03-8316-cef199daffda', 'seed-parked-express', 'await-signal-8004', '2026-11-22 13:47:39.940758+00', 'PENDING', '2026-11-22 13:37:39.940776+00');


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


