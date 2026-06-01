# Чеклист прямого импорта в Eclipse PDE

## Окружение
- [ ] Eclipse IDE for RCP Developers установлен
- [ ] JDK 17 настроен как Installed JRE в Eclipse
- [ ] Target Platform указывает на `plugins/` каталога 1C:EDT

## Импорт проекта
- [ ] `File → Import → Existing Projects into Workspace`
- [ ] Выбрана папка `plugin/` из этого репозитория
- [ ] Проект распознан как **Plug-in Project** (иконка с пазлом)
- [ ] Нет ошибок компиляции в Package Explorer

## Зависимости (MANIFEST.MF)
- [ ] `com._1c.g5.v8.dt.debug.core` разрешается из Target Platform
- [ ] `org.eclipse.debug.core` разрешается
- [ ] `org.eclipse.core.runtime` разрешается

## Запуск
- [ ] Создана конфигурация `Eclipse Application` (Run → Run Configurations)
- [ ] Второй экземпляр Eclipse запускается без ошибок
- [ ] `curl http://localhost:18080/mcp/health` возвращает `{"status":"ok"}`

## Тесты
- [ ] `cd tests && pip install -r requirements.txt`
- [ ] `python test_mcp_api.py` — все тесты проходят
