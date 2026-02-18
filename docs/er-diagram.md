# ER Diagram

```mermaid
erDiagram
    users ||--|| user_profiles : has
    users ||--o{ user_rules : has
    users ||--o| subscriptions : has
    users ||--o{ calendar_days : owns
    calendar_days ||--o{ meetings : contains
    calendar_days ||--o{ tasks : contains
    users ||--o{ notifications : receives
    users ||--o{ memory_entries : stores
    users ||--o{ inbound_items : receives
    inbound_items ||--o| meetings : can_create
    inbound_items ||--o| tasks : can_create

    users {
      uuid id PK
      bigint telegram_id UK
      text timezone
      text locale
      timestamp created_at
      timestamp updated_at
    }

    user_profiles {
      uuid user_id PK, FK
      time work_start_time
      time work_end_time
      boolean prefers_confirmation
      text verbosity_level
      timestamp updated_at
    }

    user_rules {
      uuid id PK
      uuid user_id FK
      text rule_type
      jsonb rule_value
      int priority
      boolean active
      timestamp created_at
    }

    subscriptions {
      uuid user_id PK, FK
      text plan
      text status
      timestamp current_period_end
      timestamp updated_at
    }

    calendar_days {
      uuid id PK
      uuid user_id FK
      date day_date
      int busy_level
      timestamp created_at
      timestamp updated_at
    }

    meetings {
      uuid id PK
      uuid calendar_day_id FK
      uuid inbound_item_id FK
      text title
      timestamptz starts_at
      timestamptz ends_at
      text location
      text external_link
      text google_event_id
      text status
      timestamp created_at
      timestamp updated_at
    }

    tasks {
      uuid id PK
      uuid calendar_day_id FK
      uuid inbound_item_id FK
      text title
      text priority
      boolean completed
      timestamptz due_at
      text google_task_id
      timestamp created_at
      timestamp updated_at
    }

    notifications {
      uuid id PK
      uuid user_id FK
      text related_type
      uuid related_id
      timestamptz notify_at
      boolean sent
      timestamptz sent_at
      timestamp created_at
    }

    memory_entries {
      uuid id PK
      uuid user_id FK
      vector embedding
      text content
      text source
      timestamp created_at
    }

    inbound_items {
      uuid id PK
      uuid user_id FK
      text source_type
      text raw_text
      text file_url
      jsonb metadata
      text filter_classification
      float filter_confidence
      jsonb strategist_payload
      text processing_status
      timestamp created_at
    }
```

## Notes
- `inbound_items` хранит вход и решения агентов для аудита и переигрывания.
- `calendar_days.busy_level` можно считать периодически по сумме встреч и весам задач.
- `notifications.related_type + related_id` дают универсальную связь на `task/meeting`.
