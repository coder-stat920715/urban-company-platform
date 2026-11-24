package com.urbancompany.platform.payment;

import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** UC Wallet + cashback ledger. Balance rows are protected by @Version (optimistic locking); every movement writes a ledger row. */
@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository wallets;
    private final JdbcClient jdbc;

    @Transactional
    public Wallet ensureWallet(Long userId) {
        return wallets.findByUserId(userId).orElseGet(() -> {
            Wallet w = new Wallet();
            w.setUserId(userId);
            w.setBalance(Money.ZERO);
            return wallets.save(w);
        });
    }

    public BigDecimal balance(Long userId) { return wallets.findByUserId(userId).map(Wallet::getBalance).orElse(Money.ZERO); }

    @Transactional
    public BigDecimal credit(Long userId, BigDecimal amount, String source, String reference, String description) {
        if (amount.signum() <= 0) return balance(userId);
        Wallet w = ensureWallet(userId);
        w.setBalance(Money.r(w.getBalance().add(amount)));
        wallets.saveAndFlush(w);
        ledger(w, "CREDIT", source, amount, reference, description);
        return w.getBalance();
    }

    @Transactional
    public BigDecimal debit(Long userId, BigDecimal amount, String source, String reference, String description) {
        if (amount.signum() <= 0) return balance(userId);
        Wallet w = ensureWallet(userId);
        if (w.getBalance().compareTo(amount) < 0) throw BusinessException.unprocessable("INSUFFICIENT_WALLET_BALANCE", "Insufficient wallet balance");
        w.setBalance(Money.r(w.getBalance().subtract(amount)));
        wallets.saveAndFlush(w);   // @Version check happens here: concurrent writer => ObjectOptimisticLockingFailureException => HTTP 409
        ledger(w, "DEBIT", source, amount, reference, description);
        return w.getBalance();
    }

    private void ledger(Wallet w, String type, String source, BigDecimal amount, String ref, String desc) {
        jdbc.sql("INSERT INTO wallet_transactions(wallet_id, type, source, amount, balance_after, reference, description) VALUES (?,?,?,?,?,?,?)")
                .param(w.getId()).param(type).param(source).param(Money.r(amount)).param(w.getBalance()).param(ref).param(desc).update();
    }

    public List<Map<String, Object>> transactions(Long userId, int page, int size) {
        Wallet w = ensureWallet(userId);
        return jdbc.sql("SELECT id, type, source, amount, balance_after, reference, description, created_at FROM wallet_transactions WHERE wallet_id = ? ORDER BY id DESC LIMIT ? OFFSET ?")
                .param(w.getId()).param(size).param((long) page * size).query().listOfRows();
    }
}
