-- Receiver side. The webhook id is the primary key: the insert is the
-- idempotency check, and a duplicate is a conflict, not a race.
create table inbox_event (
    webhook_id        text primary key,
    webhook_timestamp bigint      not null,
    body              text        not null,
    status            text        not null default 'RECEIVED',
    received_at       timestamptz not null default now(),
    processed_at      timestamptz,
    constraint inbox_event_status_check check (status in ('RECEIVED', 'PROCESSED'))
);

create index inbox_event_pending_idx on inbox_event (received_at) where status = 'RECEIVED';

-- Every request that reached the endpoint, accepted or not. This is what shows
-- duplicates and retry bursts from the receiver's point of view.
create table inbound_request (
    id          bigserial primary key,
    webhook_id  text,
    endpoint    text        not null,
    outcome     text        not null,
    status_code int         not null,
    received_at timestamptz not null default clock_timestamp()
);

create index inbound_request_webhook_idx on inbound_request (webhook_id);

-- The counter-example: the handler runs inside the request, with no inbox and
-- no deduplication. One row per time the handler ran.
create table naive_processing (
    id           bigserial primary key,
    webhook_id   text        not null,
    processed_at timestamptz not null default clock_timestamp()
);
