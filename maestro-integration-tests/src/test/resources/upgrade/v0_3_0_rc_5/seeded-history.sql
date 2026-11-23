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

INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (1, '1', 'create maestro schema', 'SQL', 'V1__create_maestro_schema.sql', 1894477780, 'test', '2026-11-23 04:41:54.874014', 17, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (2, '2', 'create indexes', 'SQL', 'V2__create_indexes.sql', 1117887476, 'test', '2026-11-23 04:41:54.909969', 8, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (3, '3', 'timer lookup index', 'SQL', 'V3__timer_lookup_index.sql', -511219852, 'test', '2026-11-23 04:41:54.928691', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (4, '4', 'signal trace context', 'SQL', 'V4__signal_trace_context.sql', -444138901, 'test', '2026-11-23 04:41:54.94367', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (5, '5', 'signal received at default', 'SQL', 'V5__signal_received_at_default.sql', -1319518420, 'test', '2026-11-23 04:41:54.955442', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (6, '6', 'instance lease epoch', 'SQL', 'V6__instance_lease_epoch.sql', -1501028209, 'test', '2026-11-23 04:41:54.96548', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (7, '7', 'workflow event args hash', 'SQL', 'V7__workflow_event_args_hash.sql', 1141881341, 'test', '2026-11-23 04:41:54.975368', 3, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (8, '8', 'retention indexes', 'SQL', 'V8__retention_indexes.sql', 1576234141, 'test', '2026-11-23 04:41:54.985647', 4, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (9, '9', 'signal message id', 'SQL', 'V9__signal_message_id.sql', 168428632, 'test', '2026-11-23 04:41:54.996663', 5, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (10, '100', 'maestro lock postgres', 'SQL', 'V100__maestro_lock_postgres.sql', 1037217973, 'test', '2026-11-23 04:41:55.009179', 6, true);
INSERT INTO public.maestro_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) VALUES (11, '101', 'recovery scan index', 'SQL', 'V101__recovery_scan_index.sql', 9197330, 'test', '2026-11-23 04:41:55.021495', 2, true);


--
-- Data for Name: maestro_workflow_event; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d79eccf9-1d94-4f73-9b08-cd06b124febc', '110cfc94-8387-4173-be64-07fbac267a91', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-completed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-23 02:41:55.275974+00', '61120492bde41a623abae6da6acef1477c1c44ac5ccf3e854f20c04625ce8fad');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6f21f994-0c2d-4f6f-90e9-e62b61b2e78d', '110cfc94-8387-4173-be64-07fbac267a91', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-23T02:41:55.312691Z"', '2026-11-23 02:41:55.312922+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ccc4210e-cda8-4a85-8692-d9b96a392302', '110cfc94-8387-4173-be64-07fbac267a91', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"c37ab9e5-d04b-42a9-8e65-4d6b7c046acc"', '2026-11-23 02:41:55.335087+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('856c2aac-52b1-43a3-98b0-27ad35b2802f', '110cfc94-8387-4173-be64-07fbac267a91', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-23 02:41:55.362624+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a049e506-c188-4c53-8bc3-ffaed454fae4', '110cfc94-8387-4173-be64-07fbac267a91', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-23 02:41:55.387199+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('335c0792-e640-48ee-b183-ce840242125f', '110cfc94-8387-4173-be64-07fbac267a91', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-23 02:41:55.415568+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('754b7722-b9ca-4392-815e-aa5f09bec513', '110cfc94-8387-4173-be64-07fbac267a91', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-23 02:41:55.414706+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('2fd17452-1789-4691-b76b-d7cc3ab16361', '110cfc94-8387-4173-be64-07fbac267a91', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-completed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:41:55.449913+00', '2ecf14aafdc0c4016c28daa53e863728959afca02f9b5503a122173ab2f30a04');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6ebc7042-2887-4c87-8a71-356948d123ee', '110cfc94-8387-4173-be64-07fbac267a91', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-23 02:41:55.580123+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e4fcfbd3-ae3c-44da-b03e-cacb57085e9d', '110cfc94-8387-4173-be64-07fbac267a91', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-23 02:41:56.74+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('027b21a4-7e92-4430-ab7f-e0c3d0fa8a3e', '110cfc94-8387-4173-be64-07fbac267a91', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-23T02:41:57.794073Z", "signalName": "express"}', '2026-11-23 02:41:56.806166+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('45db8459-8e55-40e8-af06-8d014add034d', '110cfc94-8387-4173-be64-07fbac267a91', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-23 02:41:57.863087+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('228362ff-5238-405b-aaa6-3aad00858dab', '110cfc94-8387-4173-be64-07fbac267a91', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-23 02:41:57.912253+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('581c4d34-6664-44d4-b097-36bc34e0d897', '110cfc94-8387-4173-be64-07fbac267a91', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-23 02:41:58.023522+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('43b99376-9091-4bf3-a8a5-ec989238f443', '110cfc94-8387-4173-be64-07fbac267a91', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-20T02:41:58.064172Z", "signalName": "approval"}', '2026-11-23 02:41:58.072452+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('071323f2-eb33-4c69-8be1-bee75156ee16', '110cfc94-8387-4173-be64-07fbac267a91', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-23 02:41:58.163989+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dfc72621-cb09-4644-ae93-dba804dee2f1', '110cfc94-8387-4173-be64-07fbac267a91', 8010, 'ACTIVITY_COMPLETED', 'seed-order.charge', '{"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}', '2026-11-23 02:41:58.212325+00', '4f7957db6f7b556032ab536a356340cca8b51fb6d1726e046db09ede5858e8e5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9b0b680c-2a8c-4eb0-aabc-2276b7216696', '110cfc94-8387-4173-be64-07fbac267a91', 8011, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:note', '"note for seed-completed"', '2026-11-23 02:41:58.244379+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a7e93cef-cf17-40a8-a91d-e388241babe2', '110cfc94-8387-4173-be64-07fbac267a91', 8012, 'WORKFLOW_COMPLETED', NULL, '{"note": "note for seed-completed", "token": "c37ab9e5-d04b-42a9-8e65-4d6b7c046acc", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-23T02:41:55.312691Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', '2026-11-23 02:41:58.268113+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('f9e60bda-7366-4959-8dcf-5b54e4246daa', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-approval", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-23 02:41:58.392724+00', 'b6195227c62ead98ed1b3b177bfa8f2bb53caca058a40f3326a52ea2b6c24a86');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('50ac4dd6-3d6c-4705-854f-b1b68f588a43', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-23T02:41:58.416717Z"', '2026-11-23 02:41:58.416774+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('43144f82-2314-4cda-9be1-06a955202c48', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"faa583bc-0d41-4d0e-af6c-ac5309fa2ca8"', '2026-11-23 02:41:58.434573+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fed89689-ab6d-46ec-a582-d318f60cae72', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-23 02:41:58.454163+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('51162688-3614-4ca7-8237-7c61f2112973', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-23 02:41:58.472024+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e040f90a-6f99-4dec-a3df-00733f624a91', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-23 02:41:58.49182+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7467b257-b471-4984-9993-4127c8169553', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-23 02:41:58.491759+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a2443f58-6822-4215-81b3-c520bf5d13c2', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-approval", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:41:58.529884+00', 'c2607e49b4ab277032d58069d85787ad440a6041aefa57dab52b717a1cc9b16f');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7d3fac8a-6404-4409-a71c-23511989337f', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-23 02:41:58.555214+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('eee07c9a-ef63-4706-8391-16d64def0f81', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-23 02:41:59.583348+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ef03c064-9ad5-4205-8761-0029be5b0cff', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-23T02:42:00.636952Z", "signalName": "express"}', '2026-11-23 02:41:59.643976+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8a4c0379-d8f0-4e53-af92-a559b6eb59e1', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-23 02:42:00.706521+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8231303c-66a6-4865-8f55-dea2ea8926aa', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-23 02:42:00.755098+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7cb24731-78de-47b0-9ea7-b4b0004c69c5', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-23 02:42:00.813064+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ca0c5fcf-f81c-487c-b6bd-71ed4a93e38b', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-20T02:42:00.860795Z", "signalName": "approval"}', '2026-11-23 02:42:00.868925+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cce1d2ec-bb5b-4740-93cc-7011c3ed8c60', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-terminated", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-23 02:42:01.170692+00', '785d82a01f4a3938f2f8d5ae7601a6d490bf7cfd73d10ee4ce728ea649d991cb');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d0debd22-c21b-43e5-9142-1f8f205413f1', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-23T02:42:01.188041Z"', '2026-11-23 02:42:01.18809+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('cdafd892-bfd5-4b41-9be3-a77d7e43fb4e', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"5bf09f86-24cb-4866-9d06-ac28a1d77aa3"', '2026-11-23 02:42:01.204386+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('1b9b7928-c3a7-4c8c-af7e-3abda145f5f7', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-23 02:42:01.220443+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e9f4bd23-5248-4c29-9867-0e5fbf2ba611', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-23 02:42:01.236354+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('948ca485-8478-47de-8840-dcccb11a8328', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-23 02:42:01.252564+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c50d1902-c132-405c-acf7-f456879dc754', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-23 02:42:01.252563+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('03fc1435-1be2-432d-a581-8c4bb6eff3ed', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-terminated", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:01.269782+00', 'aaf06686d0cc7c8cba8970dc79c375fc1ce1be0e307c7e044520e11bbf08b1bc');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('84cec9f0-06f2-40a6-a5c5-85bd9a3819eb', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-23 02:42:01.292772+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dc6e654a-0b0f-47ff-9aee-3c0d8ffbfd4f', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-23 02:42:02.460642+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('efdb8fc0-4e11-4867-b9f8-267b208929cc', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-23T02:42:03.540305Z", "signalName": "express"}', '2026-11-23 02:42:02.547919+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6bcb08f9-bfee-4f4e-a4c7-f63cb733fb8f', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-23 02:42:03.588802+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('31f07569-8b25-4064-9d06-d13cf1df638f', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-23 02:42:03.630889+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3826dda6-7391-442d-b792-f2ee9c9f9f6c', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-23 02:42:03.695063+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('87d4d631-19a7-4cd3-b46e-e775f1324bf3', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-20T02:42:03.749295Z", "signalName": "approval"}', '2026-11-23 02:42:03.755913+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8ecd83ca-2d22-48ff-9679-df272199f291', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-crashed", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-23 02:42:04.1421+00', 'e00fde43b12ebd50fb69f3fe435e050d9a6300f7fc0a13f99bebde6e91498fa5');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c2001ba5-6322-4daa-a94e-246f55c34b3a', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-23T02:42:04.163170Z"', '2026-11-23 02:42:04.163225+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('56d580e2-4bfd-4f8c-911a-02eae7cc640e', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"86d6b267-6f85-4bda-8696-e5d879116123"', '2026-11-23 02:42:04.180688+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('19380acd-98dd-4f15-87d3-4611f3e12d42', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-23 02:42:04.199377+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6da0bb20-9a5e-4b3d-8d27-4f6d844ba8c7', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-23 02:42:04.214102+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a81d7f7f-54d9-4448-9ffd-caae56136c88', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-23 02:42:04.230874+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4e4bb0a3-f56e-4147-b51a-ce36b29f3d97', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-23 02:42:04.230884+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('370658ea-40f1-4c77-9482-0fd69b965211', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-crashed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:04.249297+00', '9afaaca3ce7e4299920c56b10762cde56e24090ad65cc2266e61c3d248a41109');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b4b27c18-bcda-4d43-89f0-b1c2c1c321d1', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-23 02:42:04.286587+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('83cbff01-e7d6-4242-80f7-4cda0618d714', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-23 02:42:05.491091+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('347e68df-9580-4afd-b54c-71ff0491e5a4', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "timerId": "await-signal-8004", "deadline": "2026-11-23T02:42:06.551379Z", "signalName": "express"}', '2026-11-23 02:42:05.560915+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4e484387-9a52-42fa-9acb-aa1d985f886a', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8005, 'SIGNAL_TIMEOUT', '$maestro:awaitSignal:express', '{"timeout": "PT1S", "signalName": "express"}', '2026-11-23 02:42:06.602426+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e4e4f861-2571-4112-a84c-8b4d50e05122', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8006, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8006", "duration": "PT24H"}', '2026-11-23 02:42:06.645094+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('dc30d20d-e3e0-4f16-b504-16b6d3d8c331', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8007, 'TIMER_CANCELLED', '$maestro:timer-cancelled', NULL, '2026-11-23 02:42:06.695782+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('bd95186e-0a6f-4e9b-ac33-bfc5c32a5ed7', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8008, 'TIMER_SCHEDULED', '$maestro:awaitSignal:approval', '{"timeout": "PT87600H", "timerId": "await-signal-8008", "deadline": "2036-11-20T02:42:06.739938Z", "signalName": "approval"}', '2026-11-23 02:42:06.746317+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0ff6f6d1-1c14-4959-977a-6c842af5df3a', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 8009, 'SIGNAL_RECEIVED', '$maestro:awaitSignal:approval', '{"level": 1, "approver": "capture"}', '2026-11-23 02:42:06.835522+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3a19c6a0-b111-4062-bca9-4de33841afdf', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"plain"', '2026-11-23 02:42:06.976652+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('d1a0e523-356a-4149-8591-759200297b95', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"plain"', '2026-11-23 02:42:06.993136+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('404c7849-a231-4d31-bdd5-c5911f3941a1', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for plain", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:07.009115+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('95e7051b-6608-4804-837d-28ddd977ad3f', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-23 02:42:07.043194+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c44350ba-fba6-44d4-85a0-411341c05e95', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-23 02:42:07.076556+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8c923726-f467-4b61-80e0-feccd1c052c3', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-23 02:42:07.084247+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('30a63342-4c57-4029-aed7-503d9f6af18c', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-23 02:42:07.115876+00', '1dc0b7f9a35178cb736158bc8599eddb8d827efa75cb7bee6b11b1d167137b78');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('488379c0-494c-4036-bb46-9e237fe0cfea', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-23 02:42:07.122709+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('aba04a7f-8cc9-4862-82e7-b21fe50cd362', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', NULL, '2026-11-23 02:42:07.136393+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('a9a7ae0d-4cb6-4868-a276-c3232f36d364', '1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-23 02:42:07.155636+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ad5ba73a-acfe-423a-b165-bf72b3d1ccaa', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"fail-refund:x"', '2026-11-23 02:42:07.259638+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('30f111a2-01c6-4556-a3e3-93e4f1604975', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"fail-refund:x"', '2026-11-23 02:42:07.274814+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('04c23d71-ca4b-42f8-9a92-b325f2a1dfb2', '8cf96464-70ff-4355-9bb5-02831ebe5470', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-23 02:42:09.294222+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3aaa11c6-6e53-4cdf-b12a-32be9b865083', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:07.291196+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('5f0ef367-a88c-4ee1-ab11-e85566414828', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-23 02:42:07.321908+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0da84206-f620-4fbd-8181-eb63a266c8fd', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 5001, 'ACTIVITY_FAILED', 'seed-saga.refund', '{"message": "refund rejected for fail-refund:x", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:07.354047+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b2a6298a-0004-4a4f-91bc-e2bf1b0ca6c9', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 5000, 'COMPENSATION_STEP_FAILED', 'seed-saga.refund', '{"message": "Activity ''seed-saga.refund'' failed in workflow ''seed-saga-compensation-failed'': refund rejected for fail-refund:x", "stepName": "seed-saga.refund", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException"}', '2026-11-23 02:42:07.364565+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('77bff64a-04ed-44ab-9f7b-88420d51d809', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 6001, 'ACTIVITY_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-23 02:42:07.392042+00', '9766e5da1752115f7425f1c8b804eef6c2d47a812ee1a46a46dbd69b83bc4605');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('85b6ef7b-7c04-40d3-832b-e503baeab954', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 6000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.releaseReservation', NULL, '2026-11-23 02:42:07.398539+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0e9e3ee1-11ed-49c1-986e-ae1cefe33e8d', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 7001, 'COMPENSATION_COMPLETED', '$maestro:compensation', '{"failedSteps": ["seed-saga.refund"], "totalCompensations": 2}', '2026-11-23 02:42:07.413207+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('03ed1288-3a0a-4860-ae55-3823122edb0e', '480d7e1a-95c5-4ff1-8d38-97f004013e31', 7002, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', '2026-11-23 02:42:07.429348+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c5c9fc5c-611a-4d3d-9239-8b88b898adb0', '5d1f5faf-2048-4592-ab59-d4a7a9e5e517', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"crash-compensation"', '2026-11-23 02:42:07.567234+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e309dca5-83eb-4786-aa1a-50a54a1c7445', '5d1f5faf-2048-4592-ab59-d4a7a9e5e517', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"crash-compensation"', '2026-11-23 02:42:07.581328+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('035d0715-22ee-4ed4-ae25-6b0f0a321e7e', '5d1f5faf-2048-4592-ab59-d4a7a9e5e517', 3, 'ACTIVITY_FAILED', 'seed-saga.ship', '{"message": "no carrier for crash-compensation", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:07.598545+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('8d735d9c-4ad4-4720-9e0a-5ce83229d7f3', '5d1f5faf-2048-4592-ab59-d4a7a9e5e517', 4, 'COMPENSATION_STARTED', '$maestro:compensation', NULL, '2026-11-23 02:42:07.629901+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('58798d90-9339-4cf4-871a-e632ec511c6b', '5d1f5faf-2048-4592-ab59-d4a7a9e5e517', 5001, 'ACTIVITY_COMPLETED', 'seed-saga.refund', NULL, '2026-11-23 02:42:07.660613+00', '749a2d9df4ba0778d1ee0db97e2a1014e270254bd572ee0a0154d6df0756ba30');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('fd3610a8-977e-4d60-985a-69d886f53b3f', '5d1f5faf-2048-4592-ab59-d4a7a9e5e517', 5000, 'COMPENSATION_STEP_COMPLETED', 'seed-saga.refund', NULL, '2026-11-23 02:42:07.66751+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b8c8c1a0-a51a-4c0d-94d0-c81d29b9ea19', 'b65afe1d-45fe-488d-bc9a-5f76312a26e7', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:07.900503+00', 'ae55ac050582d8056dcfc24a102fc318460211527edd40f09779fa2b2de4f2ec');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('51a754d2-31ab-4417-98ea-91316d8cc264', 'b65afe1d-45fe-488d-bc9a-5f76312a26e7', 2, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-23 02:42:07.924928+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('540d35d9-7a86-456c-bd6d-000b71a97825', '2e767e2a-0067-4eaf-adec-107710e07d0a', 1, 'ACTIVITY_FAILED', 'seed-failing.explode', '{"message": "exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:08.12951+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9e51cc98-eb7c-4f09-b6f5-dca987d8f0c5', '2e767e2a-0067-4eaf-adec-107710e07d0a', 2, 'ACTIVITY_COMPLETED', 'seed-failing.alert', '"alerted:seed-alert-failed"', '2026-11-23 02:42:08.148239+00', 'cf18fcd511dace430ccf4bd4a6c975d976a520e4295d4f8de5eda0b5bac31160');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3234971e-bfc5-4930-84b2-731fa4fbdf5d', '2e767e2a-0067-4eaf-adec-107710e07d0a', 3, 'WORKFLOW_FAILED', NULL, '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', '2026-11-23 02:42:08.169686+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b1ef3b0f-0257-4e26-a336-dd9548b67196', 'f71f1204-bea7-435f-a176-2d8b6e76c0ff', 1, 'ACTIVITY_COMPLETED', 'seed-typed.exactAmount', '12345678901234567890.123456789', '2026-11-23 02:42:08.2769+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('0f7db1f9-6736-475b-8f1b-f8bdacd6161e', 'cfcb1527-7cd5-4250-bf00-aa0f9a808332', 1, 'ACTIVITY_COMPLETED', 'seed-typed.localStamp', '"2026-11-12T10:00:00+02:00"', '2026-11-23 02:42:08.292825+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('35e4feac-256f-403c-8952-d26544ad1480', 'c3212992-e08b-4dbc-b5b5-3b27d37c11b9', 1, 'ACTIVITY_COMPLETED', 'seed-typed.tags', '["delta", "beta", "gamma", "alpha"]', '2026-11-23 02:42:08.310265+00', '5feceb66ffc86f38d952786c6d696c79c2dbc239dd4e91b46729d73a27fb57e9');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('da7b18d2-cfcf-4188-8838-629715d78184', 'f71f1204-bea7-435f-a176-2d8b6e76c0ff', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-20T02:42:08.318389Z", "signalName": "go"}', '2026-11-23 02:42:08.324938+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('05acb5e1-7477-4f68-aa9c-1852a3c7d621', 'cfcb1527-7cd5-4250-bf00-aa0f9a808332', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-20T02:42:08.325198Z", "signalName": "go"}', '2026-11-23 02:42:08.332626+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7d3cacd6-4715-4a88-bc4d-d2e2bb08a58b', 'c3212992-e08b-4dbc-b5b5-3b27d37c11b9', 2, 'TIMER_SCHEDULED', '$maestro:awaitSignal:go', '{"timeout": "PT87600H", "timerId": "await-signal-2", "deadline": "2036-11-20T02:42:08.339920Z", "signalName": "go"}', '2026-11-23 02:42:08.34843+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ea11a0f5-6720-4f85-86b6-807140f380cc', '134f4ff5-0d65-4a64-ae85-2b52fa64523e', 1, 'ACTIVITY_COMPLETED', 'seed-saga.reserve', '"parked"', '2026-11-23 02:42:08.929138+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('08bfabc4-25e5-4ac9-aa6d-e6195025b346', '134f4ff5-0d65-4a64-ae85-2b52fa64523e', 2, 'ACTIVITY_COMPLETED', 'seed-saga.charge', '"parked"', '2026-11-23 02:42:08.944822+00', '30c8c7816d4013d405f3cfc4bb658b5fefdb8e945a5d37daf2ad9f6271a9ffea');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9b13c172-8106-494e-8872-437d03613314', '134f4ff5-0d65-4a64-ae85-2b52fa64523e', 3, 'TIMER_SCHEDULED', '$maestro:awaitSignal:ship-now', '{"timeout": "PT87600H", "timerId": "await-signal-3", "deadline": "2036-11-20T02:42:08.978344Z", "signalName": "ship-now"}', '2026-11-23 02:42:08.987568+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('ae036d7b-67a7-4521-93cd-6b3eeb3034ee', '8cf96464-70ff-4355-9bb5-02831ebe5470', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-timer", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-23 02:42:09.206556+00', '558ccdf6e173c89faa47667cf30f6a9d89ab597ce095afe18363ca028ba5882d');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('b6adcd13-466a-4c56-b98f-c60b58ad8e9f', '8cf96464-70ff-4355-9bb5-02831ebe5470', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-23T02:42:09.221868Z"', '2026-11-23 02:42:09.221913+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('3c1d6f73-1f52-4e56-801f-be111ca99cdd', '8cf96464-70ff-4355-9bb5-02831ebe5470', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"d1e693bb-f1c0-4b77-9a69-8cef2d8632f1"', '2026-11-23 02:42:09.238011+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6ef9756b-5708-44fa-835c-1865375838b2', '8cf96464-70ff-4355-9bb5-02831ebe5470', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-23 02:42:09.258897+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4f7cfac1-4eb7-4a1f-95e3-e224766e7786', '8cf96464-70ff-4355-9bb5-02831ebe5470', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-23 02:42:09.277784+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('4cd13f11-ea62-444e-bf27-f83562228c2e', '8cf96464-70ff-4355-9bb5-02831ebe5470', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-23 02:42:09.2943+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6c1ecc42-689b-4ef6-81a6-d4d476b86a76', '8cf96464-70ff-4355-9bb5-02831ebe5470', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-timer", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:09.311254+00', 'd0eae109e2ce1eb172aa9c675c463121146e996ead38dee0886c8252d36a77ac');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('7baf6396-410d-4882-9b5e-977f2fec23ee', '8cf96464-70ff-4355-9bb5-02831ebe5470', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT10M"}', '2026-11-23 02:42:09.336418+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('492a924b-fd7c-4864-a305-b6310c58ad4f', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 1, 'ACTIVITY_COMPLETED', 'seed-order.quote', '{"lines": [{"sku": "sku-red", "price": 70.99, "quantity": 1}, {"sku": "sku-blue", "price": 80.99, "quantity": 1}], "total": 151.98, "orderId": "seed-parked-express", "currency": "EUR", "validUntil": "2027-01-01T00:00:00Z"}', '2026-11-23 02:42:09.433435+00', '354fbdbc6a8e2e95b3863f5a5582854a39445fa66416df79e67c44532ca4e457');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e33f2a52-3928-41e8-8e72-f6a33ed45c2d', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 2, 'SIDE_EFFECT', '$maestro:currentTime', '"2026-11-23T02:42:09.449004Z"', '2026-11-23 02:42:09.44905+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('405e7b36-3568-4c14-924e-635a9beddb3e', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 3, 'SIDE_EFFECT', '$maestro:randomUUID', '"32434afe-4696-4cd1-b376-a52425270366"', '2026-11-23 02:42:09.464572+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('6cf2a262-a107-4966-a099-bdfa322aea6b', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 4, 'VERSION_MARKER', '$maestro:version:seed-change', '{"version": 1, "changeId": "seed-change"}', '2026-11-23 02:42:09.479792+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('22850ff2-e95e-43d1-a08e-dc83970a824d', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 5, 'SIDE_EFFECT', '$maestro:parallel', '{"branchCount": 2}', '2026-11-23 02:42:09.495521+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('9683463f-bc23-4d0d-b11e-5a2b0e3009af', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 7001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-blue"', '2026-11-23 02:42:09.5145+00', 'ad2b67e0adca8af06944666cf524abc4d29203169ebf8371dd489b49774b92c4');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('167654bc-3732-4c56-850e-4154a6b9f375', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 6001, 'ACTIVITY_COMPLETED', 'seed-order.reserve', '"reservation:sku-red"', '2026-11-23 02:42:09.513832+00', '08cb0c356fcfca151f9000a1b5944096c1ee7193b126a2c0fff8f234bfd15347');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('c57d4b4a-a529-47c1-b991-86c2e96a6da5', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 8001, 'ACTIVITY_FAILED', 'seed-risk.verify', '{"message": "risk check unavailable for seed-parked-express", "exceptionType": "io.b2mash.maestro.integration.upgrade.v0_3_0_rc_5.SeededHistoryWorkflows$SeedFailure"}', '2026-11-23 02:42:09.535133+00', 'e487ac6f64544e0840cd7f51f5490bd78e0b48293e7d15f91f57aa2ca64c9100');
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('91795b21-ceea-4f75-b904-dbd565c5bea0', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 8002, 'TIMER_SCHEDULED', '$maestro:sleep', '{"timerId": "sleep-8002", "duration": "PT1S"}', '2026-11-23 02:42:09.557564+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('e6ac5aeb-0d4e-4c3f-84c6-8fb8520491a4', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 8003, 'TIMER_FIRED', '$maestro:timer-fired', NULL, '2026-11-23 02:42:10.621483+00', NULL);
INSERT INTO public.maestro_workflow_event (id, workflow_instance_id, sequence_number, event_type, step_name, payload, created_at, args_hash) VALUES ('718df87b-b632-4d41-953c-2b4a92def600', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 8004, 'TIMER_SCHEDULED', '$maestro:awaitSignal:express', '{"timeout": "PT10M", "timerId": "await-signal-8004", "deadline": "2026-11-23T02:52:10.668143Z", "signalName": "express"}', '2026-11-23 02:42:10.675056+00', NULL);


--
-- Data for Name: maestro_workflow_instance; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('110cfc94-8387-4173-be64-07fbac267a91', 'seed-completed', '0e1f80cb-a913-4c7e-91b7-afad9a2d7312', 'SeedOrderWorkflow', 'default', 'COMPLETED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-completed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"note": "note for seed-completed", "token": "c37ab9e5-d04b-42a9-8e65-4d6b7c046acc", "orderId": "seed-completed", "receipt": {"amount": 151.98, "orderId": "seed-completed", "chargeId": "charge:seed-completed:1"}, "version": 1, "approver": "capture", "verified": false, "decidedAt": "2026-11-23T02:41:55.312691Z", "napCancelled": true, "reservations": ["reservation:sku-red", "reservation:sku-blue"]}', 'upgrade-fixture', 8011, '2026-11-23 02:41:55.177388+00', '2026-11-23 02:41:58.268113+00', '2026-11-23 02:41:58.268113+00', 9, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 'seed-parked-approval', '8f49fb05-3f8e-4229-8d1b-1dbce676d7bd', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-approval", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-23 02:41:58.363884+00', NULL, '2026-11-23 02:42:00.885587+00', 7, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('923742f5-a575-4740-9ada-c5d6fffa9d1d', 'seed-terminated', '1fcaefb4-f779-4f83-aff6-76e67144c3cf', 'SeedOrderWorkflow', 'default', 'TERMINATED', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-terminated", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', '{"reason": "seeded termination", "terminatedBy": "admin"}', 'upgrade-fixture', 0, '2026-11-23 02:42:01.145082+00', '2026-11-23 02:42:04.010901+00', '2026-11-23 02:42:04.010901+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('b170ef4f-00d1-4fe2-87ee-4826628b0801', 'seed-crashed', '38a3bfae-b9d2-4ad5-8138-ce53db270014', 'SeedOrderWorkflow', 'default', 'RUNNING', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-crashed", "napMillis": 1000, "expressWaitMillis": 1000, "cancellableNapMillis": 86400000}', NULL, 'upgrade-fixture', 0, '2026-11-23 02:42:04.104019+00', NULL, '2026-11-23 02:42:06.863175+00', 8, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('1eefa661-d3ed-4dc3-8823-86bddcf3c7fc', 'seed-saga-compensated', '4fcd1676-1f30-4b46-b65c-f2023d738746', 'SeedSagaWorkflow', 'default', 'FAILED', '"plain"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensated'': no carrier for plain", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-23 02:42:06.949596+00', '2026-11-23 02:42:07.155636+00', '2026-11-23 02:42:07.155636+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('480d7e1a-95c5-4ff1-8d38-97f004013e31', 'seed-saga-compensation-failed', 'aa02113d-9b70-4d33-9efe-fabb903c34f9', 'SeedSagaWorkflow', 'default', 'FAILED', '"fail-refund:x"', '{"message": "Activity ''seed-saga.ship'' failed in workflow ''seed-saga-compensation-failed'': no carrier for fail-refund:x", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [3]}', 'upgrade-fixture', 7001, '2026-11-23 02:42:07.238296+00', '2026-11-23 02:42:07.429348+00', '2026-11-23 02:42:07.429348+00', 2, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('5d1f5faf-2048-4592-ab59-d4a7a9e5e517', 'seed-compensating-crashed', 'ab35eb4c-0757-448d-bbd3-d2778a10a2e7', 'SeedSagaWorkflow', 'default', 'COMPENSATING', '"crash-compensation"', NULL, 'upgrade-fixture', 0, '2026-11-23 02:42:07.545991+00', NULL, '2026-11-23 02:42:07.621249+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('b65afe1d-45fe-488d-bc9a-5f76312a26e7', 'seed-activity-failed', '1ae1d360-3057-4a76-a986-ced4be096ee8', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-activity-failed", "alertBeforeRethrow": false}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-activity-failed'': exploded on seed-activity-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 1, '2026-11-23 02:42:07.773713+00', '2026-11-23 02:42:07.924928+00', '2026-11-23 02:42:07.924928+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('2e767e2a-0067-4eaf-adec-107710e07d0a', 'seed-alert-failed', '951f1285-e5a9-4d7d-b312-bef334cd51ca', 'SeedFailingWorkflow', 'default', 'FAILED', '{"key": "seed-alert-failed", "alertBeforeRethrow": true}', '{"message": "Activity ''seed-failing.explode'' failed in workflow ''seed-alert-failed'': exploded on seed-alert-failed", "exceptionType": "io.b2mash.maestro.core.exception.ActivityExecutionException", "failedSequences": [1]}', 'upgrade-fixture', 2, '2026-11-23 02:42:07.998604+00', '2026-11-23 02:42:08.169686+00', '2026-11-23 02:42:08.169686+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('f71f1204-bea7-435f-a176-2d8b6e76c0ff', 'seed-wp1-decimal', 'ffa25f19-0e2d-496b-8560-d89711c485e9', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "DECIMAL"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-23 02:42:08.250404+00', NULL, '2026-11-23 02:42:08.340513+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('cfcb1527-7cd5-4250-bf00-aa0f9a808332', 'seed-wp1-offset', 'f7eb297d-4054-4bb2-976c-0947122810a8', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "OFFSET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-23 02:42:08.26899+00', NULL, '2026-11-23 02:42:08.348471+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('c3212992-e08b-4dbc-b5b5-3b27d37c11b9', 'seed-wp1-set', 'de9eabf6-da1b-4dc0-9d5a-7b7f70267111', 'SeedTypedResultWorkflow', 'default', 'WAITING_SIGNAL', '{"kind": "SET"}', NULL, 'upgrade-fixture-wp1', 0, '2026-11-23 02:42:08.285764+00', NULL, '2026-11-23 02:42:08.366192+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('134f4ff5-0d65-4a64-ae85-2b52fa64523e', 'seed-saga-parked', 'ec6e032d-603d-48f2-a4e1-078d4b64a03a', 'SeedParkedSagaWorkflow', 'default', 'WAITING_SIGNAL', '"parked"', NULL, 'upgrade-fixture-saga', 0, '2026-11-23 02:42:08.90282+00', NULL, '2026-11-23 02:42:09.008013+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('8cf96464-70ff-4355-9bb5-02831ebe5470', 'seed-parked-timer', '95075b0a-2eb4-4ba4-aede-2b41009f1ec2', 'SeedOrderWorkflow', 'default', 'WAITING_TIMER', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-timer", "napMillis": 600000, "expressWaitMillis": 1000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-23 02:42:09.183462+00', NULL, '2026-11-23 02:42:09.351754+00', 1, 0);
INSERT INTO public.maestro_workflow_instance (id, workflow_id, run_id, workflow_type, task_queue, status, input, output, service_name, event_sequence, started_at, completed_at, updated_at, version, lease_epoch) VALUES ('8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 'seed-parked-express', '830870a0-9b65-4c0b-b31a-62311046c4ab', 'SeedOrderWorkflow', 'default', 'WAITING_SIGNAL', '{"skus": ["sku-red", "sku-blue"], "orderId": "seed-parked-express", "napMillis": 1000, "expressWaitMillis": 600000, "cancellableNapMillis": 1}', NULL, 'upgrade-fixture', 0, '2026-11-23 02:42:09.411057+00', NULL, '2026-11-23 02:42:10.690202+00', 3, 0);


--
-- Data for Name: maestro_workflow_signal; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('7dd54406-b401-4226-8783-590414bb693d', '110cfc94-8387-4173-be64-07fbac267a91', 'seed-completed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-23 02:41:58.145654+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('be443bdb-a443-4fd2-a73e-14986951cd4e', '110cfc94-8387-4173-be64-07fbac267a91', 'seed-completed', 'note', '"note for seed-completed"', true, '2026-11-23 02:41:55.285078+00', NULL, 'client:seed-completed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('b3e606e8-4410-4d08-9de0-0a8b198c529c', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 'seed-parked-approval', 'note', '"note for seed-parked-approval"', false, '2026-11-23 02:41:58.405197+00', NULL, 'client:seed-parked-approval:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('04cba31e-c41a-433d-8863-6baff65d7b11', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 'seed-terminated', 'note', '"note for seed-terminated"', false, '2026-11-23 02:42:01.1781+00', NULL, 'client:seed-terminated:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('bf233a48-26c0-4a46-9b26-51acf2b6e462', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 'seed-crashed', 'note', '"note for seed-crashed"', false, '2026-11-23 02:42:04.150945+00', NULL, 'client:seed-crashed:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('7493e336-550a-4c97-b6de-bc562c468a29', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 'seed-crashed', 'approval', '{"level": 1, "approver": "capture"}', true, '2026-11-23 02:42:06.81765+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('0ce48d71-c088-4d5a-b518-086867ff10bc', NULL, 'seed-orphan-target', 'approval', '{"level": 1, "approver": "capture"}', false, '2026-11-23 02:42:08.231004+00', NULL, NULL);
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('2fdf7439-07b3-4f9f-b2fe-01bbb0a17784', NULL, 'seed-orphan-target', 'note', '"note for seed-orphan-target"', false, '2026-11-23 02:42:08.247871+00', NULL, 'client:seed-orphan-target:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('bf09a781-02b3-475f-8024-7a937fb320d4', '8cf96464-70ff-4355-9bb5-02831ebe5470', 'seed-parked-timer', 'note', '"note for seed-parked-timer"', false, '2026-11-23 02:42:09.212623+00', NULL, 'client:seed-parked-timer:note');
INSERT INTO public.maestro_workflow_signal (id, workflow_instance_id, workflow_id, signal_name, payload, consumed, received_at, trace_context, message_id) VALUES ('ff4ce2ea-2b21-4283-a67a-d8b4882e7a4b', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 'seed-parked-express', 'note', '"note for seed-parked-express"', false, '2026-11-23 02:42:09.439778+00', NULL, 'client:seed-parked-express:note');


--
-- Data for Name: maestro_workflow_timer; Type: TABLE DATA; Schema: public; Owner: -
--

INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('06e6ce3e-55af-47cc-b2b5-35bb183c9418', '110cfc94-8387-4173-be64-07fbac267a91', 'seed-completed', 'sleep-8002', '2026-11-23 02:41:56.563337+00', 'FIRED', '2026-11-23 02:41:55.56356+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('e8dd2b2f-aaba-49e6-9357-e74d1afca856', '110cfc94-8387-4173-be64-07fbac267a91', 'seed-completed', 'await-signal-8004', '2026-11-23 02:41:57.794073+00', 'FIRED', '2026-11-23 02:41:56.794102+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('149b8064-7ec8-4523-a6d5-0489e5bb327c', '110cfc94-8387-4173-be64-07fbac267a91', 'seed-completed', 'sleep-8006', '2026-11-24 02:41:57.904048+00', 'CANCELLED', '2026-11-23 02:41:57.904053+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('87db637d-adaa-45d9-8eb0-87221512225c', '110cfc94-8387-4173-be64-07fbac267a91', 'seed-completed', 'await-signal-8008', '2036-11-20 02:41:58.064172+00', 'CANCELLED', '2026-11-23 02:41:58.064188+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('94439ab5-e2f1-47ab-a810-4704f27809de', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 'seed-parked-approval', 'sleep-8002', '2026-11-23 02:41:59.547242+00', 'FIRED', '2026-11-23 02:41:58.547246+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('b844ad5f-0fc6-4ad9-9d6d-697acd4eea15', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 'seed-parked-approval', 'await-signal-8004', '2026-11-23 02:42:00.636952+00', 'FIRED', '2026-11-23 02:41:59.636968+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('9e4bf8bb-0aff-4e8e-a0c2-7d570a2cb269', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 'seed-parked-approval', 'sleep-8006', '2026-11-24 02:42:00.746073+00', 'CANCELLED', '2026-11-23 02:42:00.746076+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('8de84cbb-5133-40c4-abac-b69f79b86150', 'aacda9b9-2deb-46fc-98a8-e0d7fe6f9669', 'seed-parked-approval', 'await-signal-8008', '2036-11-20 02:42:00.860795+00', 'PENDING', '2026-11-23 02:42:00.860812+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('e60b99fa-8580-468f-b497-0b1f14019480', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 'seed-terminated', 'sleep-8002', '2026-11-23 02:42:02.285557+00', 'FIRED', '2026-11-23 02:42:01.285561+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('cbfa38c8-9fea-4f41-89a7-29aecf829f4a', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 'seed-terminated', 'await-signal-8004', '2026-11-23 02:42:03.540305+00', 'FIRED', '2026-11-23 02:42:02.540319+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('2287cae6-d3db-4050-b764-baefe01d2270', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 'seed-terminated', 'sleep-8006', '2026-11-24 02:42:03.623167+00', 'CANCELLED', '2026-11-23 02:42:03.623176+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('381357a5-739e-4ae5-844c-3e04d6c4fc18', '923742f5-a575-4740-9ada-c5d6fffa9d1d', 'seed-terminated', 'await-signal-8008', '2036-11-20 02:42:03.749295+00', 'PENDING', '2026-11-23 02:42:03.749307+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('e34dee10-ddf2-4eb9-9ca8-fff0e23a1983', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 'seed-crashed', 'sleep-8002', '2026-11-23 02:42:05.278541+00', 'FIRED', '2026-11-23 02:42:04.278547+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('575ade33-73ac-4117-b774-284b6294ca96', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 'seed-crashed', 'await-signal-8004', '2026-11-23 02:42:06.551379+00', 'FIRED', '2026-11-23 02:42:05.551423+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('a0b4fcf2-926b-4d96-bf94-d61d7919f639', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 'seed-crashed', 'sleep-8006', '2026-11-24 02:42:06.638266+00', 'CANCELLED', '2026-11-23 02:42:06.638271+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('d9466fcd-de7c-4aa6-a8a1-f32d8df47227', 'b170ef4f-00d1-4fe2-87ee-4826628b0801', 'seed-crashed', 'await-signal-8008', '2036-11-20 02:42:06.739938+00', 'CANCELLED', '2026-11-23 02:42:06.739955+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('2be6d24a-54e0-4e0b-85b1-544020f3716a', 'f71f1204-bea7-435f-a176-2d8b6e76c0ff', 'seed-wp1-decimal', 'await-signal-2', '2036-11-20 02:42:08.318389+00', 'PENDING', '2026-11-23 02:42:08.318405+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('7bc820ba-b0e0-41f2-8ee8-9b7b0c5ffaa7', 'cfcb1527-7cd5-4250-bf00-aa0f9a808332', 'seed-wp1-offset', 'await-signal-2', '2036-11-20 02:42:08.325198+00', 'PENDING', '2026-11-23 02:42:08.325212+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('9523b184-090f-4f69-9dd5-cc38c404827c', 'c3212992-e08b-4dbc-b5b5-3b27d37c11b9', 'seed-wp1-set', 'await-signal-2', '2036-11-20 02:42:08.33992+00', 'PENDING', '2026-11-23 02:42:08.339938+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('6343997f-7098-4151-97b3-b371c3e6da48', '134f4ff5-0d65-4a64-ae85-2b52fa64523e', 'seed-saga-parked', 'await-signal-3', '2036-11-20 02:42:08.978344+00', 'PENDING', '2026-11-23 02:42:08.978364+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('a9d08bfe-c388-4937-9f09-3536be8e48d8', '8cf96464-70ff-4355-9bb5-02831ebe5470', 'seed-parked-timer', 'sleep-8002', '2026-11-23 02:52:09.329704+00', 'PENDING', '2026-11-23 02:42:09.329711+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('3ae92e04-a280-4183-99aa-12a13f706e74', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 'seed-parked-express', 'sleep-8002', '2026-11-23 02:42:10.550654+00', 'FIRED', '2026-11-23 02:42:09.55066+00');
INSERT INTO public.maestro_workflow_timer (id, workflow_instance_id, workflow_id, timer_id, fire_at, status, created_at) VALUES ('2873aa8b-7256-483b-818a-6b43ea54baf0', '8f3ffa48-aa8f-424c-8334-437cd7bb0e02', 'seed-parked-express', 'await-signal-8004', '2026-11-23 02:52:10.668143+00', 'PENDING', '2026-11-23 02:42:10.668158+00');


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


