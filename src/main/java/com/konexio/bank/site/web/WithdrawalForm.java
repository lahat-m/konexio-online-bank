package com.konexio.bank.site.web;

import com.konexio.bank.payment.PaymentChannel;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A withdrawal being put together, carried across the screens that build it.
 *
 * <p>Separate from {@link DepositForm} rather than one "payment in progress"
 * shared by both: money in and money out ask different questions, and a single
 * form would be a place for a half-finished deposit to turn into a withdrawal.
 */
public class WithdrawalForm {

    private PaymentChannel channel;
    private BigDecimal amount;

    /** Set once the intent exists, from the review screen onwards. */
    private UUID intentId;

    public PaymentChannel getChannel() {
        return channel;
    }

    public void setChannel(PaymentChannel channel) {
        this.channel = channel;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public UUID getIntentId() {
        return intentId;
    }

    public void setIntentId(UUID intentId) {
        this.intentId = intentId;
    }

    public boolean hasChannel() {
        return channel != null;
    }
}
