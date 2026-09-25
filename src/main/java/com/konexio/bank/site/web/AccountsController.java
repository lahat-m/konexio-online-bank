package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.account.ClosureEligibility;
import com.konexio.bank.account.ClosureReason;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.identity.StepUpTokenIssued;
import com.konexio.bank.shared.error.ApiException;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.site.config.SiteProperties;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;

/**
 * The customer's accounts, and the one thing they can do to one of them that
 * cannot be undone (docs/screens/5.1 to 5.3d).
 *
 * <p>Closing is built as a flow rather than a button for the reason the mocks
 * give it three screens: the checks have to be explained before they can be
 * argued with, the warning has to be read, and the PIN has to be re-entered. The
 * account module decides all of it — this asks, shows the answer, and asks
 * again.
 */
@Controller
@SessionAttributes(AccountsController.CLOSURE)
class AccountsController {

    static final String CLOSURE = "closure";

    /** More than anybody has, and the screen is a list rather than a page of them. */
    private static final int ACCOUNTS = 20;

    private final IdentityApi identityApi;
    private final AccountApi accountApi;
    private final CustomerApi customerApi;
    private final SiteProperties properties;

    AccountsController(
            IdentityApi identityApi,
            AccountApi accountApi,
            CustomerApi customerApi,
            SiteProperties properties) {
        this.identityApi = identityApi;
        this.accountApi = accountApi;
        this.customerApi = customerApi;
        this.properties = properties;
    }

    @ModelAttribute(CLOSURE)
    ClosureForm closureForm() {
        return new ClosureForm();
    }

    // ------------------------------------------------------ 5.1 the accounts

    @GetMapping("/accounts")
    String accounts(Model model) {
        model.addAttribute("accounts", accountApi
                .listFor(identityApi.currentCustomerId(), PageRequest.of(0, ACCOUNTS))
                .getContent()
                .stream()
                .filter(account -> account.accountType().isCustomerType())
                .map(account -> AccountCard.of(account, properties.zone()))
                .toList());
        return "accounts";
    }

    // ------------------------------------------------------- 5.2 one account

    @GetMapping("/accounts/{accountId}")
    String account(@PathVariable UUID accountId, Model model) {
        AccountView account = ownAccount(accountId);
        model.addAttribute("account", AccountDetail.of(account, written(account.balance()), properties.zone()));
        return "account";
    }

    // --------------------------------------------------- 5.3a the three checks

    /**
     * What stands between this account and being closed.
     *
     * <p>Re-asked every time this screen is drawn, which is what makes "I've
     * moved the balance, check again" a link back to here rather than anything
     * cleverer.
     */
    @GetMapping("/accounts/{accountId}/closure")
    String closureChecks(
            @PathVariable UUID accountId, @ModelAttribute(CLOSURE) ClosureForm form, Model model) {
        AccountView account = ownAccount(accountId);
        if (!account.accountType().isSelfServiceType()) {
            return "redirect:/accounts/" + accountId;
        }
        form.startOn(accountId);

        addChecks(account, model);
        return "account/closure-checks";
    }

    /** "Continue", which is only offered when all three checks pass. */
    @PostMapping("/accounts/{accountId}/closure")
    String startClosing(@PathVariable UUID accountId, @ModelAttribute(CLOSURE) ClosureForm form, Model model) {
        AccountView account = ownAccount(accountId);
        form.startOn(accountId);

        if (!accountApi.closureEligibility(accountId, identityApi.currentCustomerId()).eligible()) {
            addChecks(account, model);
            return "account/closure-checks";
        }
        return "redirect:/accounts/" + accountId + "/closure/confirm";
    }

    // ------------------------------------------------ 5.3b the warning

    @GetMapping("/accounts/{accountId}/closure/confirm")
    String confirmClosure(
            @PathVariable UUID accountId, @ModelAttribute(CLOSURE) ClosureForm form, Model model) {
        AccountView account = ownAccount(accountId);
        form.startOn(accountId);

        // Checked again on the way in: a customer can sit on this screen while a
        // payment lands in the account behind it.
        if (!accountApi.closureEligibility(accountId, identityApi.currentCustomerId()).eligible()) {
            return "redirect:/accounts/" + accountId + "/closure";
        }

        addAccount(account, model);
        addReasons(form, model);
        return "account/closure-confirm";
    }

    @PostMapping("/accounts/{accountId}/closure/confirm")
    String acceptWarning(
            @PathVariable UUID accountId,
            @ModelAttribute(CLOSURE) ClosureForm form,
            @RequestParam(name = "reason", required = false) ClosureReason reason,
            @RequestParam(name = "acknowledged", required = false) boolean acknowledged,
            Model model) {
        AccountView account = ownAccount(accountId);
        form.startOn(accountId);
        form.setReason(reason == null ? ClosureReason.NOT_USED : reason);
        form.setAcknowledged(acknowledged);

        if (!acknowledged) {
            // The one box that is not optional. Said here rather than by disabling
            // the button, so somebody who missed it is told why.
            addAccount(account, model);
            addReasons(form, model);
            model.addAttribute("acknowledgeError", "Tick the box to confirm you understand this can't be undone");
            return "account/closure-confirm";
        }
        return "redirect:/accounts/" + accountId + "/closure/pin";
    }

    // ---------------------------------------------------------- 5.3c the PIN

    @GetMapping("/accounts/{accountId}/closure/pin")
    String closurePin(
            @PathVariable UUID accountId, @ModelAttribute(CLOSURE) ClosureForm form, Model model) {
        if (!form.isFor(accountId) || !form.isAcknowledged()) {
            return "redirect:/accounts/" + accountId + "/closure/confirm";
        }
        addPinDetails(ownAccount(accountId), model);
        return "account/closure-pin";
    }

    /**
     * The PIN, the token and the closure, in one request.
     *
     * <p>Identity mints a token bound to this account and the account module
     * burns it inside the transaction that closes it — so a token minted for one
     * account cannot close another, and a closure the database refuses does not
     * cost the customer their PIN entry.
     */
    @PostMapping("/accounts/{accountId}/closure/pin")
    String closeWithPin(
            @PathVariable UUID accountId,
            @ModelAttribute(CLOSURE) ClosureForm form,
            @RequestParam(name = "pin", required = false) String pin,
            Model model) {
        if (!form.isFor(accountId) || !form.isAcknowledged()) {
            return "redirect:/accounts/" + accountId + "/closure/confirm";
        }

        String entered = pin == null ? "" : pin.trim();
        if (entered.length() != identityApi.pinLength()) {
            return pinError(accountId, model, "Enter all %d digits of your PIN".formatted(identityApi.pinLength()));
        }

        try {
            StepUpTokenIssued stepUp =
                    identityApi.issueStepUp(StepUpIntentType.ACCOUNT_CLOSURE, accountId, entered);
            AccountView closed = accountApi.closeAccount(
                    accountId, identityApi.currentCustomerId(), form.getReason(), stepUp.stepUpToken());
            form.setClosureReference(closed.closureReference());
        } catch (ApiException refused) {
            // A wrong PIN, or a check that stopped being true while they typed.
            return pinError(accountId, model, refused.getMessage());
        }
        return "redirect:/accounts/" + accountId + "/closed";
    }

    // ------------------------------------------------------- 5.3d the receipt

    /**
     * How the closure ended.
     *
     * <p>The account is still readable — closing is a logical delete — so this
     * reads it back rather than trusting the session for anything but the fact
     * that this browser is the one that just closed it.
     */
    @GetMapping("/accounts/{accountId}/closed")
    String closed(
            @PathVariable UUID accountId,
            @ModelAttribute(CLOSURE) ClosureForm form,
            SessionStatus session,
            Model model) {
        AccountView account = ownAccount(accountId);
        if (!account.status().isOpen() && form.isFor(accountId)) {
            model.addAttribute("account", AccountDetail.of(account, written(account.balance()), properties.zone()));
            model.addAttribute("closedOn", Moments.day(
                    account.closedAt() == null
                            ? null
                            : account.closedAt().atZone(properties.zone()).toLocalDate()));
            model.addAttribute("reference", account.closureReference());
            model.addAttribute("balance", written(account.balance()));
            model.addAttribute("email", customerApi.find(identityApi.currentCustomerId())
                    .map(CustomerProfile::email)
                    .orElse(null));

            session.setComplete();
            return "account/closed";
        }
        return "redirect:/accounts";
    }

    // ----------------------------------------------------------------- shared

    private void addChecks(AccountView account, Model model) {
        ClosureEligibility eligibility =
                accountApi.closureEligibility(account.id(), identityApi.currentCustomerId());

        addAccount(account, model);
        model.addAttribute("eligible", eligibility.eligible());
        model.addAttribute("checks", eligibility.checks().stream()
                .map(check -> ClosureCheckRow.of(check, account, properties.zone()))
                .toList());
    }

    private void addReasons(ClosureForm form, Model model) {
        model.addAttribute("reasons", java.util.Arrays.stream(ClosureReason.values())
                .map(reason -> ClosureReasonOption.of(reason, form.getReason()))
                .toList());
    }

    private void addAccount(AccountView account, Model model) {
        model.addAttribute("account", AccountDetail.of(account, written(account.balance()), properties.zone()));
        // "Savings ••••0457", the way every screen in this flow names the account.
        model.addAttribute("accountName",
                Labels.readable(account.accountType()) + " " + account.maskedNumber());
        model.addAttribute("zeroBalance",
                account.balance().currency() + " " + Numbers.amount(java.math.BigDecimal.ZERO));
    }

    private String pinError(UUID accountId, Model model, String message) {
        model.addAttribute("pinError", message);
        addPinDetails(ownAccount(accountId), model);
        return "account/closure-pin";
    }

    private void addPinDetails(AccountView account, Model model) {
        addAccount(account, model);
        model.addAttribute("pinLength", identityApi.pinLength());
    }

    /**
     * Somebody else's account is not found rather than refused, which is the same
     * answer as an id that never existed.
     */
    private AccountView ownAccount(UUID accountId) {
        return accountApi.requireOwned(accountId, identityApi.currentCustomerId());
    }

    private static String written(Money money) {
        return money.currency() + " " + Numbers.amount(money.amount());
    }
}
