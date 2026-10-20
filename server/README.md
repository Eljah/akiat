# Akiat Server — редакционный Java-сервер татарских аудиосказок

**MVP / не продакшн.** Spring Boot **4.1.1**, Java **17+**, Maven, Spring AI MCP **2.0.1**, H2/JPA, HTTP REST + RSS 2.0 с iTunes-тегами. Код публикуется вместе с Python/Colab в `Eljah/akiat`.

## Текущий объём реализации

* Хранение эмоциональных профилей русских литературных референсов (`emotional_references`) **без копирования текстов**. Три примера из старого редакционного набора автоматически добавляются как **не проверенные**.
* Поиск **кандидатов** на современные детские произведения через Brave Search API по описаниям, названиям и сниппетам. Поиск не скачивает текст целиком; редактор сам подтверждает эмоциональный профиль (`POST /api/references/{id}/review`).
* Генерация уникальной татарской истории в два LLM-вызова (концепция → оригинальный полный текст) через OpenAI-compatible `chat/completions`: можно использовать поддерживаемый облачный API или локальный сервис. Название русского референса не передаётся LLM; передаётся только предварительно вычитанная абстрактная эмоциональная характеристика.
* Планировщик по умолчанию: **понедельник и четверг, 10:00 МСК**, если `AKIAT_AUTOGENERATE=true`. Создаются **только DRAFT**, а не автоматически опубликованные выпуски.
* Редакторские состояния `DRAFT` → `LANGUAGE_APPROVED` → `TTS_QUEUED` → `AUDIO_REVIEW` → `READY` → `PUBLISHED`. Одобрение татарского языка, звука и подтверждение прав обязательны. API не позволяет редактировать опубликованную версию или пропустить проверки.
* HTTP-очередь заданий для отдельного Python-воркера на Silero/Piper/F5. Пример воркера: `../worker/silero_api_worker.py`. Инференс **не** происходит в Java и не требует GPU у сервера.
* Публичные `/feed.xml` и `/media/{id}.mp3` только для `PUBLISHED`. Секретные ключи, неопубликованный звук и редакционные API недоступны анонимно.
* MCP Streamable HTTP на `/mcp`: перечисление референсов и черновиков, поиск кандидатов, запуск генерации черновика; **без инструмента публикации**.

## Запуск на Windows / Linux

Требуются JDK 17+ и Maven 3.6.3+. На Windows переменные задаются через `set` (CMD) или `$env:` (PowerShell). В bash:

```bash
export AKIAT_ADMIN_PASSWORD='CHANGE-ME-TO-A-LONG-SECRET'
export AKIAT_MCP_TOKEN='CHANGE-ME-TO-ANOTHER-LONG-SECRET'

# Без настройки LLM каталог, REST, MCP и RSS работают, но генерация не запускается.
# Для генерации требуется поддерживаемый OpenAI-compatible API:
export AKIAT_LLM_BASE_URL='https://api.openai.com/v1'
export AKIAT_LLM_MODEL='your-enabled-chat-model'
export AKIAT_LLM_API_KEY='your-private-api-key'

mvn -f server/pom.xml clean test
mvn -f server/pom.xml spring-boot:run
# или: mvn -f server/pom.xml package && java -jar server/target/akiat-server-0.1.0-SNAPSHOT.jar
```

При локальном запуске сервер слушает **только 127.0.0.1:8080**. H2 хранит данные в `./data/akiat.mv.db`, файлы озвучки — в `./data/media/` относительно каталога запуска. В `AKIAT_DB_URL` можно задать другое расположение. Файл `/feed.xml` до первого опубликованного выпуска будет пустым — **не отправлять его на модерацию Яндекса, пока нет пилотного MP3 и обложки**.

## 1. Найти и подготовить референс

```bash
# Поиск требует AKIAT_BRAVE_API_KEY (Brave Web Search API).
curl -u "admin:$AKIAT_ADMIN_PASSWORD" \
  'http://127.0.0.1:8080/api/references/discover?q=современные+детские+авторские+сказки'

# Создать редакционную карточку — не текст оригинального произведения:
curl -u "admin:$AKIAT_ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"title":"Сказки про мам","author":"Сергей Седов","sourceUrl":"https://example.org/book","emotionalProfile":"Тепло, абсурд, принятие несовершенства, мягкий финал. Никакой морали.","notes":"Только общий эмоциональный ориентир","audienceAge":5}' \
  http://127.0.0.1:8080/api/references

# Получить UUID, после проверки карточки подтвердить:
curl -u "admin:$AKIAT_ADMIN_PASSWORD" -X POST \
  http://127.0.0.1:8080/api/references/REFERENCE_UUID/review
```

Три известные карточки из `resources/emotional_references.csv` уже появляются при первом старте, но требуют `review`. Автоматический поиск в сети **не равен** достоверному анализу полного текста произведения. Не указывать "проверено", пока редактор не проверил источник.

## 2. Регулярно генерировать, вычитывать и озвучивать

```bash
# Генерация ОДНОГО неопубликованного татарского черновика (требуется настроенный LLM):
curl -u "admin:$AKIAT_ADMIN_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"referenceId":"REFERENCE_UUID"}' \
  http://127.0.0.1:8080/api/stories/generate

# Список неопубликованных черновиков:
curl -u "admin:$AKIAT_ADMIN_PASSWORD" \
  'http://127.0.0.1:8080/api/stories?state=DRAFT'

# Редактор исправляет текст: PUT /api/stories/STORY_UUID/text
# Только после ручной татарской вычитки:
curl -u "admin:$AKIAT_ADMIN_PASSWORD" -X POST \
  http://127.0.0.1:8080/api/stories/STORY_UUID/approve-language

# Создать задачу на озвучку:
curl -u "admin:$AKIAT_ADMIN_PASSWORD" -X POST \
  http://127.0.0.1:8080/api/stories/STORY_UUID/queue-tts
```

Подключение воркера (только с правомерно используемым голосом и установленной моделью Silero):

```bash
pip install requests numpy torch
export AKIAT_ENDPOINT='http://127.0.0.1:8080'
export AKIAT_ADMIN_PASSWORD='same-admin-secret'
python worker/silero_api_worker.py
```

Воркер опрашивает `POST /api/tts/jobs/claim`, формирует MP3 через `ffmpeg` (192 кбит/с), отсылает его на `POST /api/tts/jobs/{id}/complete` и переводит сказку в `AUDIO_REVIEW`. Проверьте голос, произношение, громкость, эмоциональные паузы, отсутствие артефактов и длительность:

```bash
curl -u "admin:$AKIAT_ADMIN_PASSWORD" -X POST \
  http://127.0.0.1:8080/api/stories/STORY_UUID/approve-audio
# Удостовериться в правах на литературу, фонограмму и голос:
curl -u "admin:$AKIAT_ADMIN_PASSWORD" -X POST \
  http://127.0.0.1:8080/api/stories/STORY_UUID/confirm-rights
# Сделать выпуск публичным:
curl -u "admin:$AKIAT_ADMIN_PASSWORD" -X POST \
  http://127.0.0.1:8080/api/stories/STORY_UUID/publish
```

Все записи без аудио или подтверждённых прав остаются непубличными. Для новых голосов Piper/F5 нужно написать отдельный Python worker по той же HTTP-схеме; Java API не зависит от модели.

## 3. RSS → Яндекс Музыка → Алиса

1. Настроить публичный хостинг/HTTPS с `AKIAT_BIND=0.0.0.0` **только за HTTPS-reverse-proxy, firewalls и защитой `/api/**`, `/mcp`**, указать `AKIAT_PUBLIC_URL=https://your-domain`, `AKIAT_COVER_URL=https://your-domain/cover.jpg` и обложку достаточного разрешения. Не публиковать админ-пароль.
2. Создать первый **одобренный** эпизод. Проверьте `curl -i https://your-domain/feed.xml` и `curl -I https://your-domain/media/<id>.mp3`. Сервер должен отдавать аудио с корректным Content-Type, длиной и поддержкой HTTP Range; это дополнительно проверяется на реальном деплое.
3. Подать один раз RSS URL через [официальную форму размещения Яндекс Музыки](https://yandex.com/support/music-artists/ru/podcast-authors/audio-placement). Это ручная модерация, *не API-загрузка каждого MP3*.
4. После принятия RSS Яндекс периодически проверяет ленту, появляются новые опубликованные эпизоды. Конкретная скорость индексации и голосовое обнаружение через Алису не гарантированы; проверить на реальном устройстве отдельно.

**Критично:** наличие художественного контента в RSS само по себе не гарантирует приём в категорию подкастов. Если Яндекс классифицирует это как аудиокниги, потребуется отдельный дистрибьютор/лицензия; самостоятельно заявлять, что это уже допущенный подкаст, нельзя.

## 4. MCP для ChatGPT / Codex и автоматизаций

Endpoint: `http://127.0.0.1:8080/mcp`, транспорт Streamable HTTP через официальный Spring AI starter.

* `list_emotional_references` — список ориентиров;
* `search_fairy_tale_references` — поиск кандидатов;
* `create_akiat_draft` — создать черновик по рецензированному эмоциональному профилю;
* `list_akiat_drafts` — список на редактуру;
* `get_akiat_story` — текст и статус.

Для доступа к MCP используйте `Authorization: Bearer <AKIAT_MCP_TOKEN>` (задайте токен в переменной окружения). Локальный Codex CLI можно настроить (синтаксис зависит от версии):

```bash
codex mcp add akiat --url http://127.0.0.1:8080/mcp --bearer-token-env-var AKIAT_MCP_TOKEN
```

При публикации в интернете обязателен HTTPS, длинный токен, доступ только доверенным агентам; не открывать MCP всем пользователям. MCP — это **управляющий интерфейс**, а не способ обхода платных API LLM или хостинга аудио. Сам Java-сервер умеет работать по расписанию, поэтому MCP-клиент не должен постоянно оставаться запущенным.

## Этапы, которые ещё не выполнены

* Встроенная веб-редакторская панель (пока REST и MCP), автоматическая проверка качества татарского носителем, тест с детьми, version history.
* Полноценный crawler с правовой проверкой доступности **полных современных сказок**, классификация эмоционального спектра на основе действительно прочитанных источников; поиск по сниппетам — только кандидаты.
* PostgreSQL + Flyway, S3/MinIO object storage, автоматический резервный копии, мониторинг, восстановление заброшенных worker jobs, масштабирование на несколько серверов.
* Проверка end-to-end реальным языковым LLM/TTS ключом, фактическая модерация RSS Яндексом и голосовой поиск Алисы.

## Состояние проверки

`mvn -f server/pom.xml test` проверяется в CI GitHub Actions. Не путать успешную Maven-компиляцию с качеством татарских текстов или озвучки — они требуют отдельных тестов.
