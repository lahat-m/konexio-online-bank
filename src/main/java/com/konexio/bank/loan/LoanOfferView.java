package com.konexio.bank.loan;

import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A priced offer, as screens 6.1 and 6.2 show it.
 *
 * <p>Every amount is fixed at the moment the offer was made. Pricing is
 * time-versioned, so an offer taken up tomorrow settles on the terms it was
 * quoted today — which is the whole reason an offer is a stored row rather than
 * a calculation repeated on each request.
 *
 * @param totalRepayable principal plus interest plus fee, the one number the
 *                       customer is actually agreeing to
 */
public record LoanOfferView(
        UUID id,
        String productCode,
        String productName,
        UUID disburseToAccountId,
        Money principal,
        Money interest,
        Money processingFee,
        Money totalRepayable,
        LocalDate dueDate,
        int termDays,
        OfferStatus status,
        Instant expiresAt) {}
