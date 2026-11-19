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

INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', 'create maestro schema', 'SQL', 'V1__create_maestro_schema.sql', 1894477780, 'test', '2026-11-19 15:35:14.919755', 12, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (2, '2', 'create indexes', 'SQL', 'V2__create_indexes.sql', 1117887476, 'test', '2026-11-19 15:35:14.946859', 6, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (3, '3', 'timer lookup index', 'SQL', 'V3__timer_lookup_index.sql', -511219852, 'test', '2026-11-19 15:35:14.963032', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (4, '4', 'signal trace context', 'SQL', 'V4__signal_trace_context.sql', -444138901, 'test', '2026-11-19 15:35:14.97257', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (5, '5', 'signal received at default', 'SQL', 'V5__signal_received_at_default.sql', -1319518420, 'test', '2026-11-19 15:35:14.981572', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (6, '6', 'instance lease epoch', 'SQL', 'V6__instance_lease_epoch.sql', -1501028209, 'test', '2026-11-19 15:35:14.989102', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (7, '7', 'workflow event args hash', 'SQL', 'V7__workflow_event_args_hash.sql', 1141881341, 'test', '2026-11-19 15:35:14.997445', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (8, '8', 'retention indexes', 'SQL', 'V8__retention_indexes.sql', 1576234141, 'test', '2026-11-19 15:35:15.006244', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (9, '9', 'signal message id', 'SQL', 'V9__signal_message_id.sql', 168428632, 'test', '2026-11-19 15:35:15.015896', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (10, '100', 'maestro lock postgres', 'SQL', 'V100__maestro_lock_postgres.sql', 1037217973, 'test', '2026-11-19 15:35:15.026618', 5, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (11, '101', 'recovery scan index', 'SQL', 'V101__recovery_scan_index.sql', 9197330, 'test', '2026-11-19 15:35:15.037708', 2, true);


--
-- Data for Name: maestro_workflow_event; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7fa4c81b-d9c8-418d-9bbe-50c15707cbbf', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-completed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-19 13:35:15.237565+00', '61120492bde41a623abae6da6acef1477c1c44ac5ccf3e854f20c04625ce8fad');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('97af450e-5e28-4c10-9771-7f9c36c5d447', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-19T13:35:15.262647Z"', '2026-11-19 13:35:15.262821+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c59ca63c-8358-4843-857b-ad6c22a72d25', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"c2c5b782-59e6-4701-8a39-81dbe9cf3c65"', '2026-11-19 13:35:15.282486+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d6f087ae-454c-4d81-a999-e209140827d0', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-19 13:35:15.302508+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('da72e5cf-5bf5-484b-855a-33b1f56d2168', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-19 13:35:15.322876+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0ad7975d-0e78-445e-9521-18796f51115e', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-19 13:35:15.348842+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('51f58aa9-7ca8-462b-a934-55c47987af70', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-19 13:35:15.349336+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('00237b58-4387-420e-83df-9b83b360b332', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-completed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:15.373045+00', '2ecf14aafdc0c4016c28daa53e863728959afca02f9b5503a122173ab2f30a04');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('00acca5c-76ff-4465-95e7-c48921ec5ba6', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-19 13:35:15.400619+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0173b404-62c9-49b1-8aca-20aeda0e008d', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-19 13:35:16.485909+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f41ecff8-f582-4ff3-926f-70c34033c225', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-19T13:35:17.529638Z", "signalName": "express"}', '2026-11-19 13:35:16.537509+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('25df66f6-7109-4346-aa10-618915493dab', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-19 13:35:17.580517+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('df017f4d-4fae-4b72-9a8e-19cbf3e55801', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-19 13:35:17.6254+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('17ff088e-7d8a-456f-a8ed-066a2b8dea6c', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-19 13:35:17.681016+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('01eb5a69-b4a9-49e4-b37c-8440cc72efbc', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-16T13:35:17.730215Z", "signalName": "approval"}', '2026-11-19 13:35:17.736864+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9d79d837-5e67-42d7-ae84-026777597e28', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-19 13:35:17.814027+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('65f711f3-15ed-47bf-a008-1bb469ae0615', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8010, 'ACTIVITY_COMPLETED', 'seed-order.charge', '{"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}', '2026-11-19 13:35:17.860081+00', '4f7957db6f7b556032ab536a356340cca8b51fb6d1726e046db09ede5858e8e5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cbabad29-d69d-491e-b9e8-5215f3f40606', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8011, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:note', '"note for seed-completed"', '2026-11-19 13:35:17.884367+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c1394ac4-9ac3-4675-9b0a-c9a4a06cee66', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 8012, 'WORKFLOW_COMPLETED', NULL, '{"note": "note for seed-completed", "token": "c2c5b782-59e6-4701-8a39-81dbe9cf3c65", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-19T13:35:15.262647Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', '2026-11-19 13:35:17.903849+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('55b0ea0f-8857-40e1-b1c7-486ccb070578', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-approval", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-19 13:35:17.957242+00', 'b6195227c62ead98ed1b3b177bfa8f2bb53caca058a40f3326a52ea2b6c24a86');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('18d53ad3-feda-4805-b206-0b086d0a2c35', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-19T13:35:17.972739Z"', '2026-11-19 13:35:17.972782+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6070d36f-d591-4b32-9fff-b027ea661dfc', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"804e5938-72ae-48ac-9d48-546c3476df73"', '2026-11-19 13:35:17.987307+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('92e80122-7e9d-4395-8a78-6dc2503ccf64', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-19 13:35:18.001903+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8748ea43-0e0d-438c-99d6-720ef4d8198c', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-19 13:35:18.016674+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d629947a-1693-43ab-ae90-411b1102420e', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-19 13:35:18.03147+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a0b9d01f-b156-4ce7-8485-fb6f817cdcd7', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-19 13:35:18.031462+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cc3bc05d-f30e-48f2-a31a-de344913153b', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-approval", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:18.047403+00', 'c2607e49b4ab277032d58069d85787ad440a6041aefa57dab52b717a1cc9b16f');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d1f96322-37d9-45fa-b3e1-77459282a040', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-19 13:35:18.068103+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('eded0784-fcb2-489a-a923-ac3404720d7a', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-19 13:35:19.288468+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d89e3cc6-5e50-46d2-af36-3a13f575123a', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-19T13:35:20.339505Z", "signalName": "express"}', '2026-11-19 13:35:19.34682+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a3f19ac8-05bd-4360-9bbb-abcfa3c9e05c', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-19 13:35:20.386321+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3f28e197-cc4a-4108-a99d-5b349762db27', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-19 13:35:20.428683+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('22b37d2c-767a-488a-aa89-956e81b8a260', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-19 13:35:20.499978+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('82830e33-25e6-47c1-9238-f100cfd287d8', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-16T13:35:20.543783Z", "signalName": "approval"}', '2026-11-19 13:35:20.550023+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0c711c2c-a658-43b7-8431-58a62e346b0b', '3d24b894-2a83-4586-b79a-4269cd5f0661', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-terminated", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-19 13:35:20.835142+00', '785d82a01f4a3938f2f8d5ae7601a6d490bf7cfd73d10ee4ce728ea649d991cb');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3047cae8-03d9-4a0c-9616-64a462e06c05', '3d24b894-2a83-4586-b79a-4269cd5f0661', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-19T13:35:20.850886Z"', '2026-11-19 13:35:20.850928+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d9ef2014-ab1e-4966-ae85-ce4f36597bea', '3d24b894-2a83-4586-b79a-4269cd5f0661', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"431c2463-6b7a-47cc-8a25-1555c3c073c9"', '2026-11-19 13:35:20.865278+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('54ab4ca2-1787-4b55-a5a5-eb431fe41f01', '3d24b894-2a83-4586-b79a-4269cd5f0661', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-19 13:35:20.878345+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cbad2578-7d01-4799-b4be-1342cfb9c8ca', '3d24b894-2a83-4586-b79a-4269cd5f0661', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-19 13:35:20.892856+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b275e32f-48f1-46f0-95fe-f85fedeadda5', '3d24b894-2a83-4586-b79a-4269cd5f0661', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-19 13:35:20.907474+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5e54c635-52a8-4d1b-abd8-75c40b013f7f', '3d24b894-2a83-4586-b79a-4269cd5f0661', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-19 13:35:20.907458+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a380f35e-493c-45f6-94ef-c920b1ffae29', '3d24b894-2a83-4586-b79a-4269cd5f0661', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-terminated", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:20.922861+00', 'aaf06686d0cc7c8cba8970dc79c375fc1ce1be0e307c7e044520e11bbf08b1bc');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('917f3077-bf25-4a4e-99aa-5ee65b202fba', '3d24b894-2a83-4586-b79a-4269cd5f0661', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-19 13:35:20.943416+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2d4b3652-ee54-4a8c-9378-be396597fd36', '3d24b894-2a83-4586-b79a-4269cd5f0661', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-19 13:35:22.065727+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('43ccb7e5-c57e-48cd-b0ef-810c580e17f5', '3d24b894-2a83-4586-b79a-4269cd5f0661', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-19T13:35:23.109006Z", "signalName": "express"}', '2026-11-19 13:35:22.114922+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('075a0e86-78d3-42ba-b92e-5ac6f89f37b6', '3d24b894-2a83-4586-b79a-4269cd5f0661', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-19 13:35:23.157678+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5c1b0bcc-a7d4-43c5-995d-750d02076605', '3d24b894-2a83-4586-b79a-4269cd5f0661', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-19 13:35:23.194097+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('13490a64-c50c-4870-9f4a-ca066c7b34e4', '3d24b894-2a83-4586-b79a-4269cd5f0661', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-19 13:35:23.251137+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f4165938-69b4-4e6b-8cb6-a4977c67933e', '3d24b894-2a83-4586-b79a-4269cd5f0661', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-16T13:35:23.295676Z", "signalName": "approval"}', '2026-11-19 13:35:23.302374+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6f875745-593a-4df7-81fd-5be2be3660f9', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-crashed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-19 13:35:23.676201+00', 'e00fde43b12ebd50fb69f3fe435e050d9a6300f7fc0a13f99bebde6e91498fa5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d42f8a98-92d3-43f4-8c7a-218885088aa8', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-19T13:35:23.697240Z"', '2026-11-19 13:35:23.697283+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('edd973d0-9a6d-4972-88f5-e72d37384e41', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"098dda4e-60c4-4cba-99ba-1241b1f2c1cd"', '2026-11-19 13:35:23.713904+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('692d55f9-7d65-4c9e-a3c5-fbb0fe9fc5e4', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-19 13:35:23.73175+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('81ab4668-c740-495a-a520-9d520da23e23', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-19 13:35:23.748313+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('da70f70a-2741-4112-baac-598b755567d3', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-19 13:35:23.764833+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c08d2170-0aed-4df5-a7d3-1fa8ad20404d', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-19 13:35:23.765343+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d150222c-8c3e-4784-804a-d6ddd3b5458a', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-crashed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:23.780059+00', '9afaaca3ce7e4299920c56b10762cde56e24090ad65cc2266e61c3d248a41109');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b5b984d1-4185-4466-b5b2-a86704e5583a', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-19 13:35:23.802488+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5fa208c3-c73f-4e42-9a67-4b4f18debb5d', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-19 13:35:24.841434+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d9eabea2-a1b5-4e43-8e9e-b8db8db0c792', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-19T13:35:25.889381Z", "signalName": "express"}', '2026-11-19 13:35:24.898732+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9abf01cb-ebab-454a-8981-84190e01cd73', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-19 13:35:25.933303+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ca93f18e-b68c-4d4d-9e78-ca476c50feac', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-19 13:35:25.97279+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('56c288e2-bc6c-4cae-8fb3-77290708a957', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-19 13:35:26.08648+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2d026a5a-1599-4b3c-93be-3e62908efb40', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-16T13:35:26.128984Z", "signalName": "approval"}', '2026-11-19 13:35:26.13522+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f4db3440-abe5-4638-89a6-755e1c85e266', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-19 13:35:26.208263+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fdb64be1-cd42-4d1e-b4ed-c0f53374bf82', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"plain"', '2026-11-19 13:35:26.339526+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a5800825-a71b-47b4-8c58-896aaacb7613', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"plain"', '2026-11-19 13:35:26.355503+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f639c13f-8c46-4cc5-962c-b4ea13c6b50c', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for plain", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:26.371768+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('14967969-b5c3-413f-9966-492720470fcb', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-19 13:35:26.401921+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f494e3e2-04c8-4d39-bad4-6c59d5763452', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-19 13:35:26.432218+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0846f87f-eaa3-4c5b-b175-50f8e269aabe', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-19 13:35:26.438737+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('721d7114-b647-4706-a3c7-733e53c9303c', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-19 13:35:26.462495+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1ee88497-f1a5-4504-926a-708b76484594', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-19 13:35:26.469137+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('66d6724d-1926-4a33-92d6-5c7b1c0d857d', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', NULL, '2026-11-19 13:35:26.480659+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b93e55c5-6cdd-4cf2-a57f-d3d90751dae3', 'ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-19 13:35:26.496201+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7c4e9945-19e9-4114-8800-9f1daf3f7337', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"fail-refund:x"', '2026-11-19 13:35:26.548513+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('98149350-924b-423c-a324-f3ca366f924b', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"fail-refund:x"', '2026-11-19 13:35:26.563644+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('07a2171c-788d-4394-bd95-cf4c1e3a9334', '665204b3-a46a-48a0-997b-4233388b5e79', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-19 13:35:28.662997+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b667c875-bbe0-4714-8252-4e0a36a1fb22', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:26.577044+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9fd981e8-54e4-4572-b5bb-605003001870', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-19 13:35:26.606061+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('69eea74d-5213-4585-a2e1-a27736c1dd63', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 5001, 'ACTIVITY_FAILED', 'seed-saga.refund', '{"message": "refund rejected for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:26.637612+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('64fc3140-79e3-48ce-93fb-9d7273854850', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 5000, 'COMPENSATION_STEP_FAILED', 'seed-saga.refund', '{"message": "Activity ''seed-saga.refund'' failed in workflow ''seed-saga-compensation-failed'': refund rejected for fail-refund:x", "stepName": "seed-saga.refund", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-19 13:35:26.648379+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('abb57a8e-5efc-4f9b-b77b-750e6c03de31', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-19 13:35:26.676753+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('44414705-76f8-4e69-85a0-a97faec8b85f', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-19 13:35:26.684002+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b05a9ce0-cf12-469c-9032-831e455954c0', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', '{"failedSteps": ["seed-saga.refund"], "totalCompensations": 2}', '2026-11-19 13:35:26.698471+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ba709474-9d85-4144-bdf2-ade650c14779', 'd137545a-ea66-422d-a767-e93b1d5ce89d', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-19 13:35:26.714297+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ff422926-1742-4ac8-8688-c2a759ea86c1', '9bf98392-13ab-4a46-97b0-f5e2ffc68d06', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"crash-compensation"', '2026-11-19 13:35:26.820645+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1b026cc6-fd0a-4511-bfcc-35b8d0081cc9', '9bf98392-13ab-4a46-97b0-f5e2ffc68d06', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"crash-compensation"', '2026-11-19 13:35:26.838938+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('904244ba-6b6a-4afa-ab3e-106d23377f0d', '9bf98392-13ab-4a46-97b0-f5e2ffc68d06', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for crash-compensation", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:26.853732+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c8576f63-85a8-435c-a2b5-b8cbd8a87a11', '9bf98392-13ab-4a46-97b0-f5e2ffc68d06', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-19 13:35:26.88443+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b5b9b588-92ac-493e-84e7-8a251a896f86', '9bf98392-13ab-4a46-97b0-f5e2ffc68d06', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-19 13:35:26.913324+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5afa2f68-6c5d-47d0-bcef-900e18c71820', '9bf98392-13ab-4a46-97b0-f5e2ffc68d06', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-19 13:35:26.91952+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3586a3a4-8e07-40b5-955d-5ce93081b41b', '6ff5f759-3f46-4c57-bc86-bdcada9aa543', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:27.134213+00', 'ae55ac050582d8056dcfc24a102fc318460211527edd40f09779fa2b2de4f2ec');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('63751480-a4f2-4a49-b014-b49f4ed1d0b5', '6ff5f759-3f46-4c57-bc86-bdcada9aa543', 2, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-19 13:35:27.154746+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('77a9ca73-5bc2-43a2-8b29-e5fd46c4089d', '762679c9-e844-4067-bf24-481eb6631fcc', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:27.351936+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('087b25bb-2d8b-44e6-af43-cee6bb8f957f', '762679c9-e844-4067-bf24-481eb6631fcc', 2, 'ACTIVITY_COMPLETED', 'seed-failing.alert', '"alerted:seed-alert-failed"', '2026-11-19 13:35:27.373224+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7843edb6-43f3-4ac2-b8ba-74db7d9a6e7e', '762679c9-e844-4067-bf24-481eb6631fcc', 3, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-19 13:35:27.398812+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e849f76b-14e2-41f1-8747-22ba0b158250', '6bf5dd05-39cf-4290-bf47-5958ae7c3eb4', 1, 'ACTIVITY_COMPLETED', 'seed-typed.exactAmount', '12345678901234567890.123456789', '2026-11-19 13:35:27.509094+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('34423895-2fd0-40ee-acdb-655487828ecd', '313ba5d1-c090-4472-8091-335c04e46701', 1, 'ACTIVITY_COMPLETED', 'seed-typed.localStamp', '"2026-11-12T10:00:00+02:00"', '2026-11-19 13:35:27.529807+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('54d3c5e3-d17c-413f-87a9-38c8bbb2a65a', 'b6f1d64b-2357-449b-acfb-8d824979343c', 1, 'ACTIVITY_COMPLETED', 'seed-typed.tags', '["delta", "beta", "gamma", "alpha"]', '2026-11-19 13:35:27.571059+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('aba3814d-6291-4e96-9ea1-b59814d0d787', '6bf5dd05-39cf-4290-bf47-5958ae7c3eb4', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-16T13:35:27.584336Z", "signalName": "go"}', '2026-11-19 13:35:27.596889+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b5d2ed36-5978-4c3d-b3b2-60b72ff1933d', '313ba5d1-c090-4472-8091-335c04e46701', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-16T13:35:27.596590Z", "signalName": "go"}', '2026-11-19 13:35:27.607896+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bff5ed23-fe1f-46e4-8ec6-fc5dbb9869ff', 'b6f1d64b-2357-449b-acfb-8d824979343c', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-16T13:35:27.625432Z", "signalName": "go"}', '2026-11-19 13:35:27.642534+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b5486c32-7583-4721-9b07-8eac01e7c19e', 'c2a36754-ec37-4e34-99c9-5b758112be87', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"parked"', '2026-11-19 13:35:28.297304+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dcc87fd7-f102-42e1-ae36-565b5f8a6ffc', 'c2a36754-ec37-4e34-99c9-5b758112be87', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"parked"', '2026-11-19 13:35:28.315681+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ac375c25-8ee9-44a2-a676-d626caf370ec', 'c2a36754-ec37-4e34-99c9-5b758112be87', 3, 'TIMER_SCHEDULED', '$maestro:awaitSignal:ship-now', '{"timeout": "PT87600H", "timerId": "await-signal-3", "deadline": "2036-11-16T13:35:28.350167Z", "signalName": "ship-now"}', '2026-11-19 13:35:28.358401+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1f734a65-51e7-4b88-b2ba-95ad97d14b95', '665204b3-a46a-48a0-997b-4233388b5e79', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-timer", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-19 13:35:28.570111+00', '558ccdf6e173c89faa47667cf30f6a9d89ab597ce095afe18363ca028ba5882d');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('91b75614-797a-4f51-a64e-c4d65e057024', '665204b3-a46a-48a0-997b-4233388b5e79', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-19T13:35:28.588096Z"', '2026-11-19 13:35:28.588156+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('62a98126-a658-429f-aaa2-26b9b335d9da', '665204b3-a46a-48a0-997b-4233388b5e79', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"c8b9dbf5-0aa4-4f01-bd7b-a65b5a805d5d"', '2026-11-19 13:35:28.607433+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('41a6de40-4823-4946-a70b-0c12ca149db7', '665204b3-a46a-48a0-997b-4233388b5e79', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-19 13:35:28.627006+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c744f379-a448-4ad2-bc30-f8f6d5004533', '665204b3-a46a-48a0-997b-4233388b5e79', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-19 13:35:28.645145+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('49840143-3192-4fe6-a6eb-cad93c80937c', '665204b3-a46a-48a0-997b-4233388b5e79', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-19 13:35:28.661559+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7c072a2e-946e-49fe-ada0-fd33f342123d', '665204b3-a46a-48a0-997b-4233388b5e79', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-timer", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:28.678751+00', 'd0eae109e2ce1eb172aa9c675c463121146e996ead38dee0886c8252d36a77ac');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f8956344-54fe-4975-bb3b-eea8c035cc8b', '665204b3-a46a-48a0-997b-4233388b5e79', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT10M"}', '2026-11-19 13:35:28.701605+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cd79a2d1-467d-4d4d-a399-cc018159a103', '3399b8e1-efed-41a6-bd86-271f34f78b97', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-express", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-19 13:35:28.794195+00', '354fbdbc6a8e2e95b3863f5a5582854a39445fa66416df79e67c44532ca4e457');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0eac291e-b1b1-449f-a4fb-d22c34391f39', '3399b8e1-efed-41a6-bd86-271f34f78b97', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-19T13:35:28.809895Z"', '2026-11-19 13:35:28.809954+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('691affe2-635c-402f-8731-ef7339b82744', '3399b8e1-efed-41a6-bd86-271f34f78b97', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"60a9988e-bbab-496b-b4e8-24bd6720c856"', '2026-11-19 13:35:28.825634+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6a7de4ed-b518-4ac7-af4e-9dce4781a64b', '3399b8e1-efed-41a6-bd86-271f34f78b97', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-19 13:35:28.841568+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('15551159-9cf6-4e46-b3a3-d1779c4139c5', '3399b8e1-efed-41a6-bd86-271f34f78b97', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-19 13:35:28.858281+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ee79045b-f9f8-4409-bf6f-36da57e40018', '3399b8e1-efed-41a6-bd86-271f34f78b97', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-19 13:35:28.873541+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('290446f3-1bae-4274-b21a-57e10c386cba', '3399b8e1-efed-41a6-bd86-271f34f78b97', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-19 13:35:28.87354+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('659c450f-1060-4ae5-92fa-068db0316ac8', '3399b8e1-efed-41a6-bd86-271f34f78b97', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-express", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_1.SeededHistoryWorkflows$SeedFailure"}', '2026-11-19 13:35:28.889357+00', 'e487ac6f64544e0840cd7f51f5490bd78e0b48293e7d15f91f57aa2ca64c9100');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f54e993a-c8a2-4510-b820-5ad3fbc26986', '3399b8e1-efed-41a6-bd86-271f34f78b97', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-19 13:35:28.910006+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c864d74d-cfd2-48d9-ab98-274f22694f76', '3399b8e1-efed-41a6-bd86-271f34f78b97', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-19 13:35:30.119348+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ed2d6951-f019-4fdf-9446-563578b6fa82', '3399b8e1-efed-41a6-bd86-271f34f78b97', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT10M", "timerId": "await-signal-8004", "deadline": "2026-11-19T13:45:30.170081Z", "signalName": "express"}', '2026-11-19 13:35:30.178235+00', NULL);


--
-- Data for Name: maestro_workflow_instance; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 'seed-completed', 'f2303d9d-310a-4315-8978-548c3ecaae6a', 'SeedOrderWorkflow', 'default', 'COMPLETED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-completed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"note": "note for seed-completed", "token": "c2c5b782-59e6-4701-8a39-81dbe9cf3c65", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-19T13:35:15.262647Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', 'upgrade-fixture', 8011, '2026-11-19 13:35:15.153825+00', '2026-11-19 13:35:17.903849+00', '2026-11-19 13:35:17.903849+00', 9, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('db1a85ac-0707-45e0-9ea9-95a362739b54', 'seed-parked-approval', '63306110-6e3d-4f9f-a6e3-c240d1cdd653', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-approval", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-19 13:35:17.935107+00', NULL, '2026-11-19 13:35:20.565421+00', 7, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('3d24b894-2a83-4586-b79a-4269cd5f0661', 'seed-terminated', 'ce913924-6883-4025-a932-6d98f38d62e7', 'SeedOrderWorkflow', 'default', 'TERMINATED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-terminated", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"reason": "seeded termination", "terminatedBy": "admin"}', 'upgrade-fixture', 0, '2026-11-19 13:35:20.808836+00', '2026-11-19 13:35:23.5641+00', '2026-11-19 13:35:23.5641+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 'seed-crashed', '75799f6b-50b0-4832-ad76-36a1966111f2', 'SeedOrderWorkflow', 'default', 'RUNNING', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-crashed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-19 13:35:23.642786+00', NULL, '2026-11-19 13:35:26.232243+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('ba18b93f-5177-43e8-b2aa-cffdfcadbaa6', 'seed-saga-compensated', 'e90142f5-dc07-4bea-abeb-ed6cc937d395', 'SeedSagaWorkflow', 'default', 'FAILED', '"plain"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-19 13:35:26.314376+00', '2026-11-19 13:35:26.496201+00', '2026-11-19 13:35:26.496201+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('d137545a-ea66-422d-a767-e93b1d5ce89d', 'seed-saga-compensation-failed', '7e8048df-e807-4fc9-a810-76473840d800', 'SeedSagaWorkflow', 'default', 'FAILED', '"fail-refund:x"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-19 13:35:26.526041+00', '2026-11-19 13:35:26.714297+00', '2026-11-19 13:35:26.714297+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('9bf98392-13ab-4a46-97b0-f5e2ffc68d06', 'seed-compensating-crashed', '3ffd966a-2ac7-4cc7-8758-e04d446172e2', 'SeedSagaWorkflow', 'default', 'COMPENSATING', '"crash-compensation"', NULL, 'upgrade-fixture', 0, '2026-11-19 13:35:26.789291+00', NULL, '2026-11-19 13:35:26.875642+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('6ff5f759-3f46-4c57-bc86-bdcada9aa543', 'seed-activity-failed', 'b1851ee9-6f82-4e6d-899c-152801c9695a', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-activity-failed", "alertBeforeRethrow": false}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 1, '2026-11-19 13:35:27.008079+00', '2026-11-19 13:35:27.154746+00', '2026-11-19 13:35:27.154746+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('762679c9-e844-4067-bf24-481eb6631fcc', 'seed-alert-failed', '4771c5fb-70fa-442d-9d1f-da03f11ab8ab', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-alert-failed", "alertBeforeRethrow": true}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 2, '2026-11-19 13:35:27.223846+00', '2026-11-19 13:35:27.398812+00', '2026-11-19 13:35:27.398812+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('6bf5dd05-39cf-4290-bf47-5958ae7c3eb4', 'seed-wp1-decimal', '6ca97a32-3bae-4ddb-9bda-a53efcccd0ac', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "DECIMAL"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-19 13:35:27.479134+00', NULL, '2026-11-19 13:35:27.625541+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('313ba5d1-c090-4472-8091-335c04e46701', 'seed-wp1-offset', '6ca1d2e1-0894-4505-8c08-4a8250c512f9', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "OFFSET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-19 13:35:27.498461+00', NULL, '2026-11-19 13:35:27.642359+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('b6f1d64b-2357-449b-acfb-8d824979343c', 'seed-wp1-set', '3dae6d29-45ed-4728-bd6b-1cb3c199f77e', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "SET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-19 13:35:27.52069+00', NULL, '2026-11-19 13:35:27.695139+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('c2a36754-ec37-4e34-99c9-5b758112be87', 'seed-saga-parked', '442680b9-da87-4e64-86cd-8597c72da177', 'SeedParkedSagaWorkflow', 'default', 'WAITING_SIGNAL', '"parked"', NULL, 'upgrade-fixture-saga', 0, '2026-11-19 13:35:28.24987+00', NULL, '2026-11-19 13:35:28.373587+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('665204b3-a46a-48a0-997b-4233388b5e79', 'seed-parked-timer', 'f7463158-b81c-4b76-a46e-860448ab5c56', 'SeedOrderWorkflow', 'default', 'WAITING_TIMER', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-timer", "napMillis": 600000, "expressWaitMillis": 1000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-19 13:35:28.546563+00', NULL, '2026-11-19 13:35:28.716911+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('3399b8e1-efed-41a6-bd86-271f34f78b97', 'seed-parked-express', '52a57e7b-b8a4-4f99-ab29-2c4e283fae7c', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-express", "napMillis": 1000, "expressWaitMillis": 600000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-19 13:35:28.771459+00', NULL, '2026-11-19 13:35:30.195073+00', 3, 0);


--
-- Data for Name: maestro_workflow_signal; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('31b121c3-1961-4af4-95ac-9743225fe423', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 'seed-completed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-19 13:35:17.796376+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('a649ffd1-7acc-477c-b540-9fa0a20bee89', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 'seed-completed', 'note', '"note for seed-completed"', true, '2026-11-19 13:35:15.22853+00', NULL, 'client:seed-completed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('6067cc90-712e-48fc-838c-c7b9f6df8345', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 'seed-parked-approval', 'note', '"note for seed-parked-approval"', false, '2026-11-19 13:35:17.963979+00', NULL, 'client:seed-parked-approval:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('d32d505d-da4d-409d-8258-f02aa5e3326c', '3d24b894-2a83-4586-b79a-4269cd5f0661', 'seed-terminated', 'note', '"note for seed-terminated"', false, '2026-11-19 13:35:20.842164+00', NULL, 'client:seed-terminated:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('32e1b431-3108-4e46-abd4-dd671b65b5a2', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 'seed-crashed', 'note', '"note for seed-crashed"', false, '2026-11-19 13:35:23.688046+00', NULL, 'client:seed-crashed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('c539b313-2a77-45c1-b282-9721e9945961', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 'seed-crashed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-19 13:35:26.191698+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('968efab7-698b-42d7-9aff-0888849791c2', NULL, 'seed-orphan-target', 'approval', '{"level": 1, "approver": "capture"}', false, '2026-11-19 13:35:27.4605+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('bc08c807-a144-4c74-a54b-457d3412ee33', NULL, 'seed-orphan-target', 'note', '"note for seed-orphan-target"', false, '2026-11-19 13:35:27.477074+00', NULL, 'client:seed-orphan-target:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('8d69c1c7-096a-4503-9736-ea6d7eef2df1', '665204b3-a46a-48a0-997b-4233388b5e79', 'seed-parked-timer', 'note', '"note for seed-parked-timer"', false, '2026-11-19 13:35:28.577992+00', NULL, 'client:seed-parked-timer:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('a677fb06-8111-4739-b180-10fda680aaca', '3399b8e1-efed-41a6-bd86-271f34f78b97', 'seed-parked-express', 'note', '"note for seed-parked-express"', false, '2026-11-19 13:35:28.800842+00', NULL, 'client:seed-parked-express:note');


--
-- Data for Name: maestro_workflow_timer; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('d29832b8-f563-4af4-86e5-405cf193dfad', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 'seed-completed', 'sleep-8002', '2026-11-19 13:35:16.391121+00', 'FIRED', '2026-11-19 13:35:15.391338+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('51490a3f-258e-4c74-9ab5-5a06d8172656', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 'seed-completed', 'await-signal-8004', '2026-11-19 13:35:17.529638+00', 'FIRED', '2026-11-19 13:35:16.529665+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('bf83e76b-8be2-49f2-a702-2fe4b94eeb58', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 'seed-completed', 'sleep-8006', '2026-11-20 13:35:17.617498+00', 'CANCELLED', '2026-11-19 13:35:17.617502+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('fa8d0f4b-50b3-4f07-bd05-52b0b385206a', '087d36ff-ad34-4713-a4a4-8f4b75ea2da7', 'seed-completed', 'await-signal-8008', '2036-11-16 13:35:17.730215+00', 'CANCELLED', '2026-11-19 13:35:17.73023+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('14054f9d-7cc0-4098-ab83-852328503319', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 'seed-parked-approval', 'sleep-8002', '2026-11-19 13:35:19.061402+00', 'FIRED', '2026-11-19 13:35:18.061406+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('b25c3c5e-9207-4e2e-8b34-b9543325c1b0', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 'seed-parked-approval', 'await-signal-8004', '2026-11-19 13:35:20.339505+00', 'FIRED', '2026-11-19 13:35:19.339562+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('70e37b85-2c0d-4346-9fb6-d36760fb6c91', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 'seed-parked-approval', 'sleep-8006', '2026-11-20 13:35:20.421527+00', 'CANCELLED', '2026-11-19 13:35:20.421531+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('d5f96321-583b-40f5-ba82-06ac85851657', 'db1a85ac-0707-45e0-9ea9-95a362739b54', 'seed-parked-approval', 'await-signal-8008', '2036-11-16 13:35:20.543783+00', 'PENDING', '2026-11-19 13:35:20.543801+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('a8662661-2bcb-4b00-8602-83ab41a7c21a', '3d24b894-2a83-4586-b79a-4269cd5f0661', 'seed-terminated', 'sleep-8002', '2026-11-19 13:35:21.936853+00', 'FIRED', '2026-11-19 13:35:20.936857+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('d32104c6-e8ff-4736-999d-d71fd5414088', '3d24b894-2a83-4586-b79a-4269cd5f0661', 'seed-terminated', 'await-signal-8004', '2026-11-19 13:35:23.109006+00', 'FIRED', '2026-11-19 13:35:22.109024+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('db502ac6-dd9a-4e79-805b-1f7e8dc3a8b1', '3d24b894-2a83-4586-b79a-4269cd5f0661', 'seed-terminated', 'sleep-8006', '2026-11-20 13:35:23.187737+00', 'CANCELLED', '2026-11-19 13:35:23.187742+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('22d49397-dad2-425a-95d6-ef850bf9053b', '3d24b894-2a83-4586-b79a-4269cd5f0661', 'seed-terminated', 'await-signal-8008', '2036-11-16 13:35:23.295676+00', 'PENDING', '2026-11-19 13:35:23.295725+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('28772555-1f6e-413f-9635-a895ec5a8a7b', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 'seed-crashed', 'sleep-8002', '2026-11-19 13:35:24.795236+00', 'FIRED', '2026-11-19 13:35:23.79524+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('0e6eb8ec-1b1e-4701-aeb8-ffe255e0c7e5', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 'seed-crashed', 'await-signal-8004', '2026-11-19 13:35:25.889381+00', 'FIRED', '2026-11-19 13:35:24.889418+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('bae76cb4-651d-448e-8a83-e26a3663df15', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 'seed-crashed', 'sleep-8006', '2026-11-20 13:35:25.966295+00', 'CANCELLED', '2026-11-19 13:35:25.966299+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('f9e0f4ec-8f0e-403b-b36a-c4a1c77e5dbd', '3fa7a6a5-4dcc-49bc-a813-0ef76de8250f', 'seed-crashed', 'await-signal-8008', '2036-11-16 13:35:26.128984+00', 'CANCELLED', '2026-11-19 13:35:26.129015+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('0ec4d2e7-06f9-46f9-a6a9-2a408f2a5868', '6bf5dd05-39cf-4290-bf47-5958ae7c3eb4', 'seed-wp1-decimal', 'await-signal-2', '2036-11-16 13:35:27.584336+00', 'PENDING', '2026-11-19 13:35:27.584379+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('26031b2e-6570-45b0-8e18-04d8bc23cc0d', '313ba5d1-c090-4472-8091-335c04e46701', 'seed-wp1-offset', 'await-signal-2', '2036-11-16 13:35:27.59659+00', 'PENDING', '2026-11-19 13:35:27.596609+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('592db2a9-d6b3-4f00-81fd-b530abc1b629', 'b6f1d64b-2357-449b-acfb-8d824979343c', 'seed-wp1-set', 'await-signal-2', '2036-11-16 13:35:27.625432+00', 'PENDING', '2026-11-19 13:35:27.625456+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('6cda9ee1-53ec-4c71-b19a-99a63b6175eb', 'c2a36754-ec37-4e34-99c9-5b758112be87', 'seed-saga-parked', 'await-signal-3', '2036-11-16 13:35:28.350167+00', 'PENDING', '2026-11-19 13:35:28.350183+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('e3f8d8bc-16c4-49f6-b254-6d3e0ff1800d', '665204b3-a46a-48a0-997b-4233388b5e79', 'seed-parked-timer', 'sleep-8002', '2026-11-19 13:45:28.693444+00', 'PENDING', '2026-11-19 13:35:28.693449+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('b36a9c50-fc44-4f47-8a2a-4ca7ac2dc880', '3399b8e1-efed-41a6-bd86-271f34f78b97', 'seed-parked-express', 'sleep-8002', '2026-11-19 13:35:29.903742+00', 'FIRED', '2026-11-19 13:35:28.903747+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('d2dcc923-899a-4637-97f9-86805fcd04f3', '3399b8e1-efed-41a6-bd86-271f34f78b97', 'seed-parked-express', 'await-signal-8004', '2026-11-19 13:45:30.170081+00', 'PENDING', '2026-11-19 13:35:30.1701+00');


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


