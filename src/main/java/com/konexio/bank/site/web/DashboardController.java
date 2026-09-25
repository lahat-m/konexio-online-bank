package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.loan.LoanApi;
import com.konexio.bank.site.config.SiteProperties;
import com.konexio.bank.transactions.TransactionsApi;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The home screen (docs/screens/2).
 *
 * <p>Five modules answer this one page — who the customer is, what their account
 * holds, whether they owe anything, and what has moved lately — and each is
 * asked through its own API. Nothing here queries a table, which is what stops
 * the home screen from slowly becoming a second definition of "balance".
 *
 * <p>A customer who has just signed up has an account with nothing in it, no
 * loan and no history, so every section has to read sensibly empty. That is the
 * state the screen is in for its first customer, not an edge case.
 */
@Controller
class DashboardController {

    /** How many rows "Recent activity" shows before "See all" takes over. */
    private static final int RECENT_ACTIVITY = 3;

    private final IdentityApi identityApi;
    private final CustomerApi customerApi;
    private final AccountApi accountApi;
    private final LoanApi loanApi;
    private final TransactionsApi transactionsApi;
    private final SiteProperties properties;

    DashboardController(
            IdentityApi identityApi,
            CustomerApi customerApi,
            AccountApi accountApi,
            LoanApi loanApi,
            TransactionsApi transactionsApi,
            SiteProperties properties) {
        this.identityApi = identityApi;
        this.customerApi = customerApi;
        this.accountApi = accountApi;
        this.loanApi = loanApi;
        this.transactionsApi = transactionsApi;
        this.properties = properties;
    }

    @GetMapping("/dashboard")
    String home(Model model) {
        UUID customerId = identityApi.currentCustomerId();
        String fullName = customerApi.find(customerId).map(CustomerProfile::fullName).orElse("");

        model.addAttribute("greeting", greeting());
        model.addAttribute("firstName", Names.first(fullName));
        model.addAttribute("initials", Names.initials(fullName));

        accountApi.findMain(customerId).ifPresent(account -> addAccount(model, account));
        loanApi.findOutstanding(customerId).ifPresent(loan -> {
            model.addAttribute("loan", loan);
            model.addAttribute("loanDueDate", Moments.day(loan.dueDate()));
        });

        List<ActivityRow> activity = transactionsApi.recent(customerId, RECENT_ACTIVITY).stream()
                .map(transaction -> ActivityRow.of(transaction, properties.zone()))
                .toList();
        model.addAttribute("activity", activity);
        return "dashboard";
    }

    private static void addAccount(Model model, AccountView account) {
        model.addAttribute("account", account);
        model.addAttribute("accountNumberGrouped", Numbers.grouped(account.accountNumber()));
        model.addAttribute("balanceAmount", Numbers.amount(account.balance().amount()));
    }

    /**
     * Morning, afternoon or evening, by the bank's own clock rather than the
     * server's: a greeting that says "good evening" at nine in the morning is
     * the sort of thing a customer notices immediately.
     */
    private String greeting() {
        int hour = LocalTime.now(properties.zone()).getHour();
        if (hour < 12) {
            return "Good morning";
        }
        return hour < 17 ? "Good afternoon" : "Good evening";
    }
}
