# AI_chef

## Что должно быть установлено

### Базовые зависимости

```bash
# Java + Maven
brew install openjdk maven

# PostgreSQL
brew install postgresql@16
brew services start postgresql@16

# Python для локального STT
brew install python
python3 -m venv .venv
source .venv/bin/activate
pip install -U pip
```

### Голосовое распознавание (обязательно для voice)

```bash
# ffmpeg нужен для Vosk и Whisper
brew install ffmpeg

# Whisper CLI (внутри .venv)
pip install -U openai-whisper
```

Проверка:

```bash
java -version
mvn -version
psql --version
which ffmpeg && ffmpeg -version
which whisper
```

### Переменные окружения для STT (`.env`)

```env
PATH=/opt/homebrew/bin:/usr/bin:/bin
APP_WHISPER_COMMAND=/Users/pro/Downloads/AI_chef/.venv/bin/whisper "{input}" --model "{model}" --language Russian --output_format txt --output_dir "{output_dir}" --fp16 False
APP_WHISPER_MODEL=/Users/pro/Downloads/AI_chef/.dist/models/small.pt
APP_VOSK_PYTHON=/Users/pro/Downloads/AI_chef/.venv/bin/python
APP_VOSK_MODEL_PATH=/Users/pro/Downloads/AI_chef/.dist/models/vosk/vosk-model-ru-0.22
```

Важно:
- `APP_WHISPER_COMMAND` должен быть только один раз в `.env` (без дубликатов).
- Если Vosk не нужен, оставьте `APP_VOSK_MODEL_PATH=` пустым.

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
