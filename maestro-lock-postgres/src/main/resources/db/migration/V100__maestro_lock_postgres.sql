CREATE TABLE maestro_distributed_lock (
    lock_key    VARCHAR(512)  PRIMARY KEY,
    token       VARCHAR(64)   NOT NULL,
    expires_at  TIMESTAMPTZ   NOT NULL,
    acquired_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_dist_lock_expires ON maestro_distributed_lock(expires_at);

CREATE TABLE maestro_leader_election (
    election_key  VARCHAR(512)  PRIMARY KEY,
    candidate_id  VARCHAR(255)  NOT NULL,
    expires_at    TIMESTAMPTZ   NOT NULL,
    acquired_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
