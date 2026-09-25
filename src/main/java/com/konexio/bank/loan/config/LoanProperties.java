package com.konexio.bank.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param creditReference which {@link com.konexio.bank.loan.domain.CreditReferencePort}
 *                        to wire: {@code stub}, or a real bureau client once one
 *                        exists
 * @param minimumCreditScore the score below which no offer is made.
 *                        <strong>Placeholder value</strong>: this is a credit
 *                        policy decision, and the scale it is on belongs to
 *                        whichever bureau is eventually connected. It exists so
 *                        that the threshold is a setting rather than something
 *                        nobody thought about.
 * @param installments    how many instalments a loan is repaid in. One, for the
 *                        instant product — a 30-day advance repaid in a single
 *                        payment on the due date.
 */
@ConfigurationProperties(prefix = "app.loan")
public record LoanProperties(
        @DefaultValue("stub") String creditReference,
        @DefaultValue("600") int minimumCreditScore,
        @DefaultValue("1") int installments) {}
