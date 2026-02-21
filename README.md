# AI_chef

## Modules
- `backend` — Spring Boot API + bot logic + DB integration
- `frontend` — отдельный Spring Boot модуль, который отдает UI по HTTP
- `frontend_dir` — исходники UI (HTML/CSS/JS), отдаются модулем `frontend` по HTTP

## Run Backend
```bash
mvn -pl backend spring-boot:run
```
Backend URL: `http://localhost:8010`
Swagger: `http://localhost:8010/swagger-ui.html`

## Run Frontend
```bash
mvn -pl frontend spring-boot:run
```
Frontend URL: `http://localhost:5173`
Tasks page: `http://localhost:5173/tasks.html?telegramId=<YOUR_ID>`
Notes page: `http://localhost:5173/notes.html?telegramId=<YOUR_ID>`

## HTTP Integration
Frontend calls backend over HTTP using:
`frontend_dir/config.js`

Default backend API base: `http://localhost:8010`
