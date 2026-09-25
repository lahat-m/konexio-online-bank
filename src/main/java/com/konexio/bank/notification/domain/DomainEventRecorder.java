package com.konexio.bank.notification.domain;

import com.konexio.bank.account.AccountClosed;
import com.konexio.bank.account.AccountOpened;
import com.konexio.bank.identity.CustomerRegistered;
import com.konexio.bank.loan.LoanDisbursed;
import com.konexio.bank.loan.LoanDueSoon;
import com.konexio.bank.loan.LoanOverdue;
import com.konexio.bank.notification.AggregateType;
import com.konexio.bank.payment.PaymentCompleted;
import com.konexio.bank.payment.PaymentFailed;
import com.konexio.bank.shared.util.Masks;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes an outbox row for every domain event worth telling a customer about.
 *
 * <p>Plain {@code @EventListener}, so each row is written inside the transaction
 * that published the event. That is the transactional outbox in one sentence: a
 * transfer and its notification commit together, and a transfer that rolls back
 * leaves nothing behind promising money that never moved.
 *
 * <p>It also means the publishing modules needed no change at all. They had been
 * publishing these events since the phase each was built, with nothing listening
 * — which is exactly what the implementation order asked for
 * (docs/implementation-order.md, "Do these from Phase 0, not at the end").
 *
 * <p>Payloads carry what a template renders and a {@code customerId}, never a
 * phone number or an email address. Those are resolved when the message is
 * actually sent, so a customer who changes their number is reached on the new
 * one and the table does not become a contact list.
 */
@Component
class DomainEventRecorder {

    private final OutboxWriter outbox;

    DomainEventRecorder(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @EventListener
    void on(CustomerRegistered event) {
        outbox.write(AggregateType.CUSTOMER, event.customerId(), "CustomerRegistered",
                payload(event.customerId().toString(), Map.of("fullName", event.fullName())));
    }

    @EventListener
    void on(AccountOpened event) {
        outbox.write(AggregateType.ACCOUNT, event.accountId(), "AccountOpened",
                payload(event.customerId().toString(), Map.of(
                        "accountType", event.accountType().name(),
                        "maskedNumber", String.valueOf(Masks.accountNumber(event.accountNumber())))));
    }

    @EventListener
    void on(AccountClosed event) {
        outbox.write(AggregateType.ACCOUNT, event.accountId(), "AccountClosed",
                payload(event.customerId().toString(), Map.of(
                        "maskedNumber", String.valueOf(Masks.accountNumber(event.accountNumber())),
                        "closureReference", event.closureReference(),
                        "reason", event.reason().name())));
    }

    @EventListener
    void on(PaymentCompleted event) {
        outbox.write(AggregateType.PAYMENT, event.intentId(), "PaymentCompleted",
                payload(event.customerId().toString(), Map.of(
                        "intentType", event.intentType().name(),
                        "channel", event.channel().name(),
                        "amount", event.amount().toString(),
                        "fee", event.fee().toString(),
                        "reference", String.valueOf(event.reference()))));
    }

    @EventListener
    void on(PaymentFailed event) {
        outbox.write(AggregateType.PAYMENT, event.intentId(), "PaymentFailed",
                payload(event.customerId().toString(), Map.of(
                        "intentType", event.intentType().name(),
                        "amount", event.amount().toString(),
                        "failureCode", String.valueOf(event.failureCode()),
                        "failureReason", String.valueOf(event.failureReason()))));
    }

    @EventListener
    void on(LoanDisbursed event) {
        outbox.write(AggregateType.LOAN, event.loanId(), "LoanDisbursed",
                payload(event.customerId().toString(), Map.of(
                        "loanNumber", event.loanNumber(),
                        "principal", event.principal().toString(),
                        "totalRepayable", event.totalRepayable().toString(),
                        "dueDate", event.dueDate().toString(),
                        "reference", String.valueOf(event.disbursementReference()))));
    }

    @EventListener
    void on(LoanDueSoon event) {
        outbox.write(AggregateType.LOAN, event.loanId(), "LoanDueSoon",
                payload(event.customerId().toString(), Map.of(
                        "outstanding", event.outstanding().toString(),
                        "dueDate", event.dueDate().toString())));
    }

    @EventListener
    void on(LoanOverdue event) {
        outbox.write(AggregateType.LOAN, event.loanId(), "LoanOverdue",
                payload(event.customerId().toString(), Map.of(
                        "outstanding", event.outstanding().toString(),
                        "dueDate", event.dueDate().toString())));
    }

    private static Map<String, Object> payload(String customerId, Map<String, Object> fields) {
        Map<String, Object> payload = new HashMap<>(fields);
        payload.put("customerId", customerId);
        return payload;
    }
}
