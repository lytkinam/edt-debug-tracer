package plugin17.test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TracerSink — чистый сток конвейера разработки.
 *
 * Контракт:
 *   ВХОД:  { "project": "...", "mainClass": "...", "args": "...",
 *            "maxSteps": 0, "stepType": "into", "saveJson": true,
 *            "timeoutMs": 30000 }
 *   ВЫХОД: { "ok": true, "session_id": "...", "steps": [...],
 *            "totalSteps": N, "json_path": "..." }
 *
 * Поведение — строго сток:
 *   1. Получает запрос.
 *   2. Запускает debug-сессию.
 *   3. Включает запись трейса.
 *   4. Запускает auto-step до завершения или maxSteps.
 *   5. Останавливает запись, завершает сессию.
 *   6. Сохраняет JSON (если saveJson=true).
 *   7. Возвращает результат.
 *   8. Не вызывает ничего снаружи — полная остановка.
 */
public class TracerSink {

    private final TracerListener tracer;
    private final TracerStorage storage;
    private final String baseOutputDir;

    public TracerSink(TracerListener tracer, TracerStorage storage, String baseOutputDir) {
        this.tracer = tracer;
        this.storage = storage;
        this.baseOutputDir = baseOutputDir;
    }

    /**
     * Основной метод стока. Синхронный — возвращает результат только после
     * полного завершения трейса или таймаута.
     */
    public SinkResult run(SinkRequest req) {
        long startMs = System.currentTimeMillis();
        String sessionOutputPath = buildOutputPath(req);

        try {
            // --- 1. Запуск debug-сессии ---
            String launchResult = tracer.launchDebug(req.project, req.mainClass);
            if (launchResult.contains("error")) {
                return SinkResult.error("launch failed: " + launchResult);
            }

            // --- 2. Ждём suspension (готовности к шагу) ---
            if (!waitForSuspended(req.timeoutMs / 2)) {
                tracer.debugTerminate();
                return SinkResult.error("timeout waiting for initial suspension");
            }

            // --- 3. Начало записи ---
            tracer.setOutputPath(sessionOutputPath);
            tracer.startRecording();

            // --- 4. Auto-step до конца ---
            tracer.startAutoStep(req.maxSteps);

            // --- 5. Ждём завершения программы ---
            if (!waitForTerminated(req.timeoutMs)) {
                // Таймаут — принудительно останавливаем
                tracer.debugTerminate();
            }

            // --- 6. Остановить запись, собрать результат ---
            List<StepEntry> entries = tracer.stopRecording();
            String sessionId = null;
            if (storage != null && storage.isOpen()) {
                sessionId = storage.getLastSessionId();
            }

            // --- 7. Сохранить JSON ---
            if (req.saveJson) {
                writeJson(entries, sessionOutputPath);
            }

            long durationMs = System.currentTimeMillis() - startMs;

            // --- 8. Вернуть результат, ничего больше не вызывать ---
            return new SinkResult(
                true,
                null,
                sessionId,
                entries,
                req.saveJson ? sessionOutputPath : null,
                durationMs
            );

        } catch (Exception e) {
            try { tracer.stopRecording(); } catch (Exception ignored) {}
            try { tracer.debugTerminate(); } catch (Exception ignored) {}
            return SinkResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Вспомогательные методы
    // -------------------------------------------------------------------------

    private boolean waitForSuspended(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = tracer.getDebugStatus();
            if (status.contains("\"state\":\"suspended\"")) return true;
            Thread.sleep(100);
        }
        return false;
    }

    private boolean waitForTerminated(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String status = tracer.getDebugStatus();
            if (status.contains("\"state\":\"terminated\"") ||
                status.contains("\"state\":\"none\"")) return true;
            if (!tracer.isAutoStepping() && !tracer.isRecording()) return true;
            Thread.sleep(200);
        }
        return false;
    }

    private String buildOutputPath(SinkRequest req) {
        String ts = String.valueOf(System.currentTimeMillis());
        String name = req.project != null && !req.project.isEmpty()
            ? req.project.replaceAll("[^a-zA-Z0-9_-]", "_")
            : "sink";
        return baseOutputDir + File.separator + "sink_" + name + "_" + ts + ".json";
    }

    private void writeJson(List<StepEntry> entries, String path) throws IOException {
        File f = new File(path);
        f.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(f, StandardCharsets.UTF_8)) {
            fw.write(StepEntry.entriesToJson(entries));
        }
    }

    // -------------------------------------------------------------------------
    // Вложенные value-классы запроса и ответа
    // -------------------------------------------------------------------------

    public static class SinkRequest {
        public String project   = "";
        public String mainClass = "";
        public String args      = "";
        public int    maxSteps  = 0;        // 0 = безлимит
        public String stepType  = "into";
        public boolean saveJson = true;
        public long timeoutMs   = 30_000L;

        /** Разбор из JSON-строки (без внешних зависимостей). */
        public static SinkRequest fromJson(String json) {
            SinkRequest r = new SinkRequest();
            r.project   = jsonStr(json, "project",   r.project);
            r.mainClass = jsonStr(json, "mainClass", r.mainClass);
            r.args      = jsonStr(json, "args",      r.args);
            r.stepType  = jsonStr(json, "stepType",  r.stepType);
            r.maxSteps  = jsonInt(json, "maxSteps",  r.maxSteps);
            r.timeoutMs = jsonLong(json, "timeoutMs", r.timeoutMs);
            r.saveJson  = jsonBool(json, "saveJson",  r.saveJson);
            return r;
        }

        private static String jsonStr(String j, String k, String def) {
            int i = j.indexOf('"' + k + '"');
            if (i < 0) return def;
            int c = j.indexOf(':', i + k.length() + 2);
            int q1 = j.indexOf('"', c + 1);
            int q2 = j.indexOf('"', q1 + 1);
            return (q1 >= 0 && q2 > q1) ? j.substring(q1 + 1, q2) : def;
        }

        private static int jsonInt(String j, String k, int def) {
            int i = j.indexOf('"' + k + '"');
            if (i < 0) return def;
            int c = j.indexOf(':', i + k.length() + 2);
            int s = c + 1;
            while (s < j.length() && !Character.isDigit(j.charAt(s))) s++;
            int e = s;
            while (e < j.length() && Character.isDigit(j.charAt(e))) e++;
            return (e > s) ? Integer.parseInt(j.substring(s, e)) : def;
        }

        private static long jsonLong(String j, String k, long def) {
            int i = j.indexOf('"' + k + '"');
            if (i < 0) return def;
            int c = j.indexOf(':', i + k.length() + 2);
            int s = c + 1;
            while (s < j.length() && !Character.isDigit(j.charAt(s))) s++;
            int e = s;
            while (e < j.length() && Character.isDigit(j.charAt(e))) e++;
            return (e > s) ? Long.parseLong(j.substring(s, e)) : def;
        }

        private static boolean jsonBool(String j, String k, boolean def) {
            int i = j.indexOf('"' + k + '"');
            if (i < 0) return def;
            int c = j.indexOf(':', i + k.length() + 2);
            int s = c + 1;
            while (s < j.length() && j.charAt(s) == ' ') s++;
            if (j.startsWith("true", s))  return true;
            if (j.startsWith("false", s)) return false;
            return def;
        }
    }

    public static class SinkResult {
        public final boolean ok;
        public final String  error;
        public final String  sessionId;
        public final List<StepEntry> steps;
        public final String  jsonPath;
        public final long    durationMs;

        public SinkResult(boolean ok, String error, String sessionId,
                          List<StepEntry> steps, String jsonPath, long durationMs) {
            this.ok         = ok;
            this.error      = error;
            this.sessionId  = sessionId;
            this.steps      = steps;
            this.jsonPath   = jsonPath;
            this.durationMs = durationMs;
        }

        public static SinkResult error(String msg) {
            return new SinkResult(false, msg, null, List.of(), null, 0);
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":").append(ok);
            if (error != null) {
                sb.append(",\"error\":\"").append(esc(error)).append("\"");
            }
            if (sessionId != null) {
                sb.append(",\"session_id\":\"").append(esc(sessionId)).append("\"");
            }
            sb.append(",\"totalSteps\":").append(steps != null ? steps.size() : 0);
            sb.append(",\"durationMs\":").append(durationMs);
            if (jsonPath != null) {
                sb.append(",\"json_path\":\"").append(esc(jsonPath)).append("\"");
            }
            // Инлайн шаги
            sb.append(",\"steps\":");
            if (steps != null && !steps.isEmpty()) {
                sb.append(StepEntry.entriesToJson(steps));
            } else {
                sb.append("[]");
            }
            sb.append("}");
            return sb.toString();
        }

        private static String esc(String s) {
            return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
