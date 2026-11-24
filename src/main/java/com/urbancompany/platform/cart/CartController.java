package com.urbancompany.platform.cart;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.cart.CartModels.CartView;
import com.urbancompany.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cart;
    private final CartQuoteService quotes;

    public record AddItemRequest(@NotNull Long packageId, @Min(1) int quantity, List<String> optionKeys) {}

    @GetMapping public ApiResponse<CartView> view() { return ApiResponse.ok(cart.view(SecurityUtils.userId())); }

    @PostMapping("/items")
    public ApiResponse<CartView> add(@Valid @RequestBody AddItemRequest r) { return ApiResponse.ok(cart.add(SecurityUtils.userId(), r.packageId(), r.quantity(), r.optionKeys())); }

    @PutMapping("/items/{lineId}")
    public ApiResponse<CartView> update(@PathVariable String lineId, @RequestParam int quantity) { return ApiResponse.ok(cart.updateQuantity(SecurityUtils.userId(), lineId, quantity)); }

    @DeleteMapping("/items/{lineId}")
    public ApiResponse<CartView> remove(@PathVariable String lineId) { return ApiResponse.ok(cart.remove(SecurityUtils.userId(), lineId)); }

    @DeleteMapping public ApiResponse<Void> clear() { cart.clear(SecurityUtils.userId()); return ApiResponse.ok(null); }

    @PostMapping("/quote")
    public ApiResponse<Map<String, Object>> quote(@Valid @RequestBody CartQuoteService.QuoteRequest r) { return ApiResponse.ok(quotes.quote(SecurityUtils.userId(), r)); }
}
