package com.projectardor.calendar.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps repeating arrangements from running out. Each pass advances the
 * occurrences of a few series up to the horizon; a weekly meeting created
 * today still has rows on the calendar next quarter.
 */
@Component
public class TaskSeriesScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskSeriesScheduler.class);
    private static final int SERIES_PER_PASS = 25;

    private final TaskSeriesService taskSeriesService;

    public TaskSeriesScheduler(TaskSeriesService taskSeriesService) {
        this.taskSeriesService = taskSeriesService;
    }

    @Scheduled(
            initialDelayString = "${app.calendar.series-extend-initial-delay:20s}",
            fixedDelayString = "${app.calendar.series-extend-interval:1h}")
    public void extendSeries() {
        try {
            int created = taskSeriesService.extendActiveSeries(SERIES_PER_PASS);
            if (created > 0) log.info("Materialized {} recurring calendar occurrences", created);
        } catch (RuntimeException exception) {
            // One malformed series must not stop the pass from running again later.
            log.warn("Recurring calendar pass deferred: cause={}", exception.getClass().getSimpleName());
        }
    }
}
