package com.urbancompany.platform.dispatch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1-second heartbeat: expires 30s offers (-> escalation), retries searches, starts due scheduled bookings, pushes countdown ticks. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DispatchScheduler {
    private final DispatchService dispatch;

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        try { dispatch.tick(); } catch (Exception e) { log.warn("dispatch tick failed: {}", e.toString()); }
    }
}
