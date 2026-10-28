package com.autousbcopier;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LogEntryBufferTest {
    @Test
    public void appendingKeepsNewestOneHundredEntries() {
        List<String> existing = entries(100);

        List<String> result = LogEntryBuffer.append(existing, "entry-100");

        assertEquals(100, result.size());
        assertEquals("entry-1", result.get(0));
        assertEquals("entry-100", result.get(99));
        assertEquals(100, existing.size());
    }

    @Test
    public void appendingToAnOversizedLogKeepsNewestEntriesInOrder() {
        List<String> result = LogEntryBuffer.append(entries(105), "entry-105");

        assertEquals(100, result.size());
        assertEquals("entry-6", result.get(0));
        assertEquals("entry-105", result.get(99));
    }

    @Test
    public void appendingToAnEmptyLogCreatesOneEntry() {
        List<String> result = LogEntryBuffer.append(new ArrayList<String>(), "first");

        assertEquals(1, result.size());
        assertTrue(result.contains("first"));
        assertFalse(result.contains(null));
    }

    private List<String> entries(int count) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add("entry-" + i);
        }
        return entries;
    }
}
