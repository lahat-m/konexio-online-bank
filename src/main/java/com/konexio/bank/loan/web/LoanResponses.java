package com.konexio.bank.loan.web;

import com.konexio.bank.loan.LoanOfferView;
import com.konexio.bank.loan.LoanView;
import com.konexio.bank.loan.RepaymentInstallmentView;
import com.konexio.bank.shared.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response bodies for the loan endpoints. */
final class LoanResponses {

    private LoanResponses() {}

    /** An offer, as screens 6.1 and 6.2 read it. */
    record OfferResponse(
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
            String status,
            Instant expiresAt) {

        static OfferResponse from(LoanOfferView offer) {
            return new OfferResponse(
                    offer.id(),
                    offer.productCode(),
                    offer.productName(),
                    offer.disburseToAccountId(),
                    offer.principal(),
                    offer.interest(),
                    offer.processingFee(),
                    offer.totalRepayable(),
                    offer.dueDate(),
                    offer.termDays(),
                    offer.status().name(),
                    offer.expiresAt());
        }
    }

    /**
     * A loan (screen 6.4).
     *
     * @param disbursementReference the journal reference for the payout — the
     *                              number that ties this loan to the money that
     *                              actually arrived
     */
    record LoanResponse(
            UUID id,
            String loanNumber,
            UUID loanAccountId,
            UUID disbursedToAccountId,
            Money principal,
            Money interest,
            Money processingFee,
            Money totalRepayable,
            Money amountRepaid,
            Money outstanding,
            String status,
            String disbursementReference,
            Instant disbursedAt,
            LocalDate dueDate,
            LocalDate overdueSince,
            Instant repaidAt) {

        static LoanResponse from(LoanView loan) {
            return new LoanResponse(
                    loan.id(),
                    loan.loanNumber(),
                    loan.loanAccountId(),
                    loan.disbursedToAccountId(),
                    loan.principal(),
                    loan.interest(),
                    loan.processingFee(),
                    loan.totalRepayable(),
                    loan.amountRepaid(),
                    loan.outstanding(),
                    loan.status().name(),
                    loan.disbursementReference(),
                    loan.disbursedAt(),
                    loan.dueDate(),
                    loan.overdueSince(),
                    loan.repaidAt());
        }
    }

    /** A row of the list (screen 6.4), without the detail a summary does not need. */
    record LoanSummaryResponse(
            UUID id,
            String loanNumber,
            Money totalRepayable,
            Money outstanding,
            String status,
            LocalDate dueDate) {

        static LoanSummaryResponse from(LoanView loan) {
            return new LoanSummaryResponse(
                    loan.id(),
                    loan.loanNumber(),
                    loan.totalRepayable(),
                    loan.outstanding(),
                    loan.status().name(),
                    loan.dueDate());
        }
    }

    /** The schedule (screen 6.5). Short enough that it is not paginated. */
    record RepaymentScheduleResponse(UUID loanId, List<InstallmentResponse> installments) {

        static RepaymentScheduleResponse of(UUID loanId, List<RepaymentInstallmentView> installments) {
            return new RepaymentScheduleResponse(
                    loanId, installments.stream().map(InstallmentResponse::from).toList());
        }
    }

    record InstallmentResponse(
            UUID id,
            int installmentNumber,
            LocalDate dueDate,
            Money amountDue,
            Money amountPaid,
            String status,
            Instant paidAt) {

        static InstallmentResponse from(RepaymentInstallmentView installment) {
            return new InstallmentResponse(
                    installment.id(),
                    installment.installmentNumber(),
                    installment.dueDate(),
                    installment.amountDue(),
                    installment.amountPaid(),
                    installment.status(),
                    installment.paidAt());
        }
    }
}
