-- parity-cc-tools V3: Spring Session JDBC's tables (its schema-postgresql.sql), created here rather than by the
-- starter (spring.session.jdbc.initialize-schema: never). Sessions hold the CSRF token, the operator's
-- OIDC login and its OAuth2 tokens: every attribute is encrypted by the application (SessionEncryption).
CREATE TABLE spring_session (
    primary_id             char(36) NOT NULL,
    session_id             char(36) NOT NULL,
    creation_time          bigint NOT NULL,
    last_access_time       bigint NOT NULL,
    max_inactive_interval  int NOT NULL,
    expiry_time            bigint NOT NULL,
    principal_name         varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  char(36) NOT NULL,
    attribute_name      varchar(200) NOT NULL,
    attribute_bytes     bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO ${appRole};
