package com.autousbcopier;

import java.util.ArrayList;
import java.util.List;

final class LogEntryBuffer {
    static final int MAX_ENTRIES = 100;

    private LogEntryBuffer() {
    }

    static List<String> append(List<String> existing, String entry) {
        List<String> retained = new ArrayList<>();
        int firstRetained = Math.max(0, existing.size() - MAX_ENTRIES + 1);
        retained.addAll(existing.subList(firstRetained, existing.size()));
        retained.add(entry);
        return retained;
    }
}
