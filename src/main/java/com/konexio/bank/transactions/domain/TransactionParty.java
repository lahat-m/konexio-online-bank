package com.konexio.bank.transactions.domain;

/**
 * The other end of a transaction, as much of it as the viewer may see.
 *
 * @param name          masked when it is another customer ({@code JOSEPH OT*****}),
 *                      or the channel's own name when the money came from or went
 *                      outside the bank
 * @param accountNumber masked, or null when there is no account to show — an
 *                      M-Pesa payout has a phone number, not an account
 * @param detail        the phone number or agent code, masked, when the viewer is
 *                      the one who entered it
 */
public record TransactionParty(String name, String accountNumber, String detail) {

    static TransactionParty named(String name) {
        return new TransactionParty(name, null, null);
    }
}
