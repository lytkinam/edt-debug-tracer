# docs/CHANGELOG — История изменений контрактов

Формат: `[Версия] ГГГГ-ММ-ДД — Описание — Автор`

---

## [1.1] 2026-06-06

### Добавлено
- `docs/README.md` v1.1: версионирование контрактов, стандарт PR, CHANGELOG
- `docs/MCP-HTTP/BRD-get_capabilities.md` + `SRS-get_capabilities.md`: новый метод-манифест возможностей HTTP-стока (запрос от оркестратора)
- `docs/MCP-HTTP/BRD-poll_break_status.md` + `SRS-poll_break_status.md`: замена блокирующего `wait_for_break` на polling (запрос от оркестратора)
- `session_id` добавлен в `set_breakpoint`, `get_call_stack`, `get_variables`, `suspend` как опциональный фильтр (согласовано с пользователем 2026-06-06, интерактивный режим)
- Принцип «Потребители контракта» добавлен в регламент как обязательное поле BRD/SRS
- Принцип «Оркестратор не ждёт исполнителя» зафиксирован в `docs/cli-sink/README.md`

### Изменено
- `docs/MCP-HTTP/README.md`: `wait_for_break` заменён на `poll_break_status` в таблице методов оркестратора
- `docs/cli-sink/README.md`: схема оркестрации `breakpoint-metrics` обновлена под polling

### Требует реализации HTTP-стоком
- `get_capabilities` — ⛔ отсутствует
- `poll_break_status` — ⛔ отсутствует  
- `set_breakpoint` — ⛔ отсутствует

---

## [1.0] 2026-06-06

### Добавлено
- Начальная структура `docs/cli-sink/` и `docs/MCP-HTTP/`
- BRD + SRS для методов: `resume`, `wait_for_break`, `get_call_stack`, `get_variables`, `suspend`, `set_breakpoint`
- BRD + SRS для CLI-методов: `trace-session`, `breakpoint-metrics`
- `docs/README.md` v1.0: принципы регламента контрактов
