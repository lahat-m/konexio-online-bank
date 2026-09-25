package com.konexio.bank.staff.web;

import com.konexio.bank.account.AccountView;
import com.konexio.bank.staff.domain.CustomerDossier;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.identity.SecurityEventView;
import com.konexio.bank.ledger.PostedEntry;
import com.konexio.bank.ledger.PostedLine;
import com.konexio.bank.ledger.StatementLine;
import com.konexio.bank.shared.audit.AuditEntry;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response bodies for the staff console.
 *
 * <p>Fuller than the customer-facing ones, on purpose: an account number is
 * masked on a customer's screen because a screenshot of it travels, and shown in
 * full here because support is asked "which account?" twenty times a day. What
 * is <em>not</em> here is anything the bank does not store in the clear anyway —
 * there is no unmasked phone number on a security event, because the trail never
 * held one.
 */
final class StaffResponses {

    private StaffResponses() {}

    /** A row in the search results. */
    record CustomerSummaryResponse(
            UUID customerId,
            String fullName,
            String phone,
            String email,
            String kycLevel,
            String status,
            Instant customerSince) {

        static CustomerSummaryResponse from(CustomerProfile profile) {
            return new CustomerSummaryResponse(
                    profile.customerId(),
                    profile.fullName(),
                    profile.phone(),
                    profile.email(),
                    profile.kycLevel(),
                    profile.status(),
                    profile.customerSince());
        }
    }

    /** The customer screen: profile plus every account, closed ones included. */
    record CustomerDossierResponse(CustomerSummaryResponse customer, List<AccountResponse> accounts) {

        static CustomerDossierResponse from(CustomerDossier dossier) {
            return new CustomerDossierResponse(
                    CustomerSummaryResponse.from(dossier.profile()),
                    dossier.accounts().stream().map(AccountResponse::from).toList());
        }
    }

    record AccountResponse(
            UUID id,
            String accountNumber,
            UUID customerId,
            String type,
            String status,
            Money balance,
            String nickname,
            Instant openedAt,
            Instant lastCustomerActivityAt,
            Instant dormantSince,
            Instant closedAt,
            String closureReason,
            String closureReference) {

        static AccountResponse from(AccountView account) {
            return new AccountResponse(
                    account.id(),
                    account.accountNumber(),
                    account.customerId(),
                    account.accountType().name(),
                    account.status().name(),
                    account.balance(),
                    account.nickname(),
                    account.openedAt(),
                    account.lastCustomerActivityAt(),
                    account.dormantSince(),
                    account.closedAt(),
                    account.closureReason() == null ? null : account.closureReason().name(),
                    account.closureReference());
        }
    }

    /**
     * A journal entry with both legs of every posting — the bank's own view,
     * where crediting a clearing account is an ordinary thing to see and the
     * customer's statement would have hidden it.
     */
    record JournalEntryResponse(
            UUID id,
            String reference,
            String entryType,
            String sourceType,
            UUID sourceId,
            String description,
            Instant postedAt,
            List<JournalLineResponse> lines) {

        static JournalEntryResponse from(PostedEntry entry) {
            return new JournalEntryResponse(
                    entry.id(),
                    entry.reference(),
                    entry.entryType().name(),
                    entry.sourceType().name(),
                    entry.sourceId(),
                    entry.description(),
                    entry.postedAt(),
                    entry.lines().stream().map(JournalLineResponse::from).toList());
        }
    }

    record JournalLineResponse(
            UUID id, UUID accountId, String direction, Money amount, Money balanceAfter, Instant postedAt) {

        static JournalLineResponse from(PostedLine line) {
            return new JournalLineResponse(
                    line.id(),
                    line.accountId(),
                    line.direction().name(),
                    line.amount(),
                    line.balanceAfter(),
                    line.postedAt());
        }
    }

    /** One line of a customer's statement, with the journal entry behind it named. */
    record StatementLineResponse(
            UUID postingId,
            UUID accountId,
            String accountMaskedNumber,
            UUID journalEntryId,
            String reference,
            String entryType,
            String direction,
            Money amount,
            Money balanceAfter,
            String description,
            Instant postedAt) {

        static StatementLineResponse from(StatementLine line) {
            return new StatementLineResponse(
                    line.postingId(),
                    line.accountId(),
                    line.accountMaskedNumber(),
                    line.journalEntryId(),
                    line.reference(),
                    line.entryType().name(),
                    line.direction().name(),
                    line.amount(),
                    line.balanceAfter(),
                    line.description(),
                    line.postedAt());
        }
    }

    record AuditEventResponse(
            UUID id,
            Instant occurredAt,
            String actorType,
            UUID actorId,
            String action,
            String resourceType,
            UUID resourceId,
            String outcome,
            String requestId,
            String ipAddress,
            String deviceId,
            Map<String, Object> details) {

        static AuditEventResponse from(AuditEntry entry) {
            return new AuditEventResponse(
                    entry.id(),
                    entry.occurredAt(),
                    entry.actorType(),
                    entry.actorId(),
                    entry.action(),
                    entry.resourceType(),
                    entry.resourceId(),
                    entry.outcome(),
                    entry.requestId(),
                    entry.ipAddress(),
                    entry.deviceId(),
                    entry.details());
        }
    }

    record SecurityEventResponse(
            UUID id,
            Instant occurredAt,
            String eventType,
            String subjectType,
            UUID subjectId,
            String phoneMasked,
            String deviceId,
            String ipAddress,
            String userAgent,
            Map<String, Object> details) {

        static SecurityEventResponse from(SecurityEventView event) {
            return new SecurityEventResponse(
                    event.id(),
                    event.occurredAt(),
                    event.eventType(),
                    event.subjectType(),
                    event.subjectId(),
                    event.phoneMasked(),
                    event.deviceId(),
                    event.ipAddress(),
                    event.userAgent(),
                    event.details());
        }
    }
}
