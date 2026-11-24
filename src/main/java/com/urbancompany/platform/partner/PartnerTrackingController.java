package com.urbancompany.platform.partner;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/** STOMP: client SENDs to /app/partner/heartbeat every ~5 seconds. */
@Controller
@RequiredArgsConstructor
public class PartnerTrackingController {
    private final PartnerTrackingService tracking;

    @MessageMapping("/partner/heartbeat")
    public void heartbeat(PartnerTrackingService.Heartbeat hb, Principal principal) {
        if (principal == null) return;
        tracking.heartbeat(Long.valueOf(principal.getName()), hb);
    }
}
