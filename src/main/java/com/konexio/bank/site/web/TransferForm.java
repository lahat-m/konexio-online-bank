package com.konexio.bank.site.web;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A transfer being put together, carried across the screens that build it.
 *
 * <p>The recipient is held as an account number rather than an account id: it is
 * what the customer typed and what the next screen shows back to them, and
 * turning it into an id is the payment module's job when the transfer is quoted.
 */
public class TransferForm {

    private String recipientAccountNumber;
    private String recipientName;
    private String recipientMaskedAccountNumber;
    private boolean firstTimeRecipient;
    private BigDecimal amount;
    private String note;

    /** Set once the intent exists, from the review screen onwards. */
    private UUID intentId;

    public String getRecipientAccountNumber() {
        return recipientAccountNumber;
    }

    public void setRecipientAccountNumber(String recipientAccountNumber) {
        this.recipientAccountNumber = recipientAccountNumber;
    }

    /** As the lookup masked it, {@code GRACE MU***}. */
    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    /** As the lookup masked it, {@code ••••4420}. */
    public String getRecipientMaskedAccountNumber() {
        return recipientMaskedAccountNumber;
    }

    public void setRecipientMaskedAccountNumber(String recipientMaskedAccountNumber) {
        this.recipientMaskedAccountNumber = recipientMaskedAccountNumber;
    }

    /**
     * True when this customer has never completed a transfer to this account, and
     * so should be asked to check the name against the person they are paying.
     */
    public boolean isFirstTimeRecipient() {
        return firstTimeRecipient;
    }

    public void setFirstTimeRecipient(boolean firstTimeRecipient) {
        this.firstTimeRecipient = firstTimeRecipient;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public UUID getIntentId() {
        return intentId;
    }

    public void setIntentId(UUID intentId) {
        this.intentId = intentId;
    }

    public boolean hasRecipient() {
        return recipientAccountNumber != null && !recipientAccountNumber.isBlank();
    }
}
