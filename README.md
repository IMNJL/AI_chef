# AI_chef

## Что в репозитории
- `telegram-backend` — Telegram bot backend (вебхук/поллинг, NLP, интеграции).
- `miniapp-backend` — API для Mini App (календарь, задачи, заметки).
- `frontend` — Spring Boot, который отдает статические Mini App страницы.
- `backend-core` — общий код для backend модулей.

## Локальный запуск
1. Подготовить `.env` (можно скопировать из `.env.example`).
2. Поднять Postgres и создать БД `aichef`.
3. Запустить сервисы:

```bash
mvn -pl miniapp-backend -am spring-boot:run
mvn -pl telegram-backend -am spring-boot:run
mvn -pl frontend -am spring-boot:run
```

Локальные порты по умолчанию:
- miniapp API: `8010`
- telegram backend: `8011`
- frontend: `5174`

---

## Деплой на Render (3 хоста + 1 БД)

В репозитории уже есть `render.yaml`, который создает:
- `aichef-db` (PostgreSQL)
- `aichef-miniapp-api` (Web Service)
- `aichef-telegram` (Web Service)
- `aichef-frontend` (Web Service)

### Шаги
1. Запушить текущую ветку в GitHub.
2. В Render: `New` -> `Blueprint`.
3. Выбрать репозиторий и подтвердить деплой по `render.yaml`.
4. Дождаться создания всех сервисов и БД.
5. Открыть сервис `aichef-frontend` и скопировать URL:
   Пример: `https://aichef-frontend.onrender.com`
6. Открыть сервис `aichef-miniapp-api` и скопировать URL:
   Пример: `https://aichef-miniapp-api.onrender.com`
7. В Render -> `aichef-telegram` -> `Environment` выставить:
   - `APP_PUBLIC_BASE_URL=https://aichef-telegram.onrender.com`
   - `MINIAPP_PUBLIC_URL=https://aichef-frontend.onrender.com/index.html?apiBaseUrl=https://aichef-miniapp-api.onrender.com`
   - `TELEGRAM_BOT_TOKEN=<токен>`
   - `TELEGRAM_BOT_USERNAME=<username бота>`
8. В Render -> `aichef-miniapp-api` -> `Environment` выставить:
   - `MINIAPP_PUBLIC_URL=https://aichef-frontend.onrender.com/`
9. Если нужен Google Calendar:
   - Включить `GOOGLE_CALENDAR_ENABLED=true` у `aichef-telegram` и `aichef-miniapp-api`.
   - Заполнить `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN`.
10. Сделать `Manual Deploy` (или дождаться auto-deploy) для `aichef-telegram` и `aichef-miniapp-api`.

### Проверка
1. Открыть:
   - `https://aichef-miniapp-api.onrender.com/actuator/health`
   - `https://aichef-telegram.onrender.com/actuator/health`
   - `https://aichef-frontend.onrender.com/index.html`
2. В Telegram у бота вызвать Mini App.
3. Проверить, что задачи/заметки/календарь грузятся.

---

## Важно по CORS
`miniapp-backend` уже настроен принимать запросы с:
- `*.onrender.com`
- `*.github.io`
- `localhost`
- `ngrok`/`trycloudflare`

Если используешь другой домен фронта, добавь его origin в `MINIAPP_PUBLIC_URL` и перезапусти backend.
