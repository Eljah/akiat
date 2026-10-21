# Локальный запуск на CPU

## Автоматическое экспериментальное продолжение

`training/start_night_pipeline.ps1` запускает отдельный процесс ожидания.
Настройки: `training/night_pipeline.json`. Статус: `outputs/night_pipeline/status.json`.
Процесс ждёт `asr/cpu_status.json: needs_review`, затем проверяет SHA256 всех MP3,
непрерывность частей и достижение конца каждого файла. Неполные данные не запускают обучение.

После завершения ASR автоматически выполняются:
1. Нарезка коротких WAV (до 12 секунд, по возможности на паузах) и повторное распознавание
   каждого клипа. Старые длинные/пустые временные метки не используются как точное выравнивание.
2. Piper preprocess с татарским фонемизатором и проверкой соответствия словаря исходной модели.
3. Дообучение на CPU в WSL Ubuntu, затем экспорт ONNX.

Корпус явно обозначен как непроверенный. Подкаст по умолчанию исключён из обучения:
говорящие в нём автоматически не разделяются. Расшифровываются по-прежнему все MP3.
Проверки разметки человеком не ожидаются — экспериментальный режим разрешён пользователем.
При недостатке подходящих клипов или ошибке этапа сохраняется `last_error.txt`; повторный
запуск продолжает подготовленные клипы и сохранённую контрольную точку обучения.

До обучения требуются успешно выполненные `piper_stage.py verify` и `smoke_train`:
они проверяют загрузку официального `.ckpt`, фонемный словарь, синтез и один шаг обучения.
Piper установлен отдельно в `.tools/piper-venv` (Linux/WSL); Windows-среда ASR не заменяется.
Piper ограничен одним логическим CPU, одним потоком, пониженным приоритетом и 8 ГиБ
виртуальной памяти. При остатке менее 5 ГиБ на D: дальнейшая работа останавливается.
Сохраняются последняя и одна периодическая контрольная точка, а не все 40 эпох.

Запуск ожидания: `powershell -ExecutionPolicy Bypass -File training/start_night_pipeline.ps1`.
Уже работающий экземпляр защищён от дублирования mutex-ом. Автозапуск после перезагрузки
для этого процесса не установлен: скрипт нужно запустить снова после перезапуска Windows.

Среда Python 3.10 находится в `.venv`. CUDA не требуется.

Из корня репозитория:

```powershell
# Безопасный фоновый запуск: короткая проверка, затем все MP3 (модель уже скачана)
powershell -ExecutionPolicy Bypass -File training/start_cpu_background.ps1
# Короткая проверка на 12 секундах самого маленького MP3
powershell -ExecutionPolicy Bypass -File training/start_cpu_asr.ps1 -Smoke
# Распознавание всех MP3 из корня, продолжение после перезапуска
powershell -ExecutionPolicy Bypass -File training/start_cpu_asr.ps1
```

Распознавание использует татарскую модель из исходного блокнота,
CPU, float32, два потока и batch size 1. Перед импортом PyTorch Windows Job Object
ограничивает процесс и его дочерние процессы: не более 20% суммарного CPU
и 6 ГиБ выделенной памяти. Приоритет — BelowNormal; межоперационный
параллелизм PyTorch — 1. Если ограничения не удалось включить, запуск прерывается.
Именованный mutex блокирует второй процесс распознавания этого репозитория.
Результаты сохраняются
после каждой части длительностью до трёх минут в `asr/cpu/`.
Тестовые результаты лежат отдельно в `asr/cpu_smoke/`.
CSV с суффиксом `_draft` — черновики: исправьте текст и границы,
исключите музыку и других говорящих перед сборкой обучающего корпуса.
Исходные MP3 не изменяются.

`training/run_cpu_pipeline.ps1` последовательно выполняет короткую проверку
и полное распознавание. Текущий этап записывается в `asr/cpu_status.json`.
При фоновом запуске журналы находятся в `asr/cpu_pipeline.log` и
`asr/cpu_pipeline.err.log`; PID — в `asr/cpu_pipeline.pid`.
Не запускайте второй экземпляр одновременно с работающим.

Новый `start_cpu_background.ps1` использует уже скачанную модель и не запускает
повторное скачивание весов. Пути к журналам `cpu_safe.*.log` и PID оболочки
записаны в `asr/cpu_safe_run.json`. Фоновые окна не открываются.
Для остановки именно этого запуска вместе с дочерними процессами:

```powershell
$run = Get-Content asr/cpu_safe_run.json -Raw | ConvertFrom-Json
$worker = Get-CimInstance Win32_Process -Filter "ProcessId=$($run.pid)"
if ($worker -and $worker.CommandLine -match 'start_cpu_background.ps1.*-Worker') {
    taskkill /PID $run.pid /T /F
}
```

После остановки сохранённые части остаются; следующий запуск продолжает обработку.
Предел памяти относится к выделенной памяти, а не только к RAM.
Лимиты снижают нагрузку, но не гарантируют защиту от аппаратных и драйверных сбоев.
Описание механизма Windows: [CPU](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_cpu_rate_control_information),
[память](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information).

Старый `run_cpu_pipeline.ps1` перед распознаванием скачивает веса частями по 4 МиБ
с четырьмя соединениями, повторными попытками и проверкой SHA256.
Это обход задержек единого большого запроса через локальный прокси.
`asr/cpu_status.json` показывает полученные байты, размер файла и процент.
Повторный запуск использует уже скачанные части из `.cache/model_parts/`.
После загрузки автоматически выполняются тест и полное распознавание.

Для дообучения Piper в блокноте 03 выбран `accelerator=cpu`,
`precision=32`, `batch-size=1`, `num-workers=0`.
Сам блокнот сохраняет установку для Linux/Colab; это не установщик Windows.
Повторная проверка локального Piper и контрольной точки (синтез и один шаг обучения):

```powershell
powershell -ExecutionPolicy Bypass -File training/verify_piper.ps1
```

Исходная контрольная точка: `checkpoints/tatartts_male.ckpt`, мужской голос
[IS2AI/TatarTTS](https://github.com/IS2AI/TatarTTS). Исходный конфиг расположен рядом.
Пробный звук: `outputs/night_pipeline/pretrained_smoke.wav`.
Результаты проверок: `piper_verified.json` и `training_smoke_passed.json` в той же папке.
Для воспроизведения установки в Ubuntu WSL: `bash training/setup_piper_wsl.sh`
из `/mnt/d/Java/akiat` (нужны build-essential, python3-dev, python3-venv и доступ в сеть).

Среда распознавания воспроизводится командой:

```powershell
.tools/uv.exe pip install --python .venv/Scripts/python.exe `
  -r training/requirements-cpu.txt `
  --index https://download.pytorch.org/whl/cpu --index-strategy unsafe-best-match
```
