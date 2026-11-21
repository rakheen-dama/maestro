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

INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', 'create maestro schema', 'SQL', 'V1__create_maestro_schema.sql', 1894477780, 'test', '2026-11-21 11:50:00.110118', 19, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (2, '2', 'create indexes', 'SQL', 'V2__create_indexes.sql', 1117887476, 'test', '2026-11-21 11:50:00.145049', 6, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (3, '3', 'timer lookup index', 'SQL', 'V3__timer_lookup_index.sql', -511219852, 'test', '2026-11-21 11:50:00.160251', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (4, '4', 'signal trace context', 'SQL', 'V4__signal_trace_context.sql', -444138901, 'test', '2026-11-21 11:50:00.17094', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (5, '5', 'signal received at default', 'SQL', 'V5__signal_received_at_default.sql', -1319518420, 'test', '2026-11-21 11:50:00.182252', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (6, '6', 'instance lease epoch', 'SQL', 'V6__instance_lease_epoch.sql', -1501028209, 'test', '2026-11-21 11:50:00.19077', 2, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (7, '7', 'workflow event args hash', 'SQL', 'V7__workflow_event_args_hash.sql', 1141881341, 'test', '2026-11-21 11:50:00.198209', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (8, '8', 'retention indexes', 'SQL', 'V8__retention_indexes.sql', 1576234141, 'test', '2026-11-21 11:50:00.208705', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (9, '9', 'signal message id', 'SQL', 'V9__signal_message_id.sql', 168428632, 'test', '2026-11-21 11:50:00.218396', 6, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (10, '100', 'maestro lock postgres', 'SQL', 'V100__maestro_lock_postgres.sql', 1037217973, 'test', '2026-11-21 11:50:00.233012', 5, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (11, '101', 'recovery scan index', 'SQL', 'V101__recovery_scan_index.sql', 9197330, 'test', '2026-11-21 11:50:00.243803', 2, true);


--
-- Data for Name: maestro_workflow_event; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0fc27433-e6e2-48d8-b9e2-ae9d9d5cf2ac', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-completed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-21 09:50:00.471168+00', '61120492bde41a623abae6da6acef1477c1c44ac5ccf3e854f20c04625ce8fad');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('23005cdc-b03f-438c-a9e6-8211cc1d51f2', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-21T09:50:00.500473Z"', '2026-11-21 09:50:00.50071+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a15996bb-b4c3-46e9-a1d3-be0b3c108e2b', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"cc44eec5-fbaf-435c-8c9b-634ad8e56fa0"', '2026-11-21 09:50:00.526299+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cdfa5366-44ad-49a5-81c0-182149579184', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-21 09:50:00.54618+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5f63fa18-14dc-4017-8937-c699b230ea6e', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-21 09:50:00.565765+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('da75153f-826a-4ab9-a2e1-dce179c71786', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-21 09:50:00.589055+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('04f317c9-97d8-4fd9-a0ff-d90d1c6d9aba', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-21 09:50:00.589073+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e141a6e2-cdba-4c94-8c57-944a48547327', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-completed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:00.613073+00', '2ecf14aafdc0c4016c28daa53e863728959afca02f9b5503a122173ab2f30a04');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('432f9ce7-344e-4bf6-96e6-1c3ffca25bb7', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-21 09:50:00.642029+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b1b7c4b1-10e6-47d0-ba14-11e79d01836f', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-21 09:50:01.866117+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6d244092-4acc-4c6c-a040-bd60968c2af7', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-21T09:50:02.948450Z", "signalName": "express"}', '2026-11-21 09:50:01.95938+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('db17de12-998d-49fe-8ae8-a827476c4bb9', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-21 09:50:03.000679+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('939ec04a-6361-46b4-b597-4047170c8ea1', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-21 09:50:03.048771+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('722d7cbf-aefd-4197-af5d-ee078bed5835', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-21 09:50:03.099609+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('06527699-77a9-4d69-a912-a0bdb0ba3e92', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-18T09:50:03.170975Z", "signalName": "approval"}', '2026-11-21 09:50:03.188975+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('849d6610-a40e-4ddb-8dbb-6299c25a0c52', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-21 09:50:03.31652+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3f354ba2-bde2-4252-8e42-2b4482ee3cdf', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8010, 'ACTIVITY_COMPLETED', 'seed-order.charge', '{"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}', '2026-11-21 09:50:03.427136+00', '4f7957db6f7b556032ab536a356340cca8b51fb6d1726e046db09ede5858e8e5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cafd98c9-bd52-4fa5-8f94-62ea35337b7c', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8011, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:note', '"note for seed-completed"', '2026-11-21 09:50:03.477699+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('019707ba-f334-4269-9c9a-00e28473476d', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 8012, 'WORKFLOW_COMPLETED', NULL, '{"note": "note for seed-completed", "token": "cc44eec5-fbaf-435c-8c9b-634ad8e56fa0", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-21T09:50:00.500473Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', '2026-11-21 09:50:03.54019+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d4169747-8496-4130-9932-44096317feca', 'acb6533b-49a2-4853-8196-727e444220bf', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-approval", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-21 09:50:03.639582+00', 'b6195227c62ead98ed1b3b177bfa8f2bb53caca058a40f3326a52ea2b6c24a86');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1aea5f26-9b36-442f-a47a-53ed66c0ac6e', 'acb6533b-49a2-4853-8196-727e444220bf', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-21T09:50:03.657345Z"', '2026-11-21 09:50:03.657404+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a72088ea-51ec-4c57-aa30-ad4da5df976d', 'acb6533b-49a2-4853-8196-727e444220bf', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"36e70b24-454e-464a-899e-ec31d2f41b78"', '2026-11-21 09:50:03.673505+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5a4f631b-0e75-4a62-87b6-f90a65521f3e', 'acb6533b-49a2-4853-8196-727e444220bf', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-21 09:50:03.689662+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('33e6e058-de27-4685-b519-8f9bf0845666', 'acb6533b-49a2-4853-8196-727e444220bf', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-21 09:50:03.705733+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('86baca14-9426-4d9b-84b3-c5370ac1276f', 'acb6533b-49a2-4853-8196-727e444220bf', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-21 09:50:03.72233+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bd1e9f1a-6dca-42dc-a4ae-4f770ce27f12', 'acb6533b-49a2-4853-8196-727e444220bf', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-21 09:50:03.721147+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ef92b199-2d63-4cbf-b765-644f9f8e7936', 'acb6533b-49a2-4853-8196-727e444220bf', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-approval", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:03.739875+00', 'c2607e49b4ab277032d58069d85787ad440a6041aefa57dab52b717a1cc9b16f');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8081ff59-940d-4a52-aff5-594c855becab', 'acb6533b-49a2-4853-8196-727e444220bf', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-21 09:50:03.770668+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('15dac5cd-8ebb-4307-969b-185798710aaa', 'acb6533b-49a2-4853-8196-727e444220bf', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-21 09:50:05.001615+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('aa8e22a1-e79c-4313-a2e7-452afe0ec7d7', 'acb6533b-49a2-4853-8196-727e444220bf', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-21T09:50:06.049422Z", "signalName": "express"}', '2026-11-21 09:50:05.056843+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d274495d-d664-401c-a962-bbba314ffa89', 'acb6533b-49a2-4853-8196-727e444220bf', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-21 09:50:06.084757+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9b217879-250c-420d-80dd-04773e9b171d', 'acb6533b-49a2-4853-8196-727e444220bf', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-21 09:50:06.125559+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('513d4551-63c4-4e8c-8693-09b35f9a6702', 'acb6533b-49a2-4853-8196-727e444220bf', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-21 09:50:06.194483+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('804685c5-a665-42fd-8edc-6cf800ff8826', 'acb6533b-49a2-4853-8196-727e444220bf', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-18T09:50:06.249361Z", "signalName": "approval"}', '2026-11-21 09:50:06.256386+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ad9bdaea-e3ab-4f94-88d4-8ebd0216ca7b', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-terminated", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-21 09:50:06.529828+00', '785d82a01f4a3938f2f8d5ae7601a6d490bf7cfd73d10ee4ce728ea649d991cb');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c274a43f-b2b2-446a-820d-9c8731563fff', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-21T09:50:06.546390Z"', '2026-11-21 09:50:06.546433+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ff541829-641e-45c7-8d92-481b1234e45b', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"bd3c66a0-d32b-4081-8526-e4b867e6bf08"', '2026-11-21 09:50:06.56156+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('50ffeff3-d24c-4ded-9cc9-479f7d335353', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-21 09:50:06.578006+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a8d7c75a-22bd-4c4f-8d4d-8dd0822d2750', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-21 09:50:06.594824+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1d610c8a-8dca-4d2b-9317-fbed42aef182', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-21 09:50:06.610469+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('72896f9b-9b18-410e-859f-38315f8c08e5', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-21 09:50:06.610533+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d9fb6935-1613-41ac-bcb1-cebe8eaa6952', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-terminated", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:06.628921+00', 'aaf06686d0cc7c8cba8970dc79c375fc1ce1be0e307c7e044520e11bbf08b1bc');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dc010128-2bfd-4394-adf1-09515b0f74df', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-21 09:50:06.654946+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fac8f75a-d6d9-407a-a87e-2d997d186b4a', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-21 09:50:07.800138+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('67415458-cda8-4ecf-bbaf-aaf42e5f9071', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-21T09:50:08.849148Z", "signalName": "express"}', '2026-11-21 09:50:07.857163+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('af49494f-7b5c-4a24-916e-8f30a71d3495', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-21 09:50:08.891542+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f98ec4d6-7e2b-4df7-be05-6e92132c1025', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-21 09:50:08.9371+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0676e0c7-c44e-4bc0-894a-dec38fe28ef7', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-21 09:50:09.040798+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('edb9fa3f-54d7-49a2-b048-6c9611b62879', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-18T09:50:09.084916Z", "signalName": "approval"}', '2026-11-21 09:50:09.091841+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('55ec5f3d-e4f8-436b-b141-29edb0c11afa', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-crashed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-21 09:50:09.469515+00', 'e00fde43b12ebd50fb69f3fe435e050d9a6300f7fc0a13f99bebde6e91498fa5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c030da46-ec5f-44c4-aec9-c9b4623d3d78', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-21T09:50:09.485468Z"', '2026-11-21 09:50:09.485521+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('370a3268-0e99-4112-a42c-0ea1e6856318', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"a0265d1e-2c7f-48a3-8c4d-f42d407fd6ba"', '2026-11-21 09:50:09.500891+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f5009ee7-0f4a-4bcb-9cff-45927e642885', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-21 09:50:09.513679+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('24410cde-4412-4397-8d09-2f016732b3fc', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-21 09:50:09.527095+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3edb2491-8949-4725-ae47-73c88a956692', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-21 09:50:09.540948+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6f55f1eb-a7a0-4c02-a671-ee2752bba6e2', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-21 09:50:09.540848+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e274e4e0-2208-4459-bdd7-fd8dd61aff59', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-crashed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:09.557328+00', '9afaaca3ce7e4299920c56b10762cde56e24090ad65cc2266e61c3d248a41109');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('de7ce438-2ffb-48af-a1ea-281d60c83e65', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-21 09:50:09.579633+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bc8d9ac8-d812-49d8-b814-fa939f37e18d', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-21 09:50:10.612679+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('356f7376-952a-4b78-9107-19fc809d5560', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-21T09:50:11.660052Z", "signalName": "express"}', '2026-11-21 09:50:10.667606+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b0475394-1794-4c61-88dc-e9b2736906c9', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-21 09:50:11.697978+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('888d2cb6-1c18-4bfb-b0ce-91e993fbb23b', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-21 09:50:11.73923+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('aacabe7a-6a48-4e11-a032-b52e2edbb90d', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-21 09:50:11.808127+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b57c33d2-0013-4f3d-9710-a27c4dbd87e4', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-18T09:50:11.891014Z", "signalName": "approval"}', '2026-11-21 09:50:11.899792+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c5a8a0b8-5435-48a3-816f-7faa8d51fcb4', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-21 09:50:11.941269+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2131727e-89dd-4a93-ba96-a079d1ae992b', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"plain"', '2026-11-21 09:50:12.102101+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b489b013-82c8-4b75-b29d-fe0072c5abb0', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"plain"', '2026-11-21 09:50:12.121089+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ad11f3ab-6593-4377-9916-a679a5a33469', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for plain", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:12.140007+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f3413a8c-9813-48fe-98f7-cc80f6a74d89', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-21 09:50:12.180042+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a066f4c7-a200-4c7e-9080-07180510532b', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-21 09:50:12.217849+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8c7d87cf-9268-44c2-8f2d-963fb4abef17', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-21 09:50:12.225079+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9346076f-e29f-40b4-bfea-267da61c2f69', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-21 09:50:12.259081+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('78b8588e-09b4-45ef-a597-811830c27f78', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-21 09:50:12.273819+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c8a0ee54-39cd-4c92-ac9d-23941b86c632', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', NULL, '2026-11-21 09:50:12.296241+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cddff7b3-a535-4b22-bfdd-b25cfe049785', '8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-21 09:50:12.333965+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d7273a40-c917-491d-9f11-fe0de649876c', '3331d290-3267-4f7c-9215-e9962cd93984', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"fail-refund:x"', '2026-11-21 09:50:12.466881+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c416c884-141d-42a9-b244-bfa33c40fd7c', '3331d290-3267-4f7c-9215-e9962cd93984', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"fail-refund:x"', '2026-11-21 09:50:12.487516+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('679dfc33-11df-4076-a558-e59dd72fd352', '34f877a8-56cd-434b-b172-3f9ab366f165', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-21 09:50:14.485837+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('edc5e8db-babc-459f-8421-7fd9da26aba0', '3331d290-3267-4f7c-9215-e9962cd93984', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:12.507364+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('825b988a-a4fe-4202-ae2b-dc9947853929', '3331d290-3267-4f7c-9215-e9962cd93984', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-21 09:50:12.546053+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5e9c9b70-1d05-4164-9b28-306e6088c63a', '3331d290-3267-4f7c-9215-e9962cd93984', 5001, 'ACTIVITY_FAILED', 'seed-saga.refund', '{"message": "refund rejected for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:12.586114+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('34a13b39-6780-4a68-a2da-2401e5871f47', '3331d290-3267-4f7c-9215-e9962cd93984', 5000, 'COMPENSATION_STEP_FAILED', 'seed-saga.refund', '{"message": "Activity ''seed-saga.refund'' failed in workflow ''seed-saga-compensation-failed'': refund rejected for fail-refund:x", "stepName": "seed-saga.refund", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-21 09:50:12.599683+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b1e28d86-9923-4f92-bcec-dc5b5db23ce9', '3331d290-3267-4f7c-9215-e9962cd93984', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-21 09:50:12.635436+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ec00dcb6-3d68-4246-bcdd-8fc670de5c43', '3331d290-3267-4f7c-9215-e9962cd93984', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-21 09:50:12.642794+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6253920f-cad2-4e02-8358-6c573d8619de', '3331d290-3267-4f7c-9215-e9962cd93984', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', '{"failedSteps": ["seed-saga.refund"], "totalCompensations": 2}', '2026-11-21 09:50:12.660698+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a0021cc9-a9ba-476c-8535-028b2724f589', '3331d290-3267-4f7c-9215-e9962cd93984', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-21 09:50:12.681099+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ba68eee3-bf9e-4199-bc0e-9181ee82545f', '537a43f7-b18f-4d23-89bc-8351c61029b1', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"crash-compensation"', '2026-11-21 09:50:12.761315+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('50dfca4e-2c30-48fb-ae59-cb4553fddcd8', '537a43f7-b18f-4d23-89bc-8351c61029b1', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"crash-compensation"', '2026-11-21 09:50:12.786305+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('915d3528-0a22-431d-a501-a881636a53e1', '537a43f7-b18f-4d23-89bc-8351c61029b1', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for crash-compensation", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:12.805119+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d20a065f-453b-44aa-8b14-c9b991d6c070', '537a43f7-b18f-4d23-89bc-8351c61029b1', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-21 09:50:12.844388+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bc6fd275-bcd9-4a06-b9e6-3dd624daedac', '537a43f7-b18f-4d23-89bc-8351c61029b1', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-21 09:50:12.881427+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6c68bbe9-bac6-4b24-bf80-1a004b2bfdb1', '537a43f7-b18f-4d23-89bc-8351c61029b1', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-21 09:50:12.888726+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0c8774ff-751d-4c92-9e46-c4723bdea2bd', 'e5062042-c738-4e90-997f-1dcbd388388b', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:13.110662+00', 'ae55ac050582d8056dcfc24a102fc318460211527edd40f09779fa2b2de4f2ec');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9965eeff-bee2-474e-a7f0-5aafee735692', 'e5062042-c738-4e90-997f-1dcbd388388b', 2, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-21 09:50:13.127855+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fa5b79df-6dc8-4717-ac2e-e2a7a32be7bb', '4b175e7c-9622-449f-b8c8-28c2867d2171', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:13.324313+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0c5e1de6-6898-49a2-8dd4-1fd81a830e4f', '4b175e7c-9622-449f-b8c8-28c2867d2171', 2, 'ACTIVITY_COMPLETED', 'seed-failing.alert', '"alerted:seed-alert-failed"', '2026-11-21 09:50:13.342235+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2668b14d-bf10-489e-b96c-d74396382339', '4b175e7c-9622-449f-b8c8-28c2867d2171', 3, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-21 09:50:13.362539+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b408cc92-b8e6-44bc-8e1b-915c5f87cc6a', 'd5e7e3c3-d34e-4a42-8ee7-01ef6caab868', 1, 'ACTIVITY_COMPLETED', 'seed-typed.exactAmount', '12345678901234567890.123456789', '2026-11-21 09:50:13.471259+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f52f0a7e-f9c3-48ef-b6a2-32168e3a044a', 'd37a5580-894d-4ad0-bc1a-f66e45431821', 1, 'ACTIVITY_COMPLETED', 'seed-typed.localStamp', '"2026-11-12T10:00:00+02:00"', '2026-11-21 09:50:13.489493+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('eefd26f1-bb5d-4656-a253-66a1334c719e', '8d5f4c6d-6878-4c23-b291-fea0383556ee', 1, 'ACTIVITY_COMPLETED', 'seed-typed.tags', '["delta", "beta", "gamma", "alpha"]', '2026-11-21 09:50:13.521425+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('45fa1ea7-aeba-475e-96bf-07139d899824', 'd5e7e3c3-d34e-4a42-8ee7-01ef6caab868', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-18T09:50:13.527538Z", "signalName": "go"}', '2026-11-21 09:50:13.537622+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('19a5075d-94a5-4161-9296-f04236519a45', 'd37a5580-894d-4ad0-bc1a-f66e45431821', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-18T09:50:13.535475Z", "signalName": "go"}', '2026-11-21 09:50:13.545685+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('44c772fc-d442-46ac-8c3e-c6d5c3311b8c', '8d5f4c6d-6878-4c23-b291-fea0383556ee', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-18T09:50:13.560326Z", "signalName": "go"}', '2026-11-21 09:50:13.572501+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b61ed835-8fbe-42a5-92ea-a18f1ed9cf82', '8407fe71-4f24-4288-83f3-ba591f6a0b86', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"parked"', '2026-11-21 09:50:14.142505+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2775d346-df3c-4cf9-9dbf-7ee6c413ec25', '8407fe71-4f24-4288-83f3-ba591f6a0b86', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"parked"', '2026-11-21 09:50:14.156914+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('be9d065f-e822-442d-85b5-5606afbdb39f', '8407fe71-4f24-4288-83f3-ba591f6a0b86', 3, 'TIMER_SCHEDULED', '$maestro:awaitSignal:ship-now', '{"timeout": "PT87600H", "timerId": "await-signal-3", "deadline": "2036-11-18T09:50:14.190427Z", "signalName": "ship-now"}', '2026-11-21 09:50:14.19753+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('beaa1e99-d318-4996-8dd2-c660eaa21a43', '34f877a8-56cd-434b-b172-3f9ab366f165', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-timer", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-21 09:50:14.40976+00', '558ccdf6e173c89faa47667cf30f6a9d89ab597ce095afe18363ca028ba5882d');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ee7ef128-a4a3-44a6-8d9d-4ff030d7f31b', '34f877a8-56cd-434b-b172-3f9ab366f165', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-21T09:50:14.424892Z"', '2026-11-21 09:50:14.424965+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('43aa202d-95f5-4b6c-81b7-f6e6b0dff6c2', '34f877a8-56cd-434b-b172-3f9ab366f165', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"7d82455d-04f3-4c60-b840-d9b7122d50d0"', '2026-11-21 09:50:14.441027+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('69105c0d-e2d8-45d4-98a7-810daa065ac4', '34f877a8-56cd-434b-b172-3f9ab366f165', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-21 09:50:14.455407+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('175c725b-28ba-4cab-b916-b1cff10a4224', '34f877a8-56cd-434b-b172-3f9ab366f165', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-21 09:50:14.469884+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('31c759e0-726d-418b-b435-57b417e3b47e', '34f877a8-56cd-434b-b172-3f9ab366f165', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-21 09:50:14.485811+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('eb09fd6c-2aef-48fe-b0b3-6b78783747aa', '34f877a8-56cd-434b-b172-3f9ab366f165', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-timer", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:14.504468+00', 'd0eae109e2ce1eb172aa9c675c463121146e996ead38dee0886c8252d36a77ac');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('33f31f01-bac0-43b6-a61a-ffb46ddc31cc', '34f877a8-56cd-434b-b172-3f9ab366f165', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT10M"}', '2026-11-21 09:50:14.52688+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('466c86f1-dda5-4334-b418-f920732bc900', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-express", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-21 09:50:14.62786+00', '354fbdbc6a8e2e95b3863f5a5582854a39445fa66416df79e67c44532ca4e457');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cd021325-aa48-4ddc-9e3f-81d53d9a3642', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-21T09:50:14.643831Z"', '2026-11-21 09:50:14.643881+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a0e31ab2-9c18-4a95-9a92-f6e3449af47e', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"ce9ea6a9-4df4-42c4-8507-1820d8363338"', '2026-11-21 09:50:14.658241+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1d4b7bd5-cd77-4e25-b25a-df78cd3452ef', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-21 09:50:14.674237+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('adfac308-901d-4136-907b-6cee04a8b2c6', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-21 09:50:14.689682+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('62933a72-b684-4a25-ae3e-b54e6081a98a', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-21 09:50:14.704481+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4af51d33-7aae-449e-b416-387b94e79641', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-21 09:50:14.704522+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cbf2f804-7a74-40e5-86d7-dcf2b63ce70c', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-express", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_3.SeededHistoryWorkflows$SeedFailure"}', '2026-11-21 09:50:14.721555+00', 'e487ac6f64544e0840cd7f51f5490bd78e0b48293e7d15f91f57aa2ca64c9100');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c292c554-ffd4-42ce-ab0b-ebdd1893397f', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-21 09:50:14.745184+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8fd2d29a-1781-46f6-8ecf-04f780a67b1a', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-21 09:50:15.949738+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('20c77212-fd39-48c9-8a97-ebaebd4ad7f3', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT10M", "timerId": "await-signal-8004", "deadline": "2026-11-21T10:00:15.992553Z", "signalName": "express"}', '2026-11-21 09:50:15.999335+00', NULL);


--
-- Data for Name: maestro_workflow_instance; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('6e1d731a-3028-4933-ac96-f3f9a6c07941', 'seed-completed', 'f89cc901-42d8-43d4-a534-81382f2c40e7', 'SeedOrderWorkflow', 'default', 'COMPLETED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-completed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"note": "note for seed-completed", "token": "cc44eec5-fbaf-435c-8c9b-634ad8e56fa0", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-21T09:50:00.500473Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', 'upgrade-fixture', 8011, '2026-11-21 09:50:00.379558+00', '2026-11-21 09:50:03.54019+00', '2026-11-21 09:50:03.54019+00', 9, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('acb6533b-49a2-4853-8196-727e444220bf', 'seed-parked-approval', 'c72442db-31ed-494f-9ed0-ba3ec447c019', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-approval", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-21 09:50:03.601025+00', NULL, '2026-11-21 09:50:06.272729+00', 7, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('a010dd83-da13-4d6a-ac9b-bd6cf599b037', 'seed-terminated', 'dd1e857e-5cb2-44b2-a7d3-318c7f59201a', 'SeedOrderWorkflow', 'default', 'TERMINATED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-terminated", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"reason": "seeded termination", "terminatedBy": "admin"}', 'upgrade-fixture', 0, '2026-11-21 09:50:06.502316+00', '2026-11-21 09:50:09.359348+00', '2026-11-21 09:50:09.359348+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('a9615e6b-1b88-47a2-9a38-f20b4c4944de', 'seed-crashed', '8dbe25cf-0318-4669-8b4d-4ffe81dff6b8', 'SeedOrderWorkflow', 'default', 'RUNNING', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-crashed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-21 09:50:09.442125+00', NULL, '2026-11-21 09:50:11.97733+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('8fec99c3-6eb1-4cf9-8e07-196bc26376c6', 'seed-saga-compensated', '276424c9-e13d-4b42-ae63-bb7efdec0c8f', 'SeedSagaWorkflow', 'default', 'FAILED', '"plain"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-21 09:50:12.069361+00', '2026-11-21 09:50:12.333965+00', '2026-11-21 09:50:12.333965+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('3331d290-3267-4f7c-9215-e9962cd93984', 'seed-saga-compensation-failed', 'b451ba70-9fa7-47d4-9468-a4ae4da465bc', 'SeedSagaWorkflow', 'default', 'FAILED', '"fail-refund:x"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-21 09:50:12.437372+00', '2026-11-21 09:50:12.681099+00', '2026-11-21 09:50:12.681099+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('537a43f7-b18f-4d23-89bc-8351c61029b1', 'seed-compensating-crashed', '7083961a-b919-49a6-b456-504268b47b94', 'SeedSagaWorkflow', 'default', 'COMPENSATING', '"crash-compensation"', NULL, 'upgrade-fixture', 0, '2026-11-21 09:50:12.721672+00', NULL, '2026-11-21 09:50:12.83424+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('e5062042-c738-4e90-997f-1dcbd388388b', 'seed-activity-failed', '40a8aade-9c57-42e6-b6d8-37712f4e86c5', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-activity-failed", "alertBeforeRethrow": false}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 1, '2026-11-21 09:50:12.979265+00', '2026-11-21 09:50:13.127855+00', '2026-11-21 09:50:13.127855+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('4b175e7c-9622-449f-b8c8-28c2867d2171', 'seed-alert-failed', '76af9b67-2fe1-436e-a601-90f96d57eeb3', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-alert-failed", "alertBeforeRethrow": true}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 2, '2026-11-21 09:50:13.193089+00', '2026-11-21 09:50:13.362539+00', '2026-11-21 09:50:13.362539+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('d5e7e3c3-d34e-4a42-8ee7-01ef6caab868', 'seed-wp1-decimal', 'fb4ade0c-28b1-4422-8a3a-747081e4f8c3', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "DECIMAL"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-21 09:50:13.441522+00', NULL, '2026-11-21 09:50:13.557292+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('d37a5580-894d-4ad0-bc1a-f66e45431821', 'seed-wp1-offset', '365ab6dd-6029-4802-8af4-4310969c1d73', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "OFFSET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-21 09:50:13.46397+00', NULL, '2026-11-21 09:50:13.571497+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('8d5f4c6d-6878-4c23-b291-fea0383556ee', 'seed-wp1-set', '2068de66-f1cf-48f2-9509-8f3a9b076277', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "SET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-21 09:50:13.48199+00', NULL, '2026-11-21 09:50:13.596901+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('8407fe71-4f24-4288-83f3-ba591f6a0b86', 'seed-saga-parked', 'abc44470-ca4a-436b-8ca4-27384a3b692a', 'SeedParkedSagaWorkflow', 'default', 'WAITING_SIGNAL', '"parked"', NULL, 'upgrade-fixture-saga', 0, '2026-11-21 09:50:14.115801+00', NULL, '2026-11-21 09:50:14.214133+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('34f877a8-56cd-434b-b172-3f9ab366f165', 'seed-parked-timer', '7ef14d23-466a-4255-acc3-e30812132b8c', 'SeedOrderWorkflow', 'default', 'WAITING_TIMER', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-timer", "napMillis": 600000, "expressWaitMillis": 1000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-21 09:50:14.384791+00', NULL, '2026-11-21 09:50:14.543185+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 'seed-parked-express', '013a909b-710e-4d33-8d84-034e371df5cf', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-express", "napMillis": 1000, "expressWaitMillis": 600000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-21 09:50:14.605114+00', NULL, '2026-11-21 09:50:16.020482+00', 3, 0);


--
-- Data for Name: maestro_workflow_signal; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('a3c492f2-6a16-4b10-821a-2e32d3383044', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 'seed-completed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-21 09:50:03.281655+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('5b0ca13d-9839-4025-9ed2-af332b525878', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 'seed-completed', 'note', '"note for seed-completed"', true, '2026-11-21 09:50:00.460722+00', NULL, 'client:seed-completed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('397eb26a-6d6b-4193-b13c-db1e08f88a9c', 'acb6533b-49a2-4853-8196-727e444220bf', 'seed-parked-approval', 'note', '"note for seed-parked-approval"', false, '2026-11-21 09:50:03.647546+00', NULL, 'client:seed-parked-approval:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('f58dc2a9-7ecb-4983-bc3a-7d35bdbbf9b8', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 'seed-terminated', 'note', '"note for seed-terminated"', false, '2026-11-21 09:50:06.537961+00', NULL, 'client:seed-terminated:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('807e8c0f-953a-4f86-b4c9-12081edbe2aa', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 'seed-crashed', 'note', '"note for seed-crashed"', false, '2026-11-21 09:50:09.476447+00', NULL, 'client:seed-crashed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('d58b5416-0912-4875-971e-8061a9285077', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 'seed-crashed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-21 09:50:11.928819+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('ed03b56b-2b1f-4f41-8f64-7fda009b6444', NULL, 'seed-orphan-target', 'approval', '{"level": 1, "approver": "capture"}', false, '2026-11-21 09:50:13.422856+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('c72e9f01-acfe-4834-bcb5-2c7c18707a11', NULL, 'seed-orphan-target', 'note', '"note for seed-orphan-target"', false, '2026-11-21 09:50:13.439235+00', NULL, 'client:seed-orphan-target:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('15dcd325-5bdb-4edf-b65f-f37f9c700871', '34f877a8-56cd-434b-b172-3f9ab366f165', 'seed-parked-timer', 'note', '"note for seed-parked-timer"', false, '2026-11-21 09:50:14.415981+00', NULL, 'client:seed-parked-timer:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('b9bd1944-ca5a-401b-9a3d-6422eacd1f60', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 'seed-parked-express', 'note', '"note for seed-parked-express"', false, '2026-11-21 09:50:14.634794+00', NULL, 'client:seed-parked-express:note');


--
-- Data for Name: maestro_workflow_timer; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('08bfb03c-33b2-4a88-8387-5b1547f9a771', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 'seed-completed', 'sleep-8002', '2026-11-21 09:50:01.633115+00', 'FIRED', '2026-11-21 09:50:00.633332+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('43436135-9d6f-4b72-8dee-bbfcebe76738', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 'seed-completed', 'await-signal-8004', '2026-11-21 09:50:02.94845+00', 'FIRED', '2026-11-21 09:50:01.948484+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('0d2ebbff-63b6-4166-b6d6-2cf6b0c3c343', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 'seed-completed', 'sleep-8006', '2026-11-22 09:50:03.038992+00', 'CANCELLED', '2026-11-21 09:50:03.038996+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('ca6e88f1-31e6-4b0a-8e32-b6adb8296d78', '6e1d731a-3028-4933-ac96-f3f9a6c07941', 'seed-completed', 'await-signal-8008', '2036-11-18 09:50:03.170975+00', 'CANCELLED', '2026-11-21 09:50:03.171+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3b77c699-8d32-426a-9970-d57b51367db4', 'acb6533b-49a2-4853-8196-727e444220bf', 'seed-parked-approval', 'sleep-8002', '2026-11-21 09:50:04.756161+00', 'FIRED', '2026-11-21 09:50:03.756165+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('2f169b2a-bd79-4ebc-b30e-a79892558ff2', 'acb6533b-49a2-4853-8196-727e444220bf', 'seed-parked-approval', 'await-signal-8004', '2026-11-21 09:50:06.049422+00', 'FIRED', '2026-11-21 09:50:05.04944+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('6798dd78-a922-45f5-a870-1a0b3267a603', 'acb6533b-49a2-4853-8196-727e444220bf', 'seed-parked-approval', 'sleep-8006', '2026-11-22 09:50:06.118841+00', 'CANCELLED', '2026-11-21 09:50:06.118845+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('5bb8373c-53ac-4671-a9b9-a47368416048', 'acb6533b-49a2-4853-8196-727e444220bf', 'seed-parked-approval', 'await-signal-8008', '2036-11-18 09:50:06.249361+00', 'PENDING', '2026-11-21 09:50:06.249376+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3804ea44-6e74-4f49-8911-2e1996c2dda9', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 'seed-terminated', 'sleep-8002', '2026-11-21 09:50:07.646282+00', 'FIRED', '2026-11-21 09:50:06.646286+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('119b2b25-d9d8-4600-a6a0-6d8b085381b6', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 'seed-terminated', 'await-signal-8004', '2026-11-21 09:50:08.849148+00', 'FIRED', '2026-11-21 09:50:07.849164+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('785db4f9-f60f-4426-9750-7c4f82c6a0ee', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 'seed-terminated', 'sleep-8006', '2026-11-22 09:50:08.93026+00', 'CANCELLED', '2026-11-21 09:50:08.930265+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('49ced01b-00e7-4965-8455-c5a1cbd215c1', 'a010dd83-da13-4d6a-ac9b-bd6cf599b037', 'seed-terminated', 'await-signal-8008', '2036-11-18 09:50:09.084916+00', 'PENDING', '2026-11-21 09:50:09.08493+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('2f07ae61-b0ae-4b86-b951-b5d4632d2976', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 'seed-crashed', 'sleep-8002', '2026-11-21 09:50:10.573541+00', 'FIRED', '2026-11-21 09:50:09.573545+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('93920994-4a78-474a-af19-7b78864ae046', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 'seed-crashed', 'await-signal-8004', '2026-11-21 09:50:11.660052+00', 'FIRED', '2026-11-21 09:50:10.660069+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('abb00ce4-e09b-437e-86b5-33663e88ebf5', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 'seed-crashed', 'sleep-8006', '2026-11-22 09:50:11.732725+00', 'CANCELLED', '2026-11-21 09:50:11.73273+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('45ee9e19-673b-4fc8-9d65-3a05aac726ea', 'a9615e6b-1b88-47a2-9a38-f20b4c4944de', 'seed-crashed', 'await-signal-8008', '2036-11-18 09:50:11.891014+00', 'CANCELLED', '2026-11-21 09:50:11.891032+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('0dd70368-4858-47ed-be0d-59aa92df301d', 'd5e7e3c3-d34e-4a42-8ee7-01ef6caab868', 'seed-wp1-decimal', 'await-signal-2', '2036-11-18 09:50:13.527538+00', 'PENDING', '2026-11-21 09:50:13.527553+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('86904fa8-f12e-4204-9193-9639b8bcc3e5', 'd37a5580-894d-4ad0-bc1a-f66e45431821', 'seed-wp1-offset', 'await-signal-2', '2036-11-18 09:50:13.535475+00', 'PENDING', '2026-11-21 09:50:13.535507+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('40c88b95-a48d-4878-8fb8-e1d54bb36e50', '8d5f4c6d-6878-4c23-b291-fea0383556ee', 'seed-wp1-set', 'await-signal-2', '2036-11-18 09:50:13.560326+00', 'PENDING', '2026-11-21 09:50:13.560361+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('7907275f-2632-45f9-a894-a3183887c842', '8407fe71-4f24-4288-83f3-ba591f6a0b86', 'seed-saga-parked', 'await-signal-3', '2036-11-18 09:50:14.190427+00', 'PENDING', '2026-11-21 09:50:14.190442+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3334d738-e6fd-47b5-b458-5fb26a495f6f', '34f877a8-56cd-434b-b172-3f9ab366f165', 'seed-parked-timer', 'sleep-8002', '2026-11-21 10:00:14.520337+00', 'PENDING', '2026-11-21 09:50:14.520343+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('47f6daeb-497d-4762-bc24-1b7e8ea2e04b', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 'seed-parked-express', 'sleep-8002', '2026-11-21 09:50:15.737433+00', 'FIRED', '2026-11-21 09:50:14.737439+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('4f5ffcf2-3b7f-4019-a5cf-8082f571f72a', 'c7035a2a-db5d-49fe-8dbf-9e6f7d17270b', 'seed-parked-express', 'await-signal-8004', '2026-11-21 10:00:15.992553+00', 'PENDING', '2026-11-21 09:50:15.992571+00');


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


