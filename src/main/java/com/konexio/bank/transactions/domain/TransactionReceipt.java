package com.konexio.bank.transactions.domain;

import com.konexio.bank.ledger.EntryType;
import com.konexio.bank.ledger.StatementDirection;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * What a receipt has to be able to prove: which movement of money this was, when,
 * between whom, for how much, and what the bank charged for it.
 *
 * @param reference the journal reference — the number a customer quotes when
 *                  something is disputed, and the one thing on here that is
 *                  unique across the whole bank
 * @param fee       what the bank kept. Derived from the entry's own fee leg, not
 *                  from the payment record, so it is right even for a movement
 *                  the viewer did not initiate
 * @param channel   how the money travelled, when the viewer is the one who chose
 *                  it. Null on the receiving end of a transfer: the recipient did
 *                  not pick the channel and has no business seeing the sender's
 *                  M-Pesa number
 * @param note      what the customer wrote when they made the payment, on the
 *                  same terms as the channel: their own words, shown back only to
 *                  the side that wrote them
 */
public record TransactionReceipt(
        UUID id,
        UUID accountId,
        UUID journalEntryId,
        String reference,
        EntryType type,
        StatementDirection direction,
        Money amount,
        Money fee,
        Money total,
        Money balanceAfter,
        String description,
        String channel,
        String note,
        TransactionParty account,
        TransactionParty counterparty,
        Instant postedAt) {}
