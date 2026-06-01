# 10. Чеклист: подключение sqlite-jdbc.jar

## Где взять jar

Актуальная версия: **3.53.1.0**

Скачать напрямую:
```
https://github.com/xerial/sqlite-jdbc/releases/download/3.53.1.0/sqlite-jdbc-3.53.1.0.jar
```

Maven Central:
```xml
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.53.1.0</version>
</dependency>
```

## Куда положить

```
plugin/
  lib/
    sqlite-jdbc.jar   <-- переименовать при копировании

plugin.tests/
  lib/
    sqlite-jdbc.jar   <-- дублировать, нужен для тест-проекта
```

## Проверка в PDE

1. Открыть `plugin/META-INF/MANIFEST.MF` в редакторе плагина.
2. Вкладка **Runtime → Classpath**: должен отображаться `lib/sqlite-jdbc.jar`.
3. Вкладка **Build**: `lib/sqlite-jdbc.jar` должен быть в `bin.includes`.
4. Пересобрать проект `Project → Clean`.
5. В консоли `Eclipse Application` не должно быть `ClassNotFoundException: org.sqlite.JDBC`.

## Альтернатива: обернуть как OSGi bundle

Если хочется не копировать jar вручную — сделать обёртку:

1. `File → New → Plug-in from existing JAR archives`.
2. Выбрать скачанный `sqlite-jdbc-3.53.1.0.jar`.
3. Bundle-SymbolicName: `org.xerial.sqlite-jdbc`.
4. Добавить зависимость в `MANIFEST.MF` основного плагина:
   `Require-Bundle: org.xerial.sqlite-jdbc`.
