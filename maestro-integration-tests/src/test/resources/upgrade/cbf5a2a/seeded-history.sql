--
-- PostgreSQL database dump
--


-- Dumped from database version 16.14
-- Dumped by pg_dump version 16.14

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

INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', 'create maestro schema', 'SQL', 'V1__create_maestro_schema.sql', 1894477780, 'test', '2026-11-12 16:52:02.803132', 43, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (2, '2', 'create indexes', 'SQL', 'V2__create_indexes.sql', 1117887476, 'test', '2026-11-12 16:52:02.880606', 13, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (3, '3', 'timer lookup index', 'SQL', 'V3__timer_lookup_index.sql', -511219852, 'test', '2026-11-12 16:52:02.909463', 6, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (4, '4', 'signal trace context', 'SQL', 'V4__signal_trace_context.sql', -444138901, 'test', '2026-11-12 16:52:02.928174', 6, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (5, '5', 'signal received at default', 'SQL', 'V5__signal_received_at_default.sql', -1319518420, 'test', '2026-11-12 16:52:02.945271', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (6, '6', 'instance lease epoch', 'SQL', 'V6__instance_lease_epoch.sql', -1501028209, 'test', '2026-11-12 16:52:02.956474', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (7, '7', 'workflow event args hash', 'SQL', 'V7__workflow_event_args_hash.sql', 1141881341, 'test', '2026-11-12 16:52:02.968606', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (8, '8', 'retention indexes', 'SQL', 'V8__retention_indexes.sql', 1576234141, 'test', '2026-11-12 16:52:02.983031', 10, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (9, '9', 'signal message id', 'SQL', 'V9__signal_message_id.sql', 168428632, 'test', '2026-11-12 16:52:03.002475', 8, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (10, '100', 'maestro lock postgres', 'SQL', 'V100__maestro_lock_postgres.sql', 1037217973, 'test', '2026-11-12 16:52:03.023461', 13, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (11, '101', 'recovery scan index', 'SQL', 'V101__recovery_scan_index.sql', 9197330, 'test', '2026-11-12 16:52:03.044618', 5, true);


--
-- Data for Name: maestro_workflow_event; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('59ecb204-7de8-4db1-85ff-fb97d1fd443e', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-completed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-12 14:52:03.258758+00', '61120492bde41a623abae6da6acef1477c1c44ac5ccf3e854f20c04625ce8fad');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bf37e420-19a5-466d-b4b8-3ea40fdcd268', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-12T14:52:03.281538Z"', '2026-11-12 14:52:03.281703+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('da0f1cb5-2b68-42bb-ad25-e46269ed8e81', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"5b9ea257-54fc-4365-b32a-59399e101246"', '2026-11-12 14:52:03.303106+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('75928cc7-33c1-4c14-a075-157742ee9c79', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-12 14:52:03.319477+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('713ba3ef-5485-45a4-980d-b2a1bee9c956', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-12 14:52:03.338744+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('199ba9a1-f38c-4622-838f-0329d164e8f8', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-12 14:52:03.359135+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4e8e34da-28ef-4a97-a34a-c548ac5626f5', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-12 14:52:03.35913+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fd8ce8e9-0601-4d8b-815b-908e9eb83a00', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-completed", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:03.38614+00', '2ecf14aafdc0c4016c28daa53e863728959afca02f9b5503a122173ab2f30a04');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f8de55d8-fad4-4381-a4b5-7662d0e1f0ce', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-12 14:52:03.414245+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7cd5145b-ef10-490e-96e2-160849da554e', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-12 14:52:04.523739+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3c8419b7-31e9-43b3-ab94-df302570ea56', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-12T14:52:05.575560Z", "signalName": "express"}', '2026-11-12 14:52:04.584207+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('25d58d71-e505-4594-8a2b-46e39f9909b5', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-12 14:52:05.622431+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('071df7ef-43ba-4225-90a3-242c91facbeb', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-12 14:52:05.667033+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('21327809-24f4-486f-88c5-bb98e295bbeb', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-12 14:52:05.779005+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b6b78e55-d8a7-4819-a715-fd125deec252', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-09T14:52:05.822910Z", "signalName": "approval"}', '2026-11-12 14:52:05.829883+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e36a1766-acf3-4031-ab48-f5c32a8da478', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-12 14:52:05.919327+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('18630bc6-7657-4775-b8a4-e4309338c473', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8010, 'ACTIVITY_COMPLETED', 'seed-order.charge', '{"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}', '2026-11-12 14:52:05.96746+00', '4f7957db6f7b556032ab536a356340cca8b51fb6d1726e046db09ede5858e8e5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('03a09de8-654d-42f2-bb8b-2c7dda4d823f', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8011, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:note', '"note for seed-completed"', '2026-11-12 14:52:05.992619+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c3ad27a5-4309-4abf-980f-d065626db524', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 8012, 'WORKFLOW_COMPLETED', NULL, '{"note": "note for seed-completed", "token": "5b9ea257-54fc-4365-b32a-59399e101246", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-12T14:52:03.281538Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', '2026-11-12 14:52:06.013789+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2da723ac-f8ca-4346-adbf-43db3f0a247d', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-approval", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-12 14:52:06.12596+00', 'b6195227c62ead98ed1b3b177bfa8f2bb53caca058a40f3326a52ea2b6c24a86');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4f62a5a1-3acc-4dc2-8f1a-c04efc463127', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-12T14:52:06.142906Z"', '2026-11-12 14:52:06.142962+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c5cc0384-0bfa-47f3-a699-758c45f13c53', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"5089ca13-b1e3-4c19-971b-f614c788fd64"', '2026-11-12 14:52:06.160208+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('beb55b20-e8e5-47f8-b3f2-0f99daef153b', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-12 14:52:06.178143+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f478831b-a393-4c79-bc1e-c01597c1d3f9', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-12 14:52:06.197748+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7ce2376a-cf87-4f7f-929b-78739d1f5d74', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-12 14:52:06.215244+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9657e157-0443-414c-8f82-73579696e026', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-12 14:52:06.215241+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f5d85c6f-cb49-45d6-b790-5387b289da9d', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-approval", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:06.234448+00', 'c2607e49b4ab277032d58069d85787ad440a6041aefa57dab52b717a1cc9b16f');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('21634175-e162-48d1-b483-aa797f03d2a0', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-12 14:52:06.261696+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d7dcf188-b989-4185-8dd6-32c2a097f73b', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-12 14:52:07.29668+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2a3c8299-049b-4933-9609-8e019aa437a7', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-12T14:52:08.341029Z", "signalName": "express"}', '2026-11-12 14:52:07.34808+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('33332ea6-ce29-4661-9547-5606ace0adb5', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-12 14:52:08.379638+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('199f44f1-d5a6-489c-9857-93a188d6e5b5', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-12 14:52:08.432605+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('56722200-97f8-4795-a1b2-478531eb8db2', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-12 14:52:08.571459+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7a93998b-ae34-491c-b70f-546c87ac3bd8', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-09T14:52:08.619137Z", "signalName": "approval"}', '2026-11-12 14:52:08.62615+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('688ee736-f07e-414c-809a-8a21949b84a9', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-terminated", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-12 14:52:08.89164+00', '785d82a01f4a3938f2f8d5ae7601a6d490bf7cfd73d10ee4ce728ea649d991cb');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('56ee5ec6-0d36-4bcb-a185-efe1a862b01d', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-12T14:52:08.907308Z"', '2026-11-12 14:52:08.90736+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7a12c09f-c6da-4f85-8b81-4856ebfa76ea', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"9f3ac0fc-2b3b-4ebe-9d1e-ece9a2b6c7fe"', '2026-11-12 14:52:08.921558+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b217e83d-c689-4f0b-8f1f-2ba144b0f5d1', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-12 14:52:08.937368+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5d3d2474-6010-4a91-92d4-5b369996667c', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-12 14:52:08.954649+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('66822cdc-7a00-41cb-9595-9e37cae00422', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-12 14:52:08.970816+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2fffc8f8-e52a-4185-bfc0-e85d12428269', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-12 14:52:08.970816+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6d6c42f6-bc07-4e2e-942e-4c4b64437c63', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-terminated", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:08.989012+00', 'aaf06686d0cc7c8cba8970dc79c375fc1ce1be0e307c7e044520e11bbf08b1bc');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cf285612-29f7-40ff-83d5-815463bed108', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-12 14:52:09.011742+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bd617118-fbcf-42ba-9d0a-b55dddd1cb49', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-12 14:52:10.059764+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('73dde890-94f8-4e37-8da7-9d72258058fd', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-12T14:52:11.122071Z", "signalName": "express"}', '2026-11-12 14:52:10.129761+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e97ba240-753e-44a2-abcf-7aa29206098b', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-12 14:52:11.161393+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ba9f90a3-6d40-4538-af5f-37b871dd4685', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-12 14:52:11.201487+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6107db86-b8d3-47da-9003-5d2c2188b56c', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-12 14:52:11.263368+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9a735367-172b-4aaf-b7a3-d09c321eaa55', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-09T14:52:11.306027Z", "signalName": "approval"}', '2026-11-12 14:52:11.313514+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fa6d13a2-ccbc-453c-924f-ed2b4796127f', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-crashed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-12 14:52:11.657028+00', 'e00fde43b12ebd50fb69f3fe435e050d9a6300f7fc0a13f99bebde6e91498fa5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('56c0c7bd-af63-4224-9ef5-8e30a13cdae2', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-12T14:52:11.675796Z"', '2026-11-12 14:52:11.675855+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e3274e16-27fd-4e6f-a5bc-c6e5a0521896', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"3c7939f4-9168-4cc2-8dac-19ade621a469"', '2026-11-12 14:52:11.692668+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dc2089f2-1e79-41b4-be47-9acb7cb8966b', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-12 14:52:11.711126+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1d41c077-ddc0-42b6-bbfa-a52879de37dd', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-12 14:52:11.731231+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fea4d7c7-c21c-4409-88e5-89b8e7e16a63', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-12 14:52:11.75285+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5978f510-b655-4aa0-bb11-78b1c749fe19', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-12 14:52:11.753769+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b89de3be-7578-468e-ab68-5f2ea906acbb', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-crashed", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:11.77259+00', '9afaaca3ce7e4299920c56b10762cde56e24090ad65cc2266e61c3d248a41109');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4bdf38fa-cd51-4c06-a715-04d187f97229', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-12 14:52:11.795984+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('acbfaab3-f9e3-48b5-9abb-050071b9c350', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-12 14:52:13.083006+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ff47c47a-1d29-4a2d-b7a1-db18112cec22', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-12T14:52:14.139951Z", "signalName": "express"}', '2026-11-12 14:52:13.149353+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('95bfd3cf-5d93-41f2-addd-0ef4b37f0b01', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-12 14:52:14.343329+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0f88ce7c-12ac-49bb-a180-5d8c066800c6', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-12 14:52:14.390946+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0da5cee5-d98d-4e67-8048-d9eb3ff3b33b', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-12 14:52:14.498965+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('635e3106-41de-405d-a2b8-a842dd535581', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-09T14:52:14.564414Z", "signalName": "approval"}', '2026-11-12 14:52:14.573785+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0b11542f-feca-412b-8652-96201bcb644e', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-12 14:52:14.623826+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b041a48a-8982-4e8c-abd0-067838bf429c', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"plain"', '2026-11-12 14:52:14.819031+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e605b0ac-4299-4edc-bbf4-2071fd424197', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"plain"', '2026-11-12 14:52:14.839994+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b3ea63f1-6cde-4158-a902-d0d022ce7086', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for plain", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:14.864659+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9b74b622-7424-416b-b0ac-9bdf63fd1217', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-12 14:52:14.95317+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a96f9ca3-de2e-46fc-8f2c-118d9f7c40b9', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-12 14:52:15.078786+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e0c77e89-141c-4bb6-b11c-9c78eabed6ad', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-12 14:52:15.097753+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b4e78243-efd7-45ef-bc94-ddc982115e11', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-12 14:52:15.137091+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e1f0dcb9-d61a-4bb8-854e-065781e14983', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-12 14:52:15.144244+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f03d1b74-3a2d-4fa3-88b1-5a4ce6170c8b', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', NULL, '2026-11-12 14:52:15.158305+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e5a7a26e-e855-4cfb-9742-433bc243e5df', '0fa297a6-4650-4a75-bc03-b0b8d19bd020', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-12 14:52:15.173935+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4d1102a4-c8fc-4b7e-b991-bb6723a7fb69', '6a4e6b11-4398-48e3-b730-42ec60507b07', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"fail-refund:x"', '2026-11-12 14:52:15.265771+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e281f5f2-e32e-47d6-af47-09482be88746', '6a4e6b11-4398-48e3-b730-42ec60507b07', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"fail-refund:x"', '2026-11-12 14:52:15.280935+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5151d83e-00c2-4fa0-ace6-ec869b89639f', '6a4e6b11-4398-48e3-b730-42ec60507b07', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:15.29593+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('93cc6b25-0e5a-4a15-99e1-928d728f3495', '6a4e6b11-4398-48e3-b730-42ec60507b07', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-12 14:52:15.328486+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ecf8e264-332f-4313-844a-f52c1b3107a2', '6a4e6b11-4398-48e3-b730-42ec60507b07', 5001, 'ACTIVITY_FAILED', 'seed-saga.refund', '{"message": "refund rejected for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:15.484294+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8de24ba3-a835-4959-a3f9-5df01d88468c', '6a4e6b11-4398-48e3-b730-42ec60507b07', 5000, 'COMPENSATION_STEP_FAILED', 'seed-saga.refund', '{"message": "Activity ''seed-saga.refund'' failed in workflow ''seed-saga-compensation-failed'': refund rejected for fail-refund:x", "stepName": "seed-saga.refund", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-12 14:52:15.510431+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5ed1d85d-00d9-4fba-b5e5-1e1dfd536fff', '6a4e6b11-4398-48e3-b730-42ec60507b07', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-12 14:52:15.558865+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('912cb7c7-f3fb-482e-947d-ccf20dea6901', '6a4e6b11-4398-48e3-b730-42ec60507b07', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-12 14:52:15.56636+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3fbff90f-c2ea-49b1-8f33-48ca9af0802a', '6a4e6b11-4398-48e3-b730-42ec60507b07', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', '{"failedSteps": ["seed-saga.refund"], "totalCompensations": 2}', '2026-11-12 14:52:15.583182+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a5bdae1e-9320-4ad9-90cf-d9602ef9cf76', '6a4e6b11-4398-48e3-b730-42ec60507b07', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-12 14:52:15.59999+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b3293556-3312-41f6-9ba9-b8dbeeb7e0e3', '5ca8fcfb-041a-4386-9df3-cd85343e0106', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"crash-compensation"', '2026-11-12 14:52:15.749471+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3445120d-bf9b-49ab-9341-f1b8bb83872a', '5ca8fcfb-041a-4386-9df3-cd85343e0106', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"crash-compensation"', '2026-11-12 14:52:15.767427+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ee7bfd7e-fab2-424c-aac1-eced85f079b0', '5ca8fcfb-041a-4386-9df3-cd85343e0106', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for crash-compensation", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:15.792694+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c9f6ce4d-2a92-441d-85d5-f63d0fc3ae86', '5ca8fcfb-041a-4386-9df3-cd85343e0106', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-12 14:52:15.834201+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f3a12dcc-fbf1-428f-ac79-12f2a0a312f6', '5ca8fcfb-041a-4386-9df3-cd85343e0106', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-12 14:52:15.875855+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1f9984ad-3af1-483e-b5de-5ac8e4d52dad', '5ca8fcfb-041a-4386-9df3-cd85343e0106', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-12 14:52:15.882359+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0b08f67d-a9ba-449a-a387-db163fbce48b', '4ed52333-771c-4c76-bda2-3a0e13394668', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:16.121847+00', 'ae55ac050582d8056dcfc24a102fc318460211527edd40f09779fa2b2de4f2ec');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('be46cbeb-3c72-4c3d-8af2-15fa4b4fffd3', '4ed52333-771c-4c76-bda2-3a0e13394668', 2, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-12 14:52:16.23925+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('be86700f-6d62-411a-a9ee-4f01e7fc8f9a', 'ee7f7279-cd44-4965-8425-5f14d0aaceed', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:16.413423+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7cfff0ee-3951-4d30-90ce-196ac94bcb43', 'ee7f7279-cd44-4965-8425-5f14d0aaceed', 2, 'ACTIVITY_COMPLETED', 'seed-failing.alert', '"alerted:seed-alert-failed"', '2026-11-12 14:52:16.431604+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d77d7bd1-06a5-4166-bc48-5d3c6bece015', 'ee7f7279-cd44-4965-8425-5f14d0aaceed', 3, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-12 14:52:16.450567+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('45c3601d-6e44-4d6c-8923-760dcde38a2d', 'f330baf5-9221-4654-8486-6e318726c75e', 1, 'ACTIVITY_COMPLETED', 'seed-typed.exactAmount', '12345678901234567890.123456789', '2026-11-12 14:52:16.563754+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8fe000da-d48d-4202-8ebb-f98b483346f0', 'a6e7dec2-482f-4b5e-b66f-7ff9b60a8f71', 1, 'ACTIVITY_COMPLETED', 'seed-typed.localStamp', '"2026-11-12T10:00:00+02:00"', '2026-11-12 14:52:16.582267+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('60cf8c87-f88d-476e-9548-8e519cd872ce', '57304342-de06-45a1-ae4d-69266cca427e', 1, 'ACTIVITY_COMPLETED', 'seed-typed.tags', '["gamma", "beta", "delta", "alpha"]', '2026-11-12 14:52:16.612233+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7cddfa4b-afac-47e3-9ba0-411253309651', 'f330baf5-9221-4654-8486-6e318726c75e', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-09T14:52:16.604032Z", "signalName": "go"}', '2026-11-12 14:52:16.62091+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a3015bce-58be-4761-9e0d-e1c3b3e896fe', 'a6e7dec2-482f-4b5e-b66f-7ff9b60a8f71', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-09T14:52:16.641247Z", "signalName": "go"}', '2026-11-12 14:52:16.651059+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5766cdae-7543-4343-a1ac-500d6be46ab5', '57304342-de06-45a1-ae4d-69266cca427e', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-09T14:52:16.663394Z", "signalName": "go"}', '2026-11-12 14:52:16.672867+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('00487456-a3c0-4f35-a8e9-51925415ab8b', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-timer", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-12 14:52:17.278816+00', '558ccdf6e173c89faa47667cf30f6a9d89ab597ce095afe18363ca028ba5882d');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('27c2a4b0-6e8c-47de-a954-fbc024852d6a', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-12T14:52:17.293512Z"', '2026-11-12 14:52:17.293584+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('57820330-9b28-4269-92ed-e55d0298ffe2', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"71ad1b56-6570-4916-a505-fc43eb7183ee"', '2026-11-12 14:52:17.307886+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0d247986-1034-4de3-88c4-82fdadf86e0c', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-12 14:52:17.320462+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5a55c81c-76ad-4235-af60-77c628ae983d', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-12 14:52:17.33464+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('59dc9115-634c-4a86-b520-988d6df0fe54', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-12 14:52:17.34948+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f6add2c2-412a-4e95-9f94-2cdc31d02e6f', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-12 14:52:17.349482+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e099be91-ecdc-48fa-9905-6d7b9bb2c195', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-timer", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:17.366974+00', 'd0eae109e2ce1eb172aa9c675c463121146e996ead38dee0886c8252d36a77ac');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3b06d0bb-fd95-473e-859d-6113e0b5a490', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT10M"}', '2026-11-12 14:52:17.389935+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('42f36ad7-45c1-44ee-a62b-c55923f79be0', 'fe8863d2-96fa-4a53-b624-d3d631906247', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-express", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-12 14:52:17.499936+00', '354fbdbc6a8e2e95b3863f5a5582854a39445fa66416df79e67c44532ca4e457');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dff0f137-383c-4e81-b577-0b8748c561bf', 'fe8863d2-96fa-4a53-b624-d3d631906247', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-12T14:52:17.513957Z"', '2026-11-12 14:52:17.514006+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('537fb2b4-84ba-4de5-a6bd-65a2ba35418f', 'fe8863d2-96fa-4a53-b624-d3d631906247', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"39adec45-4006-4f58-8486-f093b552e41a"', '2026-11-12 14:52:17.527457+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a44cb5cb-d43f-4d65-8e4c-1aa0bde5ac17', 'fe8863d2-96fa-4a53-b624-d3d631906247', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-12 14:52:17.540684+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c7c95493-3a1d-4ad7-b99d-fb6df2ab968f', 'fe8863d2-96fa-4a53-b624-d3d631906247', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-12 14:52:17.554576+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('debb1b01-c6ef-4649-9a53-1af2cacab6ea', 'fe8863d2-96fa-4a53-b624-d3d631906247', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-12 14:52:17.583124+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('511e56dc-a707-44a2-bb7b-e719ca175e55', 'fe8863d2-96fa-4a53-b624-d3d631906247', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-12 14:52:17.583125+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0098ac56-6043-463b-99f8-4279043114c3', 'fe8863d2-96fa-4a53-b624-d3d631906247', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-express", "exceptionType": "io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows$SeedFailure"}', '2026-11-12 14:52:17.602384+00', 'e487ac6f64544e0840cd7f51f5490bd78e0b48293e7d15f91f57aa2ca64c9100');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('08522444-6e3d-48e6-ab33-76dd86eb5485', 'fe8863d2-96fa-4a53-b624-d3d631906247', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-12 14:52:17.630813+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cc3b8b57-03d4-4525-9c0d-bc66d65de61e', 'fe8863d2-96fa-4a53-b624-d3d631906247', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-12 14:52:18.806078+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c56943cc-d26a-4e3e-9340-568ac587ff66', 'fe8863d2-96fa-4a53-b624-d3d631906247', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT10M", "timerId": "await-signal-8004", "deadline": "2026-11-12T15:02:18.853649Z", "signalName": "express"}', '2026-11-12 14:52:18.861375+00', NULL);


--
-- Data for Name: maestro_workflow_instance; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 'seed-completed', 'd9aeeb55-e468-42e9-9e2f-5256b999b71c', 'SeedOrderWorkflow', 'default', 'COMPLETED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-completed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"note": "note for seed-completed", "token": "5b9ea257-54fc-4365-b32a-59399e101246", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-12T14:52:03.281538Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', 'upgrade-fixture', 8011, '2026-11-12 14:52:03.172418+00', '2026-11-12 14:52:06.013789+00', '2026-11-12 14:52:06.013789+00', 9, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 'seed-parked-approval', 'd80a3b07-b112-4706-81b0-67b280483db3', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-approval", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-12 14:52:06.098378+00', NULL, '2026-11-12 14:52:08.642616+00', 7, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 'seed-terminated', 'e9937046-d8cd-46a3-a19d-26fbcd0ec437', 'SeedOrderWorkflow', 'default', 'TERMINATED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-terminated", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"reason": "seeded termination", "terminatedBy": "admin"}', 'upgrade-fixture', 0, '2026-11-12 14:52:08.866156+00', '2026-11-12 14:52:11.553147+00', '2026-11-12 14:52:11.553147+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('925b841b-8065-4411-b9b6-efb0b5a0a7e0', 'seed-crashed', 'f2695d7b-6634-458b-9a5f-d3e466451c73', 'SeedOrderWorkflow', 'default', 'RUNNING', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-crashed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-12 14:52:11.629728+00', NULL, '2026-11-12 14:52:14.666112+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('0fa297a6-4650-4a75-bc03-b0b8d19bd020', 'seed-saga-compensated', '9e24a168-5016-496a-97cd-97e46636395a', 'SeedSagaWorkflow', 'default', 'FAILED', '"plain"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', 'upgrade-fixture', 7001, '2026-11-12 14:52:14.776872+00', '2026-11-12 14:52:15.173935+00', '2026-11-12 14:52:15.173935+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('6a4e6b11-4398-48e3-b730-42ec60507b07', 'seed-saga-compensation-failed', '72659046-1f43-406b-8e58-c2ed5d802e3f', 'SeedSagaWorkflow', 'default', 'FAILED', '"fail-refund:x"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', 'upgrade-fixture', 7001, '2026-11-12 14:52:15.240749+00', '2026-11-12 14:52:15.59999+00', '2026-11-12 14:52:15.59999+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('5ca8fcfb-041a-4386-9df3-cd85343e0106', 'seed-compensating-crashed', '95ae0cd9-fbb5-4e4a-908a-6b532275f152', 'SeedSagaWorkflow', 'default', 'COMPENSATING', '"crash-compensation"', NULL, 'upgrade-fixture', 0, '2026-11-12 14:52:15.719947+00', NULL, '2026-11-12 14:52:15.824118+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('4ed52333-771c-4c76-bda2-3a0e13394668', 'seed-activity-failed', '3822c745-3b57-4a9a-afef-9155513b8c28', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-activity-failed", "alertBeforeRethrow": false}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', 'upgrade-fixture', 1, '2026-11-12 14:52:15.977784+00', '2026-11-12 14:52:16.23925+00', '2026-11-12 14:52:16.23925+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('ee7f7279-cd44-4965-8425-5f14d0aaceed', 'seed-alert-failed', '90de45ad-8c55-4280-86a1-d4599ba702f6', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-alert-failed", "alertBeforeRethrow": true}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', 'upgrade-fixture', 2, '2026-11-12 14:52:16.287668+00', '2026-11-12 14:52:16.450567+00', '2026-11-12 14:52:16.450567+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('f330baf5-9221-4654-8486-6e318726c75e', 'seed-wp1-decimal', '94b7b7c7-e1b9-4947-9be2-299b2b08a52e', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "DECIMAL"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-12 14:52:16.534788+00', NULL, '2026-11-12 14:52:16.650954+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('a6e7dec2-482f-4b5e-b66f-7ff9b60a8f71', 'seed-wp1-offset', '1fd525f7-1d8d-4ca9-9506-ed849ae3fc72', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "OFFSET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-12 14:52:16.553906+00', NULL, '2026-11-12 14:52:16.672202+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('57304342-de06-45a1-ae4d-69266cca427e', 'seed-wp1-set', 'd4c0066f-49cf-43eb-8e58-b53a6567c63c', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "SET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-12 14:52:16.573605+00', NULL, '2026-11-12 14:52:16.706757+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('ea3847a0-e81c-43e8-9c2b-cd2af365495f', 'seed-parked-timer', '03acd9de-a6e8-4be4-a351-6ac1a5cb8cd5', 'SeedOrderWorkflow', 'default', 'WAITING_TIMER', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-timer", "napMillis": 600000, "expressWaitMillis": 1000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-12 14:52:17.255851+00', NULL, '2026-11-12 14:52:17.406482+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('fe8863d2-96fa-4a53-b624-d3d631906247', 'seed-parked-express', '56ffb564-6f53-44b6-9de4-0d42a75428a4', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-express", "napMillis": 1000, "expressWaitMillis": 600000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-12 14:52:17.475891+00', NULL, '2026-11-12 14:52:18.877407+00', 3, 0);


--
-- Data for Name: maestro_workflow_signal; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('14e4b586-5569-421f-bb39-64482ed371ef', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 'seed-completed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-12 14:52:05.902082+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('c6503414-c160-430a-9ab7-c1fd1b4cfdba', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 'seed-completed', 'note', '"note for seed-completed"', true, '2026-11-12 14:52:03.253849+00', NULL, 'client:seed-completed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('e5de67f2-acc9-40ea-b4bf-0fc55e06e84a', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 'seed-parked-approval', 'note', '"note for seed-parked-approval"', false, '2026-11-12 14:52:06.133987+00', NULL, 'client:seed-parked-approval:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('fa9829b8-932c-4708-b351-23d6d79391e5', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 'seed-terminated', 'note', '"note for seed-terminated"', false, '2026-11-12 14:52:08.898956+00', NULL, 'client:seed-terminated:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('1ee5ecc9-9bd6-4605-936f-bc7220309516', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 'seed-crashed', 'note', '"note for seed-crashed"', false, '2026-11-12 14:52:11.665838+00', NULL, 'client:seed-crashed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('f73db3ea-9d40-4980-a998-3e614b3e6795', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 'seed-crashed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-12 14:52:14.619213+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('faeb50d8-2756-4781-8462-a63a626a3daf', NULL, 'seed-orphan-target', 'approval', '{"level": 1, "approver": "capture"}', false, '2026-11-12 14:52:16.517858+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('fc3dd0b0-6459-4e44-962f-88d455b88905', NULL, 'seed-orphan-target', 'note', '"note for seed-orphan-target"', false, '2026-11-12 14:52:16.532919+00', NULL, 'client:seed-orphan-target:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('f8665e29-8c10-46e5-92c6-b41b7de1dc56', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 'seed-parked-timer', 'note', '"note for seed-parked-timer"', false, '2026-11-12 14:52:17.285721+00', NULL, 'client:seed-parked-timer:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('8e20e05a-813a-4d10-84c9-beebf9ad3d6a', 'fe8863d2-96fa-4a53-b624-d3d631906247', 'seed-parked-express', 'note', '"note for seed-parked-express"', false, '2026-11-12 14:52:17.506443+00', NULL, 'client:seed-parked-express:note');


--
-- Data for Name: maestro_workflow_timer; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('907eb329-5ca9-43e7-afa8-1eaa69d35c07', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 'seed-completed', 'sleep-8002', '2026-11-12 14:52:04.405081+00', 'FIRED', '2026-11-12 14:52:03.405286+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('764706e8-bced-488b-b74b-72608bd9446e', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 'seed-completed', 'await-signal-8004', '2026-11-12 14:52:05.57556+00', 'FIRED', '2026-11-12 14:52:04.575591+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('8356209d-0c58-49ca-abbc-e5314af1285d', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 'seed-completed', 'sleep-8006', '2026-11-13 14:52:05.657324+00', 'CANCELLED', '2026-11-12 14:52:05.657329+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('973499b0-21e5-496e-b5ba-fb09cafbcc53', '3a09d22d-ea94-4baf-afd0-e7bf1ffe9917', 'seed-completed', 'await-signal-8008', '2036-11-09 14:52:05.82291+00', 'CANCELLED', '2026-11-12 14:52:05.822922+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('ce0ee57f-0faf-4a52-8c05-946cd5836b04', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 'seed-parked-approval', 'sleep-8002', '2026-11-12 14:52:07.252182+00', 'FIRED', '2026-11-12 14:52:06.252187+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('c7b6e9ed-b98d-4f55-ae83-c488c3813c62', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 'seed-parked-approval', 'await-signal-8004', '2026-11-12 14:52:08.341029+00', 'FIRED', '2026-11-12 14:52:07.341056+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('57f4866d-e0fe-45eb-a833-f74d24403cd9', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 'seed-parked-approval', 'sleep-8006', '2026-11-13 14:52:08.42173+00', 'CANCELLED', '2026-11-12 14:52:08.421737+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('e79026af-289a-4496-8557-2c7997eef2b8', '4ae58dc3-65bd-4cd1-98f7-c4d587b24c2b', 'seed-parked-approval', 'await-signal-8008', '2036-11-09 14:52:08.619137+00', 'PENDING', '2026-11-12 14:52:08.619153+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('15d59f32-4027-432b-9b42-695ff3ec2154', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 'seed-terminated', 'sleep-8002', '2026-11-12 14:52:10.005502+00', 'FIRED', '2026-11-12 14:52:09.005506+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('fea316b0-8517-4570-bafc-6a2757a01f9f', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 'seed-terminated', 'await-signal-8004', '2026-11-12 14:52:11.122071+00', 'FIRED', '2026-11-12 14:52:10.122089+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('471c1af7-606a-4b2c-ab97-ff4f8133235d', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 'seed-terminated', 'sleep-8006', '2026-11-13 14:52:11.193758+00', 'CANCELLED', '2026-11-12 14:52:11.193764+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('344d88e8-7387-441b-97db-876760149878', 'bcfb89ec-d510-4ca4-b22a-6aa42f7e2b49', 'seed-terminated', 'await-signal-8008', '2036-11-09 14:52:11.306027+00', 'PENDING', '2026-11-12 14:52:11.30605+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('d39dcb53-5873-4021-aea6-b236b2400596', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 'seed-crashed', 'sleep-8002', '2026-11-12 14:52:12.788278+00', 'FIRED', '2026-11-12 14:52:11.788284+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('7b3e3b05-ff2e-421e-afc8-244defc78975', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 'seed-crashed', 'await-signal-8004', '2026-11-12 14:52:14.139951+00', 'FIRED', '2026-11-12 14:52:13.139986+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('f352c1dd-e30b-4307-b2be-ff8ccfd52196', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 'seed-crashed', 'sleep-8006', '2026-11-13 14:52:14.383332+00', 'CANCELLED', '2026-11-12 14:52:14.383338+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('d7dc658f-1259-4ff8-a84f-7275afb6c2e1', '925b841b-8065-4411-b9b6-efb0b5a0a7e0', 'seed-crashed', 'await-signal-8008', '2036-11-09 14:52:14.564414+00', 'CANCELLED', '2026-11-12 14:52:14.564434+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('36434ecb-d447-46f3-af64-c94dab288417', 'f330baf5-9221-4654-8486-6e318726c75e', 'seed-wp1-decimal', 'await-signal-2', '2036-11-09 14:52:16.604032+00', 'PENDING', '2026-11-12 14:52:16.604053+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('c143042a-7179-473c-b7a4-f5cee9322e19', 'a6e7dec2-482f-4b5e-b66f-7ff9b60a8f71', 'seed-wp1-offset', 'await-signal-2', '2036-11-09 14:52:16.641247+00', 'PENDING', '2026-11-12 14:52:16.641262+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('6a1f15e6-8fb5-4492-9034-365a6c619df8', '57304342-de06-45a1-ae4d-69266cca427e', 'seed-wp1-set', 'await-signal-2', '2036-11-09 14:52:16.663394+00', 'PENDING', '2026-11-12 14:52:16.663413+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('0409fa70-9ec1-4c07-a54a-2be25fa4269d', 'ea3847a0-e81c-43e8-9c2b-cd2af365495f', 'seed-parked-timer', 'sleep-8002', '2026-11-12 15:02:17.38253+00', 'PENDING', '2026-11-12 14:52:17.382562+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3c115674-2802-4b56-8891-02010d965e27', 'fe8863d2-96fa-4a53-b624-d3d631906247', 'seed-parked-express', 'sleep-8002', '2026-11-12 14:52:18.621465+00', 'FIRED', '2026-11-12 14:52:17.621471+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('2915ff65-a36c-4b87-b2c1-75d1ac0da18f', 'fe8863d2-96fa-4a53-b624-d3d631906247', 'seed-parked-express', 'await-signal-8004', '2026-11-12 15:02:18.853649+00', 'PENDING', '2026-11-12 14:52:18.85367+00');


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


