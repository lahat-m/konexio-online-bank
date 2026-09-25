package com.konexio.bank.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.konexio.bank.shared.money.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The double-entry rules, checked where they can be checked without a database.
 *
 * <p>{@code trg_posting_balanced} enforces the same thing at COMMIT, but by then
 * the balances have already moved and the error names a constraint. These are
 * the rules stated as the calling module will hit them.
 */
class PostEntryCommandTest {

    private static final UUID FROM = UUID.fromString("0192f7a4-0000-7000-8000-000000000001");
    private static final UUID TO = UUID.fromString("0192f7a4-0000-7000-8000-000000000002");
    private static final UUID FEE_INCOME = UUID.fromString("0192f7a4-0000-7000-8000-000000000003");

    @Test
    @DisplayName("a two-line entry whose debit equals its credit is accepted")
    void acceptsABalancedEntry() {
        PostEntryCommand command = transfer(List.of(
                PostingLine.debit(FROM, Money.kes("3500.00")),
                PostingLine.credit(TO, Money.kes("3500.00"))));

        assertThat(command.total()).isEqualTo(Money.kes("3500.00"));
        assertThat(command.currency()).isEqualTo("KES");
    }

    @Test
    @DisplayName("a fee is extra lines in the same entry, and it still has to balance")
    void acceptsAFeeInTheSameEntry() {
        PostEntryCommand command = transfer(List.of(
                PostingLine.debit(FROM, Money.kes("3550.00")),
                PostingLine.credit(TO, Money.kes("3500.00")),
                PostingLine.credit(FEE_INCOME, Money.kes("50.00"))));

        assertThat(command.lines()).hasSize(3);
        assertThat(command.total()).isEqualTo(Money.kes("3550.00"));
    }

    @Test
    @DisplayName("debits that do not equal credits are refused")
    void refusesAnUnbalancedEntry() {
        assertThatThrownBy(() -> transfer(List.of(
                PostingLine.debit(FROM, Money.kes("3500.00")),
                PostingLine.credit(TO, Money.kes("3400.00")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not balance");
    }

    @Test
    @DisplayName("a single-sided entry is refused: money always comes from somewhere")
    void refusesASingleLine() {
        assertThatThrownBy(() -> transfer(List.of(PostingLine.credit(TO, Money.kes("3500.00")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two lines");
    }

    @Test
    @DisplayName("one entry cannot span two currencies")
    void refusesMixedCurrencies() {
        assertThatThrownBy(() -> transfer(List.of(
                PostingLine.debit(FROM, Money.kes("3500.00")),
                PostingLine.credit(TO, Money.of(new java.math.BigDecimal("3500.00"), "USD")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one currency");
    }

    @Test
    @DisplayName("a zero or negative line is refused: direction carries the sign")
    void refusesNonPositiveAmounts() {
        assertThatThrownBy(() -> PostingLine.debit(FROM, Money.kes("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> PostingLine.credit(TO, Money.kes("-10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    @Test
    @DisplayName("a description is required and is capped at what the column allows")
    void requiresAUsableDescription() {
        assertThatThrownBy(() -> new PostEntryCommand(
                EntryType.TRANSFER, SourceType.PAYMENT, UUID.randomUUID(), "  ", balancedLines()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");

        assertThatThrownBy(() -> new PostEntryCommand(
                EntryType.TRANSFER, SourceType.PAYMENT, UUID.randomUUID(),
                "x".repeat(PostEntryCommand.MAX_DESCRIPTION_LENGTH + 1), balancedLines()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 200");
    }

    @Test
    @DisplayName("lines are held in ascending account id, the lock order every writer uses")
    void ordersLinesForLocking() {
        // Compared unsigned: a signed comparison would put the high id first.
        UUID high = UUID.fromString("ffffffff-ffff-7fff-8fff-ffffffffffff");
        UUID low = UUID.fromString("00000000-0000-7000-8000-000000000001");

        PostEntryCommand command = transfer(List.of(
                PostingLine.debit(high, Money.kes("100.00")),
                PostingLine.credit(low, Money.kes("100.00"))));

        assertThat(command.lines()).extracting(PostingLine::accountId).containsExactly(low, high);
    }

    private static List<PostingLine> balancedLines() {
        return List.of(
                PostingLine.debit(FROM, Money.kes("100.00")),
                PostingLine.credit(TO, Money.kes("100.00")));
    }

    private static PostEntryCommand transfer(List<PostingLine> lines) {
        return new PostEntryCommand(
                EntryType.TRANSFER, SourceType.PAYMENT, UUID.randomUUID(), "Cement, invoice 118", lines);
    }
}
