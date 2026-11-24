package com.urbancompany.platform.notification;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notifications;

    @GetMapping public ApiResponse<List<Map<String, Object>>> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        return ApiResponse.ok(notifications.list(SecurityUtils.userId(), page, Math.min(size, 100)));
    }
    @PostMapping("/read-all") public ApiResponse<Map<String, Integer>> readAll() { return ApiResponse.ok(Map.of("updated", notifications.markAllRead(SecurityUtils.userId()))); }
}
