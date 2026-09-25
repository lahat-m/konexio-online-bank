package com.konexio.bank.loan.domain;

import com.konexio.bank.loan.LoanDueSoon;
import com.konexio.bank.loan.LoanOverdue;
import com.konexio.bank.shared.money.Money;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens to a loan when nobody is looking at it: it falls due, it goes
 * overdue, and it gets reported.
 *
 * <p>All three are driven by the clock rather than by a request, which is why
 * they live here rather than in {@link LoanService} — and why each writes to the
 * outbox in the same transaction as the change it is telling the customer about.
 */
@Service
public class LoanLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LoanLifecycleService.class);

    /** Backed by {@code ix_loan_open_due}. */
    private static final String DUE_WITHIN_SQL = """
            select id, customer_id, currency, due_date,
                   principal + interest_amount + processing_fee - amount_repaid as outstanding
              from loan.loan
             where status = 'ACTIVE'
               and due_date between current_date and current_date + :days
             order by due_date
            """;

    private static final String OVERDUE_SQL = """
            select id, customer_id, currency, due_date,
                   principal + interest_amount + processing_fee - amount_repaid as outstanding
              from loan.loan
             where status = 'ACTIVE'
               and due_date < current_date
             order by due_date
            """;

    private static final String MARK_OVERDUE_SQL = """
            update loan.loan
               set status = 'OVERDUE', overdue_since = current_date, version = version + 1
             where id = :id and status = 'ACTIVE'
            """;

    /**
     * Everything still on the books, whatever its status — a bureau wants the
     * repaid ones too, because a loan settled on time is the good news a customer
     * is entitled to have on their record.
     */
    private static final String REPORTABLE_SQL = """
            select id, status, currency,
                   principal + interest_amount + processing_fee - amount_repaid as outstanding
              from loan.loan
             where status in ('ACTIVE', 'OVERDUE', 'REPAID', 'WRITTEN_OFF')
               and not exists (
                   select 1 from loan.crb_submission s
                    where s.loan_id = loan.loan.id and s.reporting_date = :reportingDate)
             order by id
             limit :limit
            """;

    /**
     * {@code ON CONFLICT DO NOTHING} against {@code uq_crb_submission}: a job that
     * runs twice on one day must not report a customer twice.
     */
    private static final String RECORD_SUBMISSION_SQL = """
            insert into loan.crb_submission
                (loan_id, reporting_date, reported_status, outstanding_amount, crb_reference, response_code)
            values
                (:loanId, :reportingDate, :status, :outstanding, :reference, :responseCode)
            on conflict (loan_id, reporting_date) do nothing
            """;

    private final JdbcClient jdbcClient;
    private final ApplicationEventPublisher eventPublisher;

    LoanLifecycleService(JdbcClient jdbcClient, ApplicationEventPublisher eventPublisher) {
        this.jdbcClient = jdbcClient;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Queues a reminder for every loan falling due within the window.
     *
     * <p>Queues one per run, so a job scheduled daily over a three-day window
     * reminds a customer three times. That is the job's schedule to decide, not
     * this method's — and {@code uq_notification_delivery} does not deduplicate
     * across events, because two reminders on different days are two messages.
     *
     * @return how many reminders were queued
     */
    @Transactional
    public int remindLoansDueWithin(int days) {
        List<DueLoan> due = jdbcClient.sql(DUE_WITHIN_SQL)
                .param("days", days)
                .query(LoanLifecycleService::mapDue)
                .list();
        due.forEach(loan -> eventPublisher.publishEvent(
                new LoanDueSoon(loan.id(), loan.customerId(), outstandingOf(loan), loan.dueDate())));
        return due.size();
    }

    /**
     * Moves loans past their due date to OVERDUE and tells the customer.
     *
     * <p>The status change and the message are one transaction: a customer told
     * their loan is overdue and a loan that is not would be worse than either.
     *
     * @return how many were moved
     */
    @Transactional
    public int markOverdueLoans() {
        List<DueLoan> overdue = jdbcClient.sql(OVERDUE_SQL).query(LoanLifecycleService::mapDue).list();
        int marked = 0;
        for (DueLoan loan : overdue) {
            // Guarded on status inside the statement: another pass, or a
            // repayment landing between the read and here, wins.
            if (jdbcClient.sql(MARK_OVERDUE_SQL).param("id", loan.id()).update() == 1) {
                eventPublisher.publishEvent(
                        new LoanOverdue(loan.id(), loan.customerId(), outstandingOf(loan), loan.dueDate()));
                marked++;
            }
        }
        return marked;
    }

    /**
     * Records one credit-bureau submission per loan per reporting date.
     *
     * <p>Records, not submits. There is no bureau connected — the same gap the
     * offer-time {@code CreditReferencePort} stub has — so what this builds is the
     * submission row, with no reference and no response code, and says plainly in
     * the log that nothing left the building. Wiring a real bureau means filling
     * those two columns in, not restructuring this.
     *
     * @return how many submissions were recorded
     */
    @Transactional
    public int reportToCreditBureau(LocalDate reportingDate, int limit) {
        List<ReportableLoan> reportable = jdbcClient.sql(REPORTABLE_SQL)
                .param("reportingDate", reportingDate)
                .param("limit", limit)
                .query((ResultSet rs, int rowNum) -> new ReportableLoan(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getBigDecimal("outstanding")))
                .list();

        for (ReportableLoan loan : reportable) {
            jdbcClient.sql(RECORD_SUBMISSION_SQL)
                    .param("loanId", loan.id())
                    .param("reportingDate", reportingDate)
                    .param("status", loan.status())
                    .param("outstanding", loan.outstanding())
                    .param("reference", null)
                    .param("responseCode", null)
                    .update();
        }
        if (!reportable.isEmpty()) {
            log.warn("Recorded {} CRB submissions for {} but sent nothing: no credit bureau is configured.",
                    reportable.size(), reportingDate);
        }
        return reportable.size();
    }

    private static Money outstandingOf(DueLoan loan) {
        return Money.of(loan.outstanding(), loan.currency());
    }

    private static DueLoan mapDue(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DueLoan(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("currency"),
                rs.getObject("due_date", LocalDate.class),
                rs.getBigDecimal("outstanding"));
    }

    /** Today, in UTC, so a job run either side of midnight local time agrees with the database. */
    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private record DueLoan(
            UUID id, UUID customerId, String currency, LocalDate dueDate, java.math.BigDecimal outstanding) {}

    private record ReportableLoan(UUID id, String status, java.math.BigDecimal outstanding) {}
}
