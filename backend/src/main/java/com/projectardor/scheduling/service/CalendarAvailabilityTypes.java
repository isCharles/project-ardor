package com.projectardor.scheduling.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Server-computed calendar availability. A calendar entry stores one instant, so occupied blocks
 * are estimated with an assumed length that callers must state instead of guessing silently. */
public final class CalendarAvailabilityTypes {
    private CalendarAvailabilityTypes() {}

    public record BusyItem(UUID taskId, String title, Instant dueAt, UUID seriesId, boolean fixedSeries) {
    }

    public record Slot(Instant start, Instant end) {
    }

    public record Window(Instant from, Instant to, int slotMinutes, int assumedEventMinutes,
            List<Slot> freeSlots, List<BusyItem> busy) {
    }
}
