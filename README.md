# AI_chef

## Project docs
- Architecture: `docs/architecture.md`
- Multi-agent prompts: `docs/agents-prompts.md`
- ER diagram: `docs/er-diagram.md`
- SQL schema (PostgreSQL): `db/schema.sql`

## Spring Boot backend
- Java: `21`
- Build: `mvn`
- Config: `.env` (see `.env.example`)

Run:
```bash
mvn -U -Dmaven.test.skip=true spring-boot:run
```

Webhook endpoint:
- `${APP_PUBLIC_BASE_URL}${TELEGRAM_WEBHOOK_PATH}`
