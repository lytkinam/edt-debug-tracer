package plugin17.test;

import com.sun.net.httpserver.HttpExchange;
import org.eclipse.debug.core.DebugPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TraceSinkHandler — чистый сток для AI-агентов.
 *
 * Контракт:
 *   Вход  : POST /sink/run { project, mainClass, breakpoints[], steps, save_json, timeout_sec }
 *   Выход : JSON { ok, session_id, steps_count, trace[], saved_to? }
 *   Побочных эффектов нет — сессия открывается и закрывается внутри.
 *
 * Сток не вызывает ничего снаружи после возврата ответа.
 */
public class TraceSinkHandler {

    private final TracerListener tracer;
    private final TracerStorage  storage;   // может быть null
    private final String         workDir;   // путь для сохранения JSON

    public TraceSinkHandler(TracerListener tracer, TracerStorage storage, String workDir) {
        this.tracer  = tracer;
        this.storage = storage;
        this.workDir = workDir;
    }

    // ─── единственный публичный метод ────────────────────────────────────────

    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"POST required\"}");
            return;
        }

        String body;
        try {
            body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            respond(ex, 400, "{\"error\":\"cannot read body\"}" );
            return;
        }

        // ── 1. Разбор входных параметров ─────────────────────────────────────
        SinkRequest req = SinkRequest.parse(body);
        if (req.project.isEmpty() || req.mainClass.isEmpty()) {
            respond(ex, 400, "{\"error\":\"project and mainClass are required\"}");
            return;
        }

        // ── 2. Установка breakpoints до запуска ──────────────────────────────
        TracerBreakpoints bp = new TracerBreakpoints();
        int bpCount = 0;
        for (SinkRequest.Breakpoint b : req.breakpoints) {
            try {
                bp.addLineBreakpoint(b.typeName, b.lineNumber);
                bpCount++;
            } catch (Exception e) {
                // пропускаем неустановленные точки, фиксируем в лог
                System.err.println("[sink] bp skip " + b.typeName + ":" + b.lineNumber + " — " + e.getMessage());
            }
        }

        // ── 3. Запуск debug-сессии ────────────────────────────────────────────
        String launchResult;
        try {
            launchResult = tracer.launchDebug(req.project, req.mainClass);
        } catch (Exception e) {
            bp.clearAll();
            respond(ex, 500, "{\"error\":\"launch failed: " + esc(e.getMessage()) + "\"}");
            return;
        }
        if (launchResult.contains("error")) {
            bp.clearAll();
            respond(ex, 500, launchResult);
            return;
        }

        // ── 4. Запись трейса ─────────────────────────────────────────────────
        tracer.startRecording();
        tracer.startAutoStep(req.steps);   // 0 = безлимит

        // Ждём завершения auto-step или таймаута
        boolean finished = waitForCompletion(req.timeoutSec);

        List<StepEntry> entries = tracer.stopRecording();

        // ── 5. Завершение debug-сессии ────────────────────────────────────────
        try { tracer.debugTerminate(); } catch (Exception ignored) {}
        bp.clearAll();

        // ── 6. Формирование ответа ────────────────────────────────────────────
        String sessionId = (storage != null) ? storage.getLastSessionId() : null;

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"ok\":true");
        json.append(",\"finished\":").append(finished);
        json.append(",\"steps_count\":").append(entries.size());
        json.append(",\"breakpoints_set\":").append(bpCount);
        if (sessionId != null) json.append(",\"session_id\":\"").append(esc(sessionId)).append("\"");

        // Полный трейс со всеми полями
        json.append(",\"trace\":").append(entriesToJson(entries));

        // ── 7. Опциональное сохранение в файл ────────────────────────────────
        if (req.saveJson) {
            String filePath = saveToFile(entries, req.project, req.mainClass);
            json.append(",\"saved_to\":\"").append(esc(filePath)).append("\"");
        }

        json.append("}");
        respond(ex, 200, json.toString());
        // ── СТОП. Больше ничего не вызываем. ─────────────────────────────────
    }

    // ─── вспомогательные методы ───────────────────────────────────────────────

    /** Ждёт окончания auto-step через polling статуса. */
    private boolean waitForCompletion(int timeoutSec) {
        long deadline = System.currentTimeMillis() + (long) timeoutSec * 1000;
        try {
            // Даём сессии время стартовать
            Thread.sleep(300);
            while (System.currentTimeMillis() < deadline) {
                if (!tracer.isAutoStepping() && !tracer.isRecording()) return true;
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false; // таймаут
    }

    /** Сериализует список StepEntry в JSON-массив с полными полями. */
    private static String entriesToJson(List<StepEntry> entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(entryToJson(entries.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String entryToJson(StepEntry e) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"seq\":").append(e.seq);
        sb.append(",\"procedure\":\"").append(esc(e.procedure)).append("\"");
        sb.append(",\"line\":").append(e.line);
        sb.append(",\"module\":\"").append(esc(e.module)).append("\"");
        sb.append(",\"thread_name\":\"").append(esc(e.threadName)).append("\"");
        sb.append(",\"thread_id\":").append(e.threadId);
        sb.append(",\"ts\":").append(e.ts);
        sb.append(",\"char_start\":").append(e.charStart);
        sb.append(",\"char_end\":").append(e.charEnd);
        sb.append(",\"stack_depth\":").append(e.stackDepth);
        sb.append(",\"parent_seq\":").append(e.parentSeq);
        // stack[]
        sb.append(",\"stack\":");
        if (e.stack != null && !e.stack.isEmpty()) {
            sb.append("[");
            for (int i = 0; i < e.stack.size(); i++) {
                if (i > 0) sb.append(",");
                StepEntry.StackFrame f = e.stack.get(i);
                sb.append("{\"procedure\":\"").append(esc(f.procedure))
                  .append("\",\"line\":").append(f.line).append("}");
            }
            sb.append("]");
        } else {
            sb.append("[]");
        }
        // variables{}
        sb.append(",\"variables\":");
        if (e.variables != null && !e.variables.isEmpty()) {
            sb.append("{");
            boolean first = true;
            for (java.util.Map.Entry<String, StepEntry.VarInfo> v : e.variables.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(esc(v.getKey())).append("\"");
                sb.append(":{\"type\":\"").append(esc(v.getValue().type))
                  .append("\",\"value\":\"").append(esc(v.getValue().value)).append("\"}");
            }
            sb.append("}");
        } else {
            sb.append("{}");
        }
        sb.append("}");
        return sb.toString();
    }

    /** Сохраняет трейс в файл, возвращает путь. */
    private String saveToFile(List<StepEntry> entries, String project, String mainClass) {
        String name = "sink_trace_" + project + "_" + System.currentTimeMillis() + ".json";
        File dir = new File(workDir);
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileWriter fw = new FileWriter(f, StandardCharsets.UTF_8)) {
            fw.write("{");
            fw.write("\"project\":\"" + esc(project) + "\",");
            fw.write("\"mainClass\":\"" + esc(mainClass) + "\",");
            fw.write("\"ts\":\"" + java.time.Instant.now() + "\",");
            fw.write("\"steps\":");
            fw.write(entriesToJson(entries));
            fw.write("}");
        } catch (IOException e) {
            System.err.println("[sink] save error: " + e.getMessage());
            return "error: " + e.getMessage();
        }
        return f.getAbsolutePath();
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    // ─── Внутренний DTO запроса ───────────────────────────────────────────────

    static class SinkRequest {
        String project   = "";
        String mainClass = "";
        int    steps     = 0;       // 0 = безлимит
        int    timeoutSec = 60;     // таймаут ожидания завершения
        boolean saveJson = false;   // сохранять ли результат в файл
        List<Breakpoint> breakpoints = new ArrayList<>();

        static class Breakpoint {
            String typeName  = "";  // полное имя класса: com.example.MyClass
            int    lineNumber = 0;
        }

        /**
         * Минимальный JSON-парсер без зависимостей.
         * Ожидает формат:
         * {
         *   "project": "MyProject",
         *   "mainClass": "com.example.Main",
         *   "steps": 200,
         *   "timeout_sec": 30,
         *   "save_json": true,
         *   "breakpoints": [
         *     { "typeName": "com.example.MyClass", "lineNumber": 42 },
         *     ...
         *   ]
         * }
         */
        static SinkRequest parse(String json) {
            SinkRequest r = new SinkRequest();
            r.project    = extractStr(json, "project");
            r.mainClass  = extractStr(json, "mainClass");
            r.steps      = extractInt(json, "steps",       0);
            r.timeoutSec = extractInt(json, "timeout_sec", 60);
            r.saveJson   = extractBool(json, "save_json",  false);

            // разбираем массив breakpoints
            int arrStart = json.indexOf("\"breakpoints\"");
            if (arrStart >= 0) {
                int bra = json.indexOf('[', arrStart);
                int ket = json.lastIndexOf(']');
                if (bra >= 0 && ket > bra) {
                    String arr = json.substring(bra + 1, ket);
                    // делим по объектам { ... }
                    int pos = 0;
                    while (pos < arr.length()) {
                        int ob = arr.indexOf('{', pos);
                        if (ob < 0) break;
                        int cb = arr.indexOf('}', ob);
                        if (cb < 0) break;
                        String obj = arr.substring(ob + 1, cb);
                        Breakpoint bp = new Breakpoint();
                        bp.typeName   = extractStr(obj, "typeName");
                        bp.lineNumber = extractInt(obj, "lineNumber", 0);
                        if (!bp.typeName.isEmpty() && bp.lineNumber > 0) r.breakpoints.add(bp);
                        pos = cb + 1;
                    }
                }
            }
            return r;
        }

        private static String extractStr(String json, String key) {
            int idx = json.indexOf("\"" + key + "\"");
            if (idx < 0) return "";
            int colon = json.indexOf(':', idx + key.length() + 2);
            if (colon < 0) return "";
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) return "";
            int q2 = json.indexOf('"', q1 + 1);
            return q2 > q1 ? json.substring(q1 + 1, q2) : "";
        }

        private static int extractInt(String json, String key, int def) {
            int idx = json.indexOf("\"" + key + "\"");
            if (idx < 0) return def;
            int colon = json.indexOf(':', idx + key.length() + 2);
            if (colon < 0) return def;
            int start = colon + 1;
            while (start < json.length() && !Character.isDigit(json.charAt(start))) start++;
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            return end > start ? Integer.parseInt(json.substring(start, end)) : def;
        }

        private static boolean extractBool(String json, String key, boolean def) {
            int idx = json.indexOf("\"" + key + "\"");
            if (idx < 0) return def;
            int colon = json.indexOf(':', idx + key.length() + 2);
            if (colon < 0) return def;
            String tail = json.substring(colon + 1).trim();
            if (tail.startsWith("true"))  return true;
            if (tail.startsWith("false")) return false;
            return def;
        }
    }
}
