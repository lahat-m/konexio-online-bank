package com.konexio.bank.site.web;

import com.konexio.bank.transactions.ReceiptView;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A transaction as screen 4.2 draws it.
 *
 * <p>The lead is the receipt's own sentence — "Sent to JOSEPH OT*****", "Deposit
 * from M-Pesa" — and the row labels turn round with the money: what is the
 * "Recipient account" on the way out is where it came "From" on the way in, and
 * a receipt that got that backwards would be worse than one with no labels.
 *
 * @param signedAmount with a minus in front when money left, which is the first
 *                     thing the eye reads on a receipt
 */
record ReceiptCard(
        UUID id,
        String signedAmount,
        String description,
        String counterpartyLabel,
        String counterpartyAccount,
        String counterpartyDetail,
        String ownLabel,
        String ownAccount,
        String note,
        String fee,
        String date,
        String reference,
        boolean outgoing) {

    static ReceiptCard of(ReceiptView receipt, String amount, String fee, String ownAccount, ZoneId zone) {
        boolean out = receipt.outgoing();
        return new ReceiptCard(
                receipt.id(),
                (out ? "−" : "+") + amount,
                receipt.description(),
                out ? "Recipient account" : "From",
                receipt.counterpartyAccount(),
                receipt.counterpartyDetail(),
                out ? "From" : "To",
                ownAccount,
                receipt.note(),
                fee,
                Moments.dateAndTime(receipt.postedAt(), zone),
                receipt.reference(),
                out);
    }
}
