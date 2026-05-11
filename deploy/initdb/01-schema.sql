CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_salt VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS user_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_access_at TIMESTAMPTZ NOT NULL,
    user_agent VARCHAR(512)
);

CREATE TABLE IF NOT EXISTS short_links (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    route_key VARCHAR(32) NOT NULL UNIQUE,
    original_url TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NULL,
    total_clicks BIGINT NOT NULL DEFAULT 0,
    custom_alias BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS ix_short_links_owner_created ON short_links(owner_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_short_links_owner_status_created ON short_links(owner_user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_short_links_status_expires ON short_links(status, expires_at);

CREATE TABLE IF NOT EXISTS link_daily_stats (
    short_link_id UUID NOT NULL REFERENCES short_links(id) ON DELETE CASCADE,
    stat_date DATE NOT NULL,
    click_count BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (short_link_id, stat_date)
);

CREATE TABLE IF NOT EXISTS route_key_pool (
    route_key VARCHAR(32) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS retired_route_keys (
    route_key VARCHAR(32) PRIMARY KEY,
    retired_at TIMESTAMPTZ NOT NULL
);
