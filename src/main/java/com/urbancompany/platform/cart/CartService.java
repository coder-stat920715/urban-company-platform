package com.urbancompany.platform.cart;

import com.urbancompany.platform.cart.CartModels.*;
import com.urbancompany.platform.catalog.CatalogService;
import com.urbancompany.platform.catalog.CatalogViews.*;
import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/** Redis-backed smart cart (hash per user, 24h sliding TTL). Field = lineId, value = "qty|OPT1,OPT2". Totals are always recomputed from the catalog. */
@Service
@RequiredArgsConstructor
public class CartService {
    private static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redis;
    private final CatalogService catalog;

    private String key(Long userId) { return "cart:" + userId; }

    public CartView add(Long userId, Long packageId, int quantity, List<String> optionKeys) {
        PackageDetail pd = catalog.packageDetail(packageId);
        List<String> keys = normalize(optionKeys);
        validate(pd, keys, quantity);
        String lineId = lineId(packageId, keys);
        Object existing = redis.opsForHash().get(key(userId), lineId);
        int qty = quantity + (existing == null ? 0 : Integer.parseInt(String.valueOf(existing).split("\\|", -1)[0]));
        if (qty > pd.maxQuantity()) throw BusinessException.unprocessable("MAX_QUANTITY", "Maximum quantity for this package is " + pd.maxQuantity());
        redis.opsForHash().put(key(userId), lineId, qty + "|" + String.join(",", keys) + "|" + packageId);
        redis.expire(key(userId), TTL);
        return view(userId);
    }

    public CartView updateQuantity(Long userId, String lineId, int quantity) {
        Object v = redis.opsForHash().get(key(userId), lineId);
        if (v == null) throw BusinessException.notFound("Cart item");
        if (quantity <= 0) return remove(userId, lineId);
        String[] parts = String.valueOf(v).split("\\|", -1);
        Long packageId = Long.valueOf(parts[2]);
        List<String> keys = parts[1].isBlank() ? List.of() : List.of(parts[1].split(","));
        validate(catalog.packageDetail(packageId), keys, quantity);
        redis.opsForHash().put(key(userId), lineId, quantity + "|" + parts[1] + "|" + packageId);
        redis.expire(key(userId), TTL);
        return view(userId);
    }

    public CartView remove(Long userId, String lineId) { redis.opsForHash().delete(key(userId), lineId); return view(userId); }
    public void clear(Long userId) { redis.delete(key(userId)); }

    /** Removes only the lines of one category (after a per-category booking checkout). */
    public void removeCategory(Long userId, Long categoryId) {
        view(userId).lines().stream().filter(l -> l.categoryId().equals(categoryId)).forEach(l -> redis.opsForHash().delete(key(userId), l.lineId()));
    }

    public CartView view(Long userId) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(userId));
        List<CartLine> lines = new ArrayList<>();
        for (var en : raw.entrySet()) {
            String[] parts = String.valueOf(en.getValue()).split("\\|", -1);
            int qty = Integer.parseInt(parts[0]);
            List<String> keys = parts[1].isBlank() ? List.of() : List.of(parts[1].split(","));
            Long packageId = Long.valueOf(parts[2]);
            try {
                PackageDetail pd = catalog.packageDetail(packageId);
                lines.add(priceLine(String.valueOf(en.getKey()), pd, keys, qty));
            } catch (BusinessException ex) {
                redis.opsForHash().delete(key(userId), en.getKey());   // package deactivated since it was added
            }
        }
        lines.sort(Comparator.comparing(CartLine::categoryId).thenComparing(CartLine::lineId));
        Map<Long, List<CartLine>> byCat = lines.stream().collect(Collectors.groupingBy(CartLine::categoryId, LinkedHashMap::new, Collectors.toList()));
        List<CartGroup> groups = byCat.entrySet().stream().map(e -> new CartGroup(e.getKey(), e.getValue().get(0).categoryName(), e.getValue(),
                sum(e.getValue()), e.getValue().stream().mapToInt(CartLine::durationMinutes).sum())).toList();
        return new CartView(lines, groups, sum(lines), lines.stream().mapToInt(CartLine::durationMinutes).sum(), groups.size() > 1);
    }

    /** Lines belonging to one category; enforces the multi-category split rule when categoryId is omitted. */
    public List<CartLine> linesForCheckout(Long userId, Long categoryId) {
        CartView v = view(userId);
        if (v.lines().isEmpty()) throw BusinessException.unprocessable("CART_EMPTY", "Your cart is empty");
        if (categoryId == null) {
            if (v.multiCategory()) throw BusinessException.unprocessable("MULTI_CATEGORY_CART",
                    "Cart has services from " + v.groups().size() + " categories; check out one category at a time (categoryId required). Categories: "
                            + v.groups().stream().map(g -> g.categoryId() + ":" + g.categoryName()).collect(Collectors.joining(", ")));
            return v.lines();
        }
        List<CartLine> l = v.lines().stream().filter(x -> x.categoryId().equals(categoryId)).toList();
        if (l.isEmpty()) throw BusinessException.unprocessable("CATEGORY_NOT_IN_CART", "No items from that category in your cart");
        return l;
    }

    // ---------- helpers ----------
    private BigDecimal sum(List<CartLine> l) { return Money.r(l.stream().map(CartLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)); }

    private List<String> normalize(List<String> keys) { return keys == null ? List.of() : keys.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().sorted().toList(); }

    private String lineId(Long packageId, List<String> keys) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(String.join(",", keys).getBytes(StandardCharsets.UTF_8));
            return packageId + "-" + HexFormat.of().formatHex(d).substring(0, 8);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void validate(PackageDetail pd, List<String> keys, int quantity) {
        if (quantity < 1 || quantity > pd.maxQuantity()) throw BusinessException.unprocessable("INVALID_QUANTITY", "Quantity must be between 1 and " + pd.maxQuantity());
        Set<String> valid = new HashSet<>();
        for (QuestionView q : pd.questions()) {
            List<String> chosen = q.options().stream().map(OptionView::key).filter(keys::contains).toList();
            valid.addAll(chosen);
            if (q.required() && chosen.isEmpty()) throw BusinessException.unprocessable("OPTION_REQUIRED", "Please answer: " + q.text());
            if (q.type() == com.urbancompany.platform.catalog.QuestionType.SINGLE_SELECT && chosen.size() > 1) throw BusinessException.unprocessable("SINGLE_SELECT_VIOLATION", "Only one option allowed for: " + q.text());
        }
        for (String k : keys) if (!valid.contains(k)) throw BusinessException.unprocessable("INVALID_OPTION", "Unknown option '" + k + "' for package " + pd.name());
    }

    private CartLine priceLine(String lineId, PackageDetail pd, List<String> keys, int qty) {
        BigDecimal unit = pd.basePrice();
        int dur = pd.durationMinutes();
        for (QuestionView q : pd.questions()) for (OptionView o : q.options()) if (keys.contains(o.key())) { unit = unit.add(o.priceDelta()); dur += o.durationDeltaMinutes(); }
        unit = Money.max(Money.ZERO, Money.r(unit));
        dur = Math.max(15, dur) * qty;
        return new CartLine(lineId, pd.id(), pd.name(), pd.categoryId(), pd.categoryName(), qty, keys, unit, Money.r(unit.multiply(BigDecimal.valueOf(qty))), dur, pd.instantEligible());
    }
}
