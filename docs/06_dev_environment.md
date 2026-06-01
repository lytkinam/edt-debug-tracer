# 06. Настройка окружения разработки

## 1. Eclipse IDE for RCP Developers

Скачать: https://www.eclipse.org/downloads/packages/release/2024-12/r/eclipse-ide-rcp-and-rap-developers

Версия: 2024-12 или новее. Требует JDK 17+.

## 2. JDK

- **Для разработки плагина:** JDK 17 или 21 (Eclipse PDE)
- **Для 1C:EDT 2026.1:** EDT поставляется со встроенной Java — отдельно устанавливать не нужно

Рекомендуется: Eclipse Temurin 17 LTS https://adoptium.net/

## 3. Target Platform (обязательно!)

1. `Window → Preferences → Plug-in Development → Target Platform`
2. `Add... → Nothing → Next`
3. `Add... → Directory`
4. Указать путь к `<1C_EDT_install>/plugins/`
5. Дать имя `1C EDT 2026.1`
6. Установить как Active Target Platform
7. Дождаться разрешения зависимостей

**Проверка:** в `MANIFEST.MF` зависимость `com._1c.g5.v8.dt.debug.core` должна разрешаться (не подчёркнута красным).

## 4. Импорт проекта плагина

```
File → Import → General → Existing Projects into Workspace
→ Browse → [выбрать папку plugin/ этого репозитория]
→ Finish
```

Проект должен отобразиться с иконкой **пазла** (Plug-in Project).

## 5. Запуск (Eclipse Application)

1. `Run → Run Configurations → Eclipse Application → New`
2. Вкладка `Plug-ins` → `Launch with: plug-ins selected below`
3. Включить `com.tracer.edt.debugtracer`
4. Вкладка `Arguments` → VM arguments:
   ```
   -Dedt.tracer.port=18080
   ```
5. `Apply → Run`

## 6. Проверка работы

```bash
curl http://localhost:18080/mcp/health
# Ожидается: {"status":"ok","version":"1.0.0"}

curl -X POST http://localhost:18080/mcp/start
# Ожидается: {"started":true,"session_id":null}

curl -X POST http://localhost:18080/mcp/stop
# Ожидается: {"stopped":true,"entries":[]}
```

## 7. Структура каталогов после установки

```
~/workspace/
  edt-debug-tracer/       ← этот репозиторий
    plugin/               ← Eclipse PDE проект (импортировать)
    docs/
    tests/
```

## 8. Частые проблемы

| Проблема | Решение |
|----------|---------|
| `com._1c.g5.v8.dt.debug.core` не найден | Проверить Target Platform |
| Порт 18080 занят | Добавить `-Dedt.tracer.port=18090` |
| SUSPEND события не приходят | Убедиться, что EDT подключён к debug target |
| `ClassCastException` на `event.getSource()` | Проверить тип события — не все SUSPEND идут от IThread |
