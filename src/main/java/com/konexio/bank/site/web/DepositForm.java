package com.konexio.bank.site.web;

import com.konexio.bank.payment.PaymentChannel;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A deposit being put together, carried across screens 3.1a to 3.1e.
 *
 * <p>In the session for the same reason the sign-up is: the payment module takes
 * a channel and an amount together, and the screens ask for them one at a time.
 * Nothing is created until the customer has seen the review — up to then this is
 * only what they have chosen.
 */
public class DepositForm {

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
