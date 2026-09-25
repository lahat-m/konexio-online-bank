package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.site.config.SiteProperties;
import com.konexio.bank.transactions.ReceiptView;
import com.konexio.bank.transactions.Transaction;
import com.konexio.bank.transactions.TransactionsApi;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * The history and one line of it in full (docs/screens/4.1 and 4.2).
 *
 * <p>Read-only, and the only part of the site that is: every other screen is a
 * step in a flow that ends in money moving. This one is what a customer opens
 * when they want to know what already happened — so it is addressable, each row
 * has its own URL, and nothing here writes anything.
 */
@Controller
class ActivityController {

    /**
     * A screenful and then some. The list is not paged yet: the chips are the way
     * a customer narrows it, and a "load more" that nothing links to would be
     * plumbing for a screen that does not exist.
     */
    private static final int PAGE_SIZE = 50;

    private final IdentityApi identityApi;
    private final AccountApi accountApi;
    private final TransactionsApi transactionsApi;
    private final SiteProperties properties;

    ActivityController(
            IdentityApi identityApi,
            AccountApi accountApi,
            TransactionsApi transactionsApi,
            SiteProperties properties) {
        this.identityApi = identityApi;
        this.accountApi = accountApi;
        this.transactionsApi = transactionsApi;
        this.properties = properties;
    }

    // ------------------------------------------------------- 4.1 the history

    @GetMapping("/activity")
    String history(@RequestParam(name = "filter", required = false) String filter, Model model) {
        ActivityFilter chosen = ActivityFilter.of(filter);
        List<Transaction> transactions = transactionsApi
                .history(identityApi.currentCustomerId(), chosen.direction(), chosen.types(), 0, PAGE_SIZE)
                .getContent();

        model.addAttribute("chips", Arrays.stream(ActivityFilter.values())
                .map(available -> available.chip(chosen))
                .toList());
        model.addAttribute("days", byDay(transactions));
        return "activity";
    }

    /**
     * The rows grouped under Today, Yesterday and their dates.
     *
     * <p>Grouped here rather than in the template because the heading depends on
     * the customer's own clock, and a run of rows is only one day's worth if the
     * zone saying so is the one they read the time in.
     */
    private List<ActivityEntry.Day> byDay(List<Transaction> transactions) {
        ZoneId zone = properties.zone();
        List<ActivityEntry.Day> days = new ArrayList<>();
        String heading = null;
        List<ActivityEntry> entries = new ArrayList<>();

        for (Transaction transaction : transactions) {
            String day = Moments.dayHeading(transaction.postedAt(), zone);
            if (!day.equals(heading)) {
                if (heading != null) {
                    days.add(new ActivityEntry.Day(heading, List.copyOf(entries)));
                }
                heading = day;
                entries = new ArrayList<>();
            }
            entries.add(ActivityEntry.of(transaction, zone));
        }
        if (heading != null) {
            days.add(new ActivityEntry.Day(heading, List.copyOf(entries)));
        }
        return days;
    }

    // ------------------------------------------------------- 4.2 the receipt

    /**
     * One transaction in full.
     *
     * <p>Somebody else's posting is a 404 from the transactions module, which is
     * the same answer as an id that never existed — on purpose, so a receipt URL
     * cannot be used to find out whose it is.
     */
    @GetMapping("/activity/{transactionId}")
    String receipt(@PathVariable UUID transactionId, Model model) {
        UUID customerId = identityApi.currentCustomerId();
        ReceiptView receipt = transactionsApi.receipt(transactionId, customerId);

        model.addAttribute("receipt", ReceiptCard.of(
                receipt,
                written(receipt.amount()),
                written(receipt.fee()),
                ownAccountLabel(receipt),
                properties.zone()));
        return "receipt";
    }

    /**
     * The same receipt as a file.
     *
     * <p>Rendered by the transactions module, which is where the receipt is
     * defined; this exists because the API's own PDF endpoint is behind a bearer
     * token and a browser on this site has a session cookie instead.
     */
    @GetMapping("/activity/{transactionId}/receipt.pdf")
    @ResponseBody
    ResponseEntity<byte[]> receiptPdf(@PathVariable UUID transactionId) {
        UUID customerId = identityApi.currentCustomerId();
        ReceiptView receipt = transactionsApi.receipt(transactionId, customerId);
        byte[] pdf = transactionsApi.receiptPdf(transactionId, customerId);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"%s.pdf\"".formatted(receipt.reference()))
                .body(pdf);
    }

    /** {@code Main account ••••3310}, as the receipt names the side that is theirs. */
    private String ownAccountLabel(ReceiptView receipt) {
        return accountApi.find(receipt.accountId())
                .map(account -> Labels.readable(account.accountType()) + " account " + account.maskedNumber())
                .orElse(receipt.accountMaskedNumber());
    }

    private static String written(Money money) {
        return money.currency() + " " + Numbers.amount(money.amount());
    }
}
