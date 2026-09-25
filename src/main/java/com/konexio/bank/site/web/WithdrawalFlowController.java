package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.identity.StepUpTokenIssued;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentApi;
import com.konexio.bank.payment.PaymentChannel;
import com.konexio.bank.payment.PaymentIntentView;
import com.konexio.bank.shared.error.ApiException;
import com.konexio.bank.shared.util.Masks;
import com.konexio.bank.site.config.SiteProperties;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;

/**
 * Taking money out (docs/screens/3.2a onwards).
 *
 * <p>Built like the deposit flow and kept separate from it, because the two are
 * not the same journey read backwards. A deposit asks somebody else for money
 * and waits; a withdrawal spends what the account holds, so it can fail for a
 * reason a deposit never can — there not being enough — and the screens have to
 * say so before the customer picks a number.
 *
 * <p>Where the money may go is the payment module's rule, not this one's:
 * M-Pesa or an agent, never a card. It is asked for rather than restated, so the
 * screen and the service cannot drift apart.
 */
@Controller
@SessionAttributes(WithdrawalFlowController.FORM)
class WithdrawalFlowController {

    static final String FORM = "withdrawal";

    private final IdentityApi identityApi;
    private final CustomerApi customerApi;
    private final AccountApi accountApi;
    private final PaymentApi paymentApi;
    private final SiteProperties properties;

    WithdrawalFlowController(
            IdentityApi identityApi,
            CustomerApi customerApi,
            AccountApi accountApi,
            PaymentApi paymentApi,
            SiteProperties properties) {
        this.identityApi = identityApi;
        this.customerApi = customerApi;
        this.accountApi = accountApi;
        this.paymentApi = paymentApi;
        this.properties = properties;
    }

    @ModelAttribute(FORM)
    WithdrawalForm withdrawalForm() {
        return new WithdrawalForm();
    }

    // -------------------------------------------------- 3.2a the destination

    @GetMapping("/withdrawals")
    String destination(@ModelAttribute(FORM) WithdrawalForm form, Model model) {
        UUID customerId = identityApi.currentCustomerId();
        Optional<AccountView> main = accountApi.findMain(customerId);
        if (main.isEmpty()) {
            return "redirect:/dashboard";
        }

        model.addAttribute("account", main.get());
        model.addAttribute("phoneMasked", customerApi.find(customerId)
                .map(profile -> Masks.phone(profile.phone()))
                .orElse(null));
        model.addAttribute("channel", form.hasChannel() ? form.getChannel() : PaymentChannel.MPESA);
        return "withdraw/destination";
    }

    /**
     * The channel binds onto the session form, so an unknown one is a binding
     * error — hence the {@link BindingResult}, without which Spring answers 400
     * before this runs. A channel the module does not pay out to is refused the
     * same way rather than being quietly swapped for one it does.
     */
    @PostMapping("/withdrawals")
    String chooseDestination(
            @ModelAttribute(FORM) WithdrawalForm form, BindingResult errors, Model model) {
        if (errors.hasErrors()
                || !form.hasChannel()
                || !paymentApi.withdrawalChannels().contains(form.getChannel())) {
            form.setChannel(null);
            model.addAttribute("destinationError", "Choose where the money should go");
            return destination(form, model);
        }
        return "redirect:/withdrawals/amount";
    }

    // ------------------------------------------------------- 3.2b the amount

    @GetMapping("/withdrawals/amount")
    String amount(@ModelAttribute(FORM) WithdrawalForm form, Model model) {
        if (!form.hasChannel()) {
            return "redirect:/withdrawals";
        }
        return addAmountDetails(form, model);
    }

    /**
     * The {@link BindingResult} is declared and not read: {@code amount} binds
     * onto the form as a {@code BigDecimal}, and {@code 30,000} — which is what
     * the screen shows and what a customer types — is not one. Without somewhere
     * to put that failure the request is answered 400 before this method runs.
     */
    @PostMapping("/withdrawals/amount")
    String chooseAmount(
            @ModelAttribute(FORM) WithdrawalForm form,
            BindingResult errors,
            @RequestParam(name = "amount", required = false) String typed,
            Model model) {
        if (!form.hasChannel()) {
            return "redirect:/withdrawals";
        }
        form.setAmount(null);
        model.addAttribute("typedAmount", typed);

        Optional<BigDecimal> amount = Amounts.parse(typed);
        if (amount.isEmpty()) {
            return amountError(form, model, "Enter an amount, in shillings and cents");
        }

        Optional<String> refusal = tooMuch(amount.get()).or(() -> outsideLimits(form, amount.get()));
        if (refusal.isPresent()) {
            return amountError(form, model, refusal.get());
        }

        form.setAmount(amount.get());
        try {
            quote(form);
        } catch (ApiException refused) {
            return amountError(form, model, refused.getMessage());
        }
        return "redirect:/withdrawals/review";
    }

    /**
     * The one refusal a withdrawal has and a deposit does not: asking for more
     * than is there.
     *
     * <p>Said on this screen, in the balance's own figures, because the
     * alternative is a customer typing a number, tapping through a review and
     * being told at the PIN that they never had it. The ledger is what actually
     * stops an account going negative, under the row lock, and the quote below
     * checks again with the fee included.
     */
    private Optional<String> tooMuch(BigDecimal amount) {
        return accountApi.findMain(identityApi.currentCustomerId())
                .filter(account -> amount.compareTo(account.balance().amount()) > 0)
                .map(account -> "That is more than your available balance of %s. Enter a smaller amount."
                        .formatted(account.balance()));
    }

    private Optional<String> outsideLimits(WithdrawalForm form, BigDecimal amount) {
        return paymentApi.limitsFor(identityApi.currentCustomerId(), IntentType.WITHDRAWAL, form.getChannel())
                .flatMap(limits -> {
                    if (amount.compareTo(limits.minimum().amount()) < 0) {
                        return Optional.of("The smallest withdrawal is %s".formatted(limits.minimum()));
                    }
                    if (amount.compareTo(limits.maximum().amount()) > 0) {
                        return Optional.of("The largest withdrawal is %s".formatted(limits.maximum()));
                    }
                    return Optional.empty();
                });
    }

    /** Quotes the withdrawal, or re-quotes the one already in hand. */
    private void quote(WithdrawalForm form) {
        UUID customerId = identityApi.currentCustomerId();
        PaymentIntentView quoted = form.getIntentId() == null
                ? paymentApi.quoteWithdrawal(customerId, form.getChannel(), form.getAmount(), msisdnFor(form))
                : paymentApi.requoteWithdrawal(form.getIntentId(), customerId, form.getAmount());
        form.setIntentId(quoted.id());
    }

    /** M-Pesa needs the number the money is going to; an agent does not. */
    private String msisdnFor(WithdrawalForm form) {
        return form.getChannel() == PaymentChannel.MPESA
                ? customerApi.find(identityApi.currentCustomerId()).map(CustomerProfile::phone).orElse(null)
                : null;
    }

    private String amountError(WithdrawalForm form, Model model, String message) {
        model.addAttribute("amountError", message);
        return addAmountDetails(form, model);
    }

    private String addAmountDetails(WithdrawalForm form, Model model) {
        model.addAttribute("channelTitle", titleFor(form.getChannel()));
        model.addAttribute("quickAmounts", properties.withdrawalQuickAmounts());
        accountApi.findMain(identityApi.currentCustomerId())
                .ifPresent(account -> model.addAttribute("account", account));
        if (!model.containsAttribute("typedAmount")) {
            model.addAttribute("typedAmount", form.getAmount() == null ? "" : form.getAmount().toPlainString());
        }
        paymentApi.limitsFor(identityApi.currentCustomerId(), IntentType.WITHDRAWAL, form.getChannel())
                .ifPresent(limits -> model.addAttribute("limits", limits));
        return "withdraw/amount";
    }

    /** The screen says where the money is going, in the words the customer chose it by. */
    private static String titleFor(PaymentChannel channel) {
        return switch (channel) {
            case MPESA -> "Withdraw to M-Pesa";
            case AGENT -> "Withdraw at an agent";
            case CARD, INTERNAL -> throw new IllegalStateException("A withdrawal cannot go to " + channel);
        };
    }

    // ------------------------------------------------------- 3.2c the review

    @GetMapping("/withdrawals/review")
    String review(@ModelAttribute(FORM) WithdrawalForm form, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/withdrawals/amount";
        }

        UUID customerId = identityApi.currentCustomerId();
        Optional<PaymentIntentView> quoted =
                paymentApi.findIfOwned(form.getIntentId(), customerId, IntentType.WITHDRAWAL);
        if (quoted.isEmpty()) {
            form.setIntentId(null);
            return "redirect:/withdrawals/amount";
        }

        PaymentIntentView intent = quoted.get();
        model.addAttribute("intent", intent);
        model.addAttribute("destinationLabel", destinationLabelFor(form.getChannel()));
        model.addAttribute("destinationDetail", destinationDetailFor(intent));
        accountApi.find(intent.sourceAccountId()).ifPresent(account -> {
            model.addAttribute("account", account);
            model.addAttribute("accountLabel", Labels.readable(account.accountType()) + " account");
        });
        return "withdraw/review";
    }

    @PostMapping("/withdrawals/review")
    String confirmReview(@ModelAttribute(FORM) WithdrawalForm form) {
        return form.getIntentId() == null ? "redirect:/withdrawals/amount" : "redirect:/withdrawals/pin";
    }

    /** Where the money is going, as the row on the review calls it. */
    private static String destinationLabelFor(PaymentChannel channel) {
        return switch (channel) {
            case MPESA -> "M-Pesa";
            case AGENT -> "Konexio agent";
            case CARD, INTERNAL -> throw new IllegalStateException("A withdrawal cannot go to " + channel);
        };
    }

    /**
     * The number the money is going to, masked, or the code that collects it.
     * Taken from the intent rather than the customer profile: the quote is what
     * the payment module will actually pay out against.
     */
    private static String destinationDetailFor(PaymentIntentView intent) {
        if (intent.counterpartyMsisdn() != null) {
            return Masks.phone(intent.counterpartyMsisdn());
        }
        return intent.agentCode();
    }

    // ---------------------------------------------------------- 3.2d the PIN

    @GetMapping("/withdrawals/pin")
    String pin(@ModelAttribute(FORM) WithdrawalForm form, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/withdrawals/amount";
        }
        return addPinDetails(form, model);
    }

    /**
     * The PIN, the token and the confirmation, in that order and in one request.
     *
     * <p>Identity checks the PIN and mints a token bound to this withdrawal; the
     * payment module burns it inside the transaction that debits the account and
     * instructs the payout. So a confirmation that fails does not spend the
     * customer PIN entry, and a token cannot be carried to a different payment.
     *
     * <p>This is the moment the money actually leaves, which is why the balance
     * is checked again here by the ledger itself: between the quote and now,
     * another payment can have landed.
     */
    @PostMapping("/withdrawals/pin")
    String confirmWithPin(
            @ModelAttribute(FORM) WithdrawalForm form,
            @RequestParam(name = "pin", required = false) String pin,
            Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/withdrawals/amount";
        }

        String entered = pin == null ? "" : pin.trim();
        if (entered.length() != identityApi.pinLength()) {
            return pinError(form, model, "Enter all %d digits of your PIN".formatted(identityApi.pinLength()));
        }

        try {
            StepUpTokenIssued stepUp = identityApi.issueStepUp(
                    StepUpIntentType.WITHDRAWAL, form.getIntentId(), entered);
            paymentApi.confirmWithdrawal(
                    form.getIntentId(), identityApi.currentCustomerId(), stepUp.stepUpToken());
        } catch (ApiException refused) {
            return pinError(form, model, refused.getMessage());
        }
        return "redirect:/withdrawals/done";
    }

    private String pinError(WithdrawalForm form, Model model, String message) {
        model.addAttribute("pinError", message);
        return addPinDetails(form, model);
    }

    private String addPinDetails(WithdrawalForm form, Model model) {
        model.addAttribute("pinLength", identityApi.pinLength());
        model.addAttribute("destinationLabel", destinationLabelFor(form.getChannel()));
        paymentApi.findIfOwned(form.getIntentId(), identityApi.currentCustomerId(), IntentType.WITHDRAWAL)
                .ifPresent(intent -> model.addAttribute("amount", intent.amount()));
        return "withdraw/pin";
    }

    // ------------------------------------------------------ 3.2e the outcome

    /**
     * How the withdrawal ended.
     *
     * <p>The account was debited when it was confirmed, so unlike a deposit this
     * screen can show a balance and a reference straight away: both are already
     * true. What it does not claim is that the money has reached the other end —
     * that is what the payout, and later its callback, are for.
     *
     * <p>The session is finished with here: the withdrawal exists on its own now.
     */
    @GetMapping("/withdrawals/done")
    String done(@ModelAttribute(FORM) WithdrawalForm form, SessionStatus session, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/dashboard";
        }

        UUID customerId = identityApi.currentCustomerId();
        Optional<PaymentIntentView> withdrawal =
                paymentApi.findIfOwned(form.getIntentId(), customerId, IntentType.WITHDRAWAL);
        if (withdrawal.isEmpty()) {
            return "redirect:/dashboard";
        }

        PaymentIntentView intent = withdrawal.get();
        model.addAttribute("intent", intent);
        model.addAttribute("outcome", WithdrawalOutcome.of(intent));
        model.addAttribute("destinationLabel", destinationLabelFor(intent.channel()));
        model.addAttribute("destinationDetail", destinationDetailFor(intent));
        accountApi.find(intent.sourceAccountId()).ifPresent(account -> model.addAttribute("account", account));

        session.setComplete();
        return "withdraw/done";
    }
}
