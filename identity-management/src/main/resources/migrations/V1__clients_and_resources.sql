START TRANSACTION;

-- API Resources (The protected APIs/Resource Servers)
CREATE TABLE api_resources (
    id serial PRIMARY KEY,
    name varchar(200) NOT NULL UNIQUE,
    display_name varchar(200),
    description varchar(1000),
    enabled bool NOT NULL DEFAULT true,
    show_in_discovery_document bool NOT NULL DEFAULT false,
    require_resource_indicator bool NOT NULL DEFAULT false,
    allowed_access_token_signing_algorithms varchar(100),
    created timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated timestamptz,
    last_accessed timestamptz,
    non_editable bool NOT NULL DEFAULT false
);

-- Clients (The applications requesting tokens)
CREATE TABLE clients (
    id serial PRIMARY KEY,
    client_id varchar(200) NOT NULL UNIQUE,
    client_name varchar(200),
    protocol_type varchar(200) NOT NULL DEFAULT 'oidc',
    enabled bool NOT NULL DEFAULT true,
    description varchar(1000),
    client_uri varchar(2000),
    logo_uri varchar(2000),
    require_client_secret bool NOT NULL DEFAULT true,
    require_pkce bool NOT NULL DEFAULT true,
    allow_plain_text_pkce bool NOT NULL DEFAULT false,
    require_request_object bool NOT NULL DEFAULT false,
    allow_offline_access bool NOT NULL DEFAULT false,
    -- Lifetimes
    access_token_lifetime int4 NOT NULL DEFAULT 3600,
    authorization_code_lifetime int4 NOT NULL DEFAULT 300,
    identity_token_lifetime int4 NOT NULL DEFAULT 300,
    refresh_token_usage int4 NOT NULL DEFAULT 1,
    refresh_token_expiration int4 NOT NULL DEFAULT 1,
    absolute_refresh_token_lifetime int4 NOT NULL DEFAULT 2592000,
    sliding_refresh_token_lifetime int4 NOT NULL DEFAULT 1296000,
    -- Advanced/Modern Fields
    ciba_lifetime int4,
    polling_interval int4,
    coordinate_lifetime_with_user_session bool,
    require_dpop bool NOT NULL DEFAULT false,
    dpop_validation_mode int4 NOT NULL DEFAULT 0,
    dpop_clock_skew interval NOT NULL DEFAULT '00:00:00',
    initiate_login_uri varchar(2000),
    pushed_authorization_lifetime int4,
    require_pushed_authorization bool NOT NULL DEFAULT false,
    created timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated timestamptz,
    last_accessed timestamptz,
    non_editable bool NOT NULL DEFAULT false
);

-- API Scopes (Individual permissions)
CREATE TABLE api_scopes (
    id serial PRIMARY KEY,
    name varchar(200) NOT NULL UNIQUE,
    display_name varchar(200),
    description varchar(1000),
    enabled bool NOT NULL DEFAULT true,
    required bool NOT NULL DEFAULT false,
    emphasize bool NOT NULL DEFAULT false,
    show_in_discovery_document bool NOT NULL DEFAULT true,
    created timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated timestamptz,
    last_accessed timestamptz,
    non_editable bool NOT NULL DEFAULT false
);

-- Identity Resources (OIDC claims like openid, profile)
CREATE TABLE identity_resources (
    id serial PRIMARY KEY,
    name varchar(200) NOT NULL UNIQUE,
    display_name varchar(200),
    description varchar(1000),
    enabled bool NOT NULL DEFAULT true,
    required bool NOT NULL DEFAULT false,
    emphasize bool NOT NULL DEFAULT false,
    show_in_discovery_document bool NOT NULL DEFAULT true,
    created timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated timestamptz,
    non_editable bool NOT NULL DEFAULT false
);

-- Identity Providers (External Auth like Google/Microsoft)
CREATE TABLE identity_providers (
    id serial PRIMARY KEY,
    scheme varchar(200) NOT NULL UNIQUE,
    display_name varchar(200),
    enabled bool NOT NULL,
    type varchar(20) NOT NULL,
    properties text,
    created timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated timestamptz,
    last_accessed timestamptz,
    non_editable bool NOT NULL DEFAULT false
);

-- Client Relationships
CREATE TABLE client_scopes (
    id serial PRIMARY KEY,
    scope varchar(200) NOT NULL,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE client_redirect_uris (
    id serial PRIMARY KEY,
    redirect_uri varchar(400) NOT NULL,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE client_post_logout_redirect_uris (
    id serial PRIMARY KEY,
    post_logout_redirect_uri varchar(400) NOT NULL,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE client_grant_types (
    id serial PRIMARY KEY,
    grant_type varchar(250) NOT NULL,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE client_secrets (
    id serial PRIMARY KEY,
    value varchar(4000) NOT NULL,
    description varchar(2000),
    expiration timestamptz,
    type varchar(250) NOT NULL,
    created timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE client_claims (
    id serial PRIMARY KEY,
    type varchar(250) NOT NULL,
    value varchar(250) NOT NULL,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE client_cors_origins (
    id serial PRIMARY KEY,
    origin varchar(150) NOT NULL,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE client_idp_restrictions (
    id serial PRIMARY KEY,
    provider varchar(200) NOT NULL,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

CREATE TABLE client_properties (
    id serial PRIMARY KEY,
    key varchar(250) NOT NULL,
    value varchar(2000) NOT NULL,
    client_id int4 NOT NULL REFERENCES clients(id) ON DELETE CASCADE
);

-- API Resource Relationships
CREATE TABLE api_resource_scopes (
    id serial PRIMARY KEY,
    scope varchar(200) NOT NULL,
    api_resource_id int4 NOT NULL REFERENCES api_resources(id) ON DELETE CASCADE
);

CREATE TABLE api_resource_claims (
    id serial PRIMARY KEY,
    type varchar(200) NOT NULL,
    api_resource_id int4 NOT NULL REFERENCES api_resources(id) ON DELETE CASCADE
);

CREATE TABLE api_resource_secrets (
    id serial PRIMARY KEY,
    value varchar(4000) NOT NULL,
    description varchar(1000),
    expiration timestamptz,
    type varchar(250) NOT NULL,
    created timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    api_resource_id int4 NOT NULL REFERENCES api_resources(id) ON DELETE CASCADE
);

CREATE TABLE api_resource_properties (
    id serial PRIMARY KEY,
    key varchar(250) NOT NULL,
    value varchar(2000) NOT NULL,
    api_resource_id int4 NOT NULL REFERENCES api_resources(id) ON DELETE CASCADE
);

-- API Scope Relationships
CREATE TABLE api_scope_claims (
    id serial PRIMARY KEY,
    type varchar(200) NOT NULL,
    scope_id int4 NOT NULL REFERENCES api_scopes(id) ON DELETE CASCADE
);

CREATE TABLE api_scope_properties (
    id serial PRIMARY KEY,
    key varchar(250) NOT NULL,
    value varchar(2000) NOT NULL,
    scope_id int4 NOT NULL REFERENCES api_scopes(id) ON DELETE CASCADE
);

-- Identity Resource Relationships
CREATE TABLE identity_resource_claims (
    id serial PRIMARY KEY,
    type varchar(200) NOT NULL,
    identity_resource_id int4 NOT NULL REFERENCES identity_resources(id) ON DELETE CASCADE
);

CREATE TABLE identity_resource_properties (
    id serial PRIMARY KEY,
    key varchar(250) NOT NULL,
    value varchar(2000) NOT NULL,
    identity_resource_id int4 NOT NULL REFERENCES identity_resources(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ix_client_scopes_client_id_scope ON client_scopes (client_id, scope);
CREATE UNIQUE INDEX ix_client_redirect_uris_client_id_uri ON client_redirect_uris (client_id, redirect_uri);
CREATE UNIQUE INDEX ix_client_grant_types_client_id_grant ON client_grant_types (client_id, grant_type);
CREATE UNIQUE INDEX ix_api_resource_scopes_api_res_scope ON api_resource_scopes (api_resource_id, scope);
CREATE UNIQUE INDEX ix_identity_providers_scheme ON identity_providers (scheme);
COMMIT;