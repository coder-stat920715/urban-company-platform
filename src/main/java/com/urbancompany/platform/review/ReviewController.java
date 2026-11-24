package com.urbancompany.platform.review;

import com.urbancompany.platform.auth.SecurityUtils;
import com.urbancompany.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {
    private final ReviewService reviews;
    private final WarrantyService warranty;

    public record ReviewBody(@NotNull Long bookingId, @Min(1) @Max(5) int rating, @Size(max = 1000) String comment) {}
    public record ClaimBody(@NotNull Long bookingId, @NotBlank String reason, Instant preferredStart) {}

    @PostMapping("/reviews") public ApiResponse<Review> rate(@Valid @RequestBody ReviewBody b) { return ApiResponse.ok(reviews.rate(SecurityUtils.userId(), b.bookingId(), b.rating(), b.comment())); }
    @GetMapping("/reviews/partner/{partnerId}") public ApiResponse<List<Review>> forPartner(@PathVariable Long partnerId) { return ApiResponse.ok(reviews.forPartner(partnerId)); }

    @PostMapping("/warranty/claims") public ApiResponse<WarrantyClaim> claim(@Valid @RequestBody ClaimBody b) { return ApiResponse.ok(warranty.claim(SecurityUtils.userId(), b.bookingId(), b.reason(), b.preferredStart())); }
    @GetMapping("/warranty/claims") public ApiResponse<List<WarrantyClaim>> mine() { return ApiResponse.ok(warranty.mine(SecurityUtils.userId())); }
}
