-- Sender side. A subscription is a URL plus the secret shared with its owner.
-- During a rotation the previous secret keeps signing until it expires, so a
-- receiver that has not switched yet still verifies.
create table subscription (
    id                         uuid primary key,
    url                        text        not null,
    secret                     text        not null,
    previous_secret            text,
    previous_secret_expires_at timestamptz,
    active                     boolean     not null default true,
    created_at                 timestamptz not null default now()
);

-- The body is frozen when the event is created: every attempt sends the same
-- bytes, and only the timestamp and the signature change between attempts.
create table webhook_event (
    id         text primary key,
    type       text        not null,
    body       text        not null,
    created_at timestamptz not null default now()
);

-- One row per (event, subscription). The dispatcher leases a row, sends it
-- outside any transaction and writes the outcome back. A lease that expires
-- (the instance died mid-flight) makes the row claimable again.
create table delivery (
    id               bigserial primary key,
    event_id         text        not null references webhook_event (id),
    subscription_id  uuid        not null references subscription (id),
    status           text        not null,
    attempts         int         not null default 0,
    next_attempt_at  timestamptz not null default now(),
    lease_until      timestamptz,
    last_status_code int,
    last_error       text,
    created_at       timestamptz not null default now(),
    delivered_at     timestamptz,
    constraint delivery_status_check
        check (status in ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'FAILED')),
    constraint delivery_event_subscription_key unique (event_id, subscription_id)
);

create index delivery_due_idx on delivery (next_attempt_at)
    where status in ('PENDING', 'IN_FLIGHT');

create table delivery_attempt (
    id          bigserial primary key,
    delivery_id bigint      not null references delivery (id),
    attempt     int         not null,
    instance    text        not null,
    started_at  timestamptz not null,
    duration_ms int         not null,
    status_code int,
    error       text
);

create index delivery_attempt_delivery_idx on delivery_attempt (delivery_id);
