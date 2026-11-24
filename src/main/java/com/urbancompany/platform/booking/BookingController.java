package com.urbancompany.platform.booking;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.cart.CartModels.CartLine;
import com.urbancompany.platform.cart.CartService;
import com.urbancompany.platform.common.ApiResponse;
import com.urbancompany.platform.location.Address;
import com.urbancompany.platform.location.AddressService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {
    private final BookingService bookings;
    private final SlotService slots;
    private final CartService cart;
    private final AddressService addresses;

    public record CheckoutBody(@NotNull Long addressId, Long categoryId, java.time.Instant scheduledStart, boolean instant, String couponCode) {}
    public record ReasonBody(@NotBlank String reason) {}
    public record ExtraDecision(boolean approve) {}

    /** Slot engine: available start times for the cart's items of one category on a date. */
    @GetMapping("/slots")
    public ApiResponse<List<SlotService.Slot>> slots(@RequestParam Long addressId, @RequestParam(required = false) Long categoryId, @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date) {
        Long uid = SecurityUtils.userId();
        List<CartLine> lines = cart.linesForCheckout(uid, categoryId);
        Address a = addresses.owned(uid, addressId);
        return ApiResponse.ok(slots.slots(a.getZoneId(), lines.get(0).categoryId(), date, lines.stream().mapToInt(CartLine::durationMinutes).sum()));
    }

    @PostMapping("/checkout")
    public ApiResponse<Map<String, Object>> checkout(@Valid @RequestBody CheckoutBody b) {
        Booking created = bookings.checkout(SecurityUtils.userId(), new BookingService.CheckoutRequest(b.addressId(), b.categoryId(), b.scheduledStart(), b.instant(), b.couponCode()));
        return ApiResponse.ok("Booking created. Complete payment to confirm.", bookings.customerView(SecurityUtils.userId(), created.getId()));
    }

    @GetMapping public ApiResponse<List<Map<String, Object>>> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookings.customerBookings(SecurityUtils.userId(), page, Math.min(size, 100)));
    }

    @GetMapping("/{id}") public ApiResponse<Map<String, Object>> get(@PathVariable Long id) { return ApiResponse.ok(bookings.customerView(SecurityUtils.userId(), id)); }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id, @Valid @RequestBody ReasonBody r) {
        bookings.cancelByCustomer(SecurityUtils.userId(), id, r.reason());
        return ApiResponse.ok(bookings.customerView(SecurityUtils.userId(), id));
    }

    @PostMapping("/{id}/extra-work/{requestId}")
    public ApiResponse<ExtraWorkRequest> resolveExtra(@PathVariable Long id, @PathVariable Long requestId, @RequestBody ExtraDecision d) {
        return ApiResponse.ok(bookings.resolveExtraWork(SecurityUtils.userId(), id, requestId, d.approve()));
    }

    @PostMapping("/{id}/dispute")
    public ApiResponse<Map<String, Object>> dispute(@PathVariable Long id, @Valid @RequestBody ReasonBody r) {
        bookings.dispute(SecurityUtils.userId(), id, r.reason());
        return ApiResponse.ok(bookings.customerView(SecurityUtils.userId(), id));
    }
}
