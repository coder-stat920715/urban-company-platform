package com.urbancompany.platform.notification;

import com.urbancompany.platform.auth.AuthPrincipal;
import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ChatController {
    private final ChatService chat;

    public record ChatIn(String content) {}

    /** STOMP: SEND /app/chat/{bookingId} {"content":"..."} */
    @MessageMapping("/chat/{bookingId}")
    public void ws(@DestinationVariable Long bookingId, ChatIn in, Principal principal) {
        if (principal instanceof Authentication a && a.getPrincipal() instanceof AuthPrincipal p) chat.send(p.userId(), p.role(), bookingId, in.content());
    }

    @RestController
    @RequestMapping("/api/v1/bookings/{bookingId}/chat")
    @RequiredArgsConstructor
    public static class Rest {
        private final ChatService chat;
        @GetMapping public ApiResponse<List<Map<String, Object>>> history(@PathVariable Long bookingId) { var p = SecurityUtils.principal(); return ApiResponse.ok(chat.history(p.userId(), p.role(), bookingId)); }
        @PostMapping public ApiResponse<Map<String, Object>> send(@PathVariable Long bookingId, @RequestBody ChatIn in) { var p = SecurityUtils.principal(); return ApiResponse.ok(chat.send(p.userId(), p.role(), bookingId, in.content())); }
    }
}
