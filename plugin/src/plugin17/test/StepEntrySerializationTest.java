package plugin17.test;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * Tests StepEntry serialization with all enriched fields (1.1-1.5).
 */
public class StepEntrySerializationTest {

    @Test
    public void testFullStepEntryToJson() {
        StepEntry entry = new StepEntry(
            "МодульСеанса.УстановкаПараметров",  // procedure
            47,                                     // line
            "CommonModules/МодульСеанса/Module.bsl",// module (1.1)
            "Сервер [admin]",                       // threadName (1.3)
            646430608,                              // threadId
            1780382800594L,                         // timestamp
            1234,                                   // charStart (1.4)
            1289,                                   // charEnd (1.4)
            3,                                      // stackDepth (1.5)
            18,                                     // parentSeq (1.5)
            "[{\"procedure\":\"МодульСеанса:47\",\"line\":47}]", // stackJson (1.5)
            "{\"ИменаПараметров\":{\"type\":\"Массив\",\"value\":\"[3]\"}}" // variablesJson (1.2)
        );

        String json = entry.toJson();
        System.out.println("Full StepEntry JSON: " + json);

        // Verify all fields present
        assertTrue("procedure", json.contains("\"procedure\":\"МодульСеанса.УстановкаПараметров\""));
        assertTrue("line", json.contains("\"line\":47"));
        assertTrue("module (1.1)", json.contains("\"module\":\"CommonModules/МодульСеанса/Module.bsl\""));
        assertTrue("thread_name (1.3)", json.contains("\"thread_name\":\"Сервер [admin]\""));
        assertTrue("thread_id", json.contains("\"thread_id\":646430608"));
        assertTrue("ts", json.contains("\"ts\":1780382800594"));
        assertTrue("char_start (1.4)", json.contains("\"char_start\":1234"));
        assertTrue("char_end (1.4)", json.contains("\"char_end\":1289"));
        assertTrue("stack_depth (1.5)", json.contains("\"stack_depth\":3"));
        assertTrue("parent_seq (1.5)", json.contains("\"parent_seq\":18"));
        assertTrue("stack (1.5)", json.contains("\"stack\":["));
        assertTrue("variables (1.2)", json.contains("\"variables\":{"));
    }

    @Test
    public void testNullOptionalFields() {
        StepEntry entry = new StepEntry(
            "TestProc", 1, "test.bsl",
            "main", 123, 1000L,
            -1, -1,       // no char position
            1, -1, null,  // no stack
            null           // no variables
        );

        String json = entry.toJson();
        System.out.println("Minimal StepEntry JSON: " + json);

        assertTrue("procedure", json.contains("\"procedure\":\"TestProc\""));
        assertTrue("char_start=-1", json.contains("\"char_start\":-1"));
        assertTrue("stack_depth=1", json.contains("\"stack_depth\":1"));
        assertTrue("parent_seq=-1", json.contains("\"parent_seq\":-1"));
        assertFalse("no stack key", json.contains("\"stack\":"));
        assertFalse("no variables key", json.contains("\"variables\":"));
    }

    @Test
    public void testEscaping() {
        StepEntry entry = new StepEntry(
            "proc with \"quotes\" and \\backslash",
            1, "module\"path",
            "thread\"name", 0, 0L,
            0, 0, 0, -1, null, null
        );

        String json = entry.toJson();
        assertTrue("escapes quotes", json.contains("\\\"quotes\\\""));
        assertTrue("escapes backslash", json.contains("\\\\backslash"));
    }
}
