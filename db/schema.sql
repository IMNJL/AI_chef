CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    telegram_id BIGINT NOT NULL UNIQUE,
    timezone TEXT NOT NULL DEFAULT 'Europe/Moscow',
    locale TEXT NOT NULL DEFAULT 'ru',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE user_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    work_start_time TIME NOT NULL DEFAULT '09:00',
    work_end_time TIME NOT NULL DEFAULT '18:00',
    prefers_confirmation BOOLEAN NOT NULL DEFAULT true,
    verbosity_level TEXT NOT NULL DEFAULT 'concise',
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE event_creation_sessions (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    step TEXT NOT NULL CHECK (step IN ('WAIT_DATE', 'WAIT_TIME', 'WAIT_TITLE', 'WAIT_DURATION')),
    meeting_date DATE NULL,
    meeting_time TIME NULL,
    meeting_title TEXT NULL,
    duration_minutes INTEGER NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE note_edit_sessions (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    step TEXT NOT NULL CHECK (step IN ('WAIT_NOTE_NUMBER', 'WAIT_NEW_TEXT')),
    mode TEXT NOT NULL DEFAULT 'EDIT' CHECK (mode IN ('EDIT', 'DELETE')),
    target_note_id UUID NULL,
    target_note_number INTEGER NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE user_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rule_type TEXT NOT NULL,
    rule_value JSONB NOT NULL,
    priority INTEGER NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_rules_user_id ON user_rules(user_id);

CREATE TABLE subscriptions (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    plan TEXT NOT NULL CHECK (plan IN ('free', 'pro', 'executive')),
    status TEXT NOT NULL CHECK (status IN ('active', 'trial', 'past_due', 'canceled')),
    current_period_end TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE calendar_days (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day_date DATE NOT NULL,
    busy_level INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(user_id, day_date)
);
CREATE INDEX idx_calendar_days_user_date ON calendar_days(user_id, day_date);

CREATE TABLE inbound_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_type TEXT NOT NULL CHECK (source_type IN ('voice', 'text', 'file', 'forwarded')),
    raw_text TEXT NULL,
    file_url TEXT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    filter_classification TEXT NULL CHECK (filter_classification IN ('IGNORE', 'TASK', 'MEETING', 'INFO_ONLY', 'ASK_CLARIFICATION')),
    filter_confidence DOUBLE PRECISION NULL,
    strategist_payload JSONB NULL,
    processing_status TEXT NOT NULL DEFAULT 'received' CHECK (
        processing_status IN ('received', 'processed', 'failed', 'ignored', 'needs_clarification')
    ),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_inbound_items_user_created ON inbound_items(user_id, created_at DESC);

CREATE TABLE meetings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    calendar_day_id UUID NOT NULL REFERENCES calendar_days(id) ON DELETE CASCADE,
    inbound_item_id UUID NULL REFERENCES inbound_items(id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    location TEXT NULL,
    external_link TEXT NULL,
    google_event_id TEXT NULL,
    status TEXT NOT NULL DEFAULT 'confirmed' CHECK (status IN ('tentative', 'confirmed', 'canceled')),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);
CREATE INDEX idx_meetings_day ON meetings(calendar_day_id);
CREATE INDEX idx_meetings_google_id ON meetings(google_event_id);

CREATE TABLE tasks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    calendar_day_id UUID NOT NULL REFERENCES calendar_days(id) ON DELETE CASCADE,
    inbound_item_id UUID NULL REFERENCES inbound_items(id) ON DELETE SET NULL,
    title TEXT NOT NULL,
    priority TEXT NOT NULL DEFAULT 'medium' CHECK (priority IN ('low', 'medium', 'high', 'critical')),
    completed BOOLEAN NOT NULL DEFAULT false,
    due_at TIMESTAMPTZ NULL,
    google_task_id TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_tasks_day ON tasks(calendar_day_id);
CREATE INDEX idx_tasks_due_at ON tasks(due_at);
CREATE INDEX idx_tasks_google_id ON tasks(google_task_id);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    related_type TEXT NOT NULL CHECK (related_type IN ('task', 'meeting')),
    related_id UUID NOT NULL,
    notify_at TIMESTAMPTZ NOT NULL,
    sent BOOLEAN NOT NULL DEFAULT false,
    sent_at TIMESTAMPTZ NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_due ON notifications(sent, notify_at);
CREATE INDEX idx_notifications_user ON notifications(user_id);

CREATE TABLE memory_entries (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    embedding VECTOR(1536) NOT NULL,
    content TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'agent',
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_memory_user_created ON memory_entries(user_id, created_at DESC);
