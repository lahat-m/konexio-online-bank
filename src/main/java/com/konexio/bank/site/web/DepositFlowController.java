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
import com.konexio.bank.payment.TransactionLimits;
import com.konexio.bank.shared.error.ApiException;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.shared.util.Masks;
import com.konexio.bank.site.config.SiteProperties;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;

/**
 * Paying money in (docs/screens/3.1a to 3.1e).
 *
 * <p>Named for the flow rather than the noun because the payment module already
 * has a {@code DepositController} serving {@code /api/deposits}: two classes of
 * the same name in one Spring context are two beans with one name, and the
 * application refuses to start.
 *
 * <p>One screen per decision, in the order the customer makes them: where the
 * money comes from, how much, does this look right, prove it is you. Nothing is
 * created until the review is confirmed, so a customer who changes their mind on
 * the amount screen leaves nothing behind in the payment module.
 *
 * <p>The channel is not this module's to define. {@code MPESA}, {@code CARD} and
 * {@code AGENT} are the payment module's, each with its own clearing account, so
 * the screen offers exactly those and refuses anything else rather than inventing
 * a fourth.
 */
@Controller
@SessionAttributes(DepositFlowController.FORM)
class DepositFlowController {

    static final String FORM = "deposit";

    private final IdentityApi identityApi;
    private final CustomerApi customerApi;
    private final AccountApi accountApi;
    private final PaymentApi paymentApi;
    private final SiteProperties properties;

    DepositFlowController(
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
    DepositForm depositForm() {
        return new DepositForm();
    }

    // ------------------------------------------------------- 3.1a the source

    @GetMapping("/deposits")
    String source(@ModelAttribute(FORM) DepositForm form, Model model) {
        UUID customerId = identityApi.currentCustomerId();
        Optional<AccountView> main = accountApi.findMain(customerId);
        if (main.isEmpty()) {
            // Nothing to pay into. The home screen says so and offers the way out.
            return "redirect:/dashboard";
        }

        model.addAttribute("account", main.get());
        model.addAttribute("phoneMasked", customerApi.find(customerId)
                .map(profile -> Masks.phone(profile.phone()))
                .orElse(null));
        model.addAttribute("channel", form.hasChannel() ? form.getChannel() : PaymentChannel.MPESA);
        return "deposit/source";
    }


    // ------------------------------------------------------- 3.1b the amount

    @GetMapping("/deposits/amount")
    String amount(@ModelAttribute(FORM) DepositForm form, Model model) {
        if (!form.hasChannel()) {
            return "redirect:/deposits";
        }
        addAmountDetails(form, model);
        return "deposit/amount";
    }

    /**
     * The {@link BindingResult} is declared and not read, deliberately. The field
     * is called {@code amount} and so is the form's, so Spring tries to bind it
     * to a {@code BigDecimal} — and {@code 5,000}, which is what the screen shows
     * and what a customer types, is not one. Without somewhere to put that
     * failure the request is answered 400 before this method runs. The value the
     * binder managed is thrown away either way: {@link Amounts} decides, because
     * it knows a comma is not a decimal point.
     */
    @PostMapping("/deposits/amount")
    String chooseAmount(
            @ModelAttribute(FORM) DepositForm form,
            BindingResult errors,
            @RequestParam(name = "amount", required = false) String typed,
            Model model) {
        if (!form.hasChannel()) {
            return "redirect:/deposits";
        }
        form.setAmount(null);
        model.addAttribute("typedAmount", typed);

        Optional<BigDecimal> amount = Amounts.parse(typed);
        if (amount.isEmpty()) {
            return amountError(form, model, "Enter an amount, in shillings and cents");
        }

        Optional<String> refusal = outsideLimits(form, amount.get());
        if (refusal.isPresent()) {
            return amountError(form, model, refusal.get());
        }

        form.setAmount(amount.get());
        try {
            quote(form);
        } catch (ApiException refused) {
            // The payment module has the last word on limits and on anything else
            // that makes a deposit impossible. Its message is written for the
            // customer, and belongs on the screen holding the number it refused.
            return amountError(form, model, refused.getMessage());
        }
        return "redirect:/deposits/review";
    }

    /**
     * Quotes the deposit, or re-quotes the one already in hand.
     *
     * <p>A customer who comes back from the review to change the amount should
     * end up with the same deposit for a different number, not a second quote
     * left behind for a job to expire.
     */
    private void quote(DepositForm form) {
        UUID customerId = identityApi.currentCustomerId();
        PaymentIntentView quoted = form.getIntentId() == null
                ? paymentApi.quoteDeposit(customerId, form.getChannel(), form.getAmount(), msisdnFor(form))
                : paymentApi.requoteDeposit(form.getIntentId(), customerId, form.getAmount());
        form.setIntentId(quoted.id());
    }

    /** M-Pesa needs the number the money comes from; the other channels do not. */
    private String msisdnFor(DepositForm form) {
        return form.getChannel() == PaymentChannel.MPESA
                ? customerApi.find(identityApi.currentCustomerId()).map(CustomerProfile::phone).orElse(null)
                : null;
    }

    // ------------------------------------------------------- 3.1c the review

    @GetMapping("/deposits/review")
    String review(@ModelAttribute(FORM) DepositForm form, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/deposits/amount";
        }

        Optional<PaymentIntentView> quoted = paymentApi.findIfOwned(
                form.getIntentId(), identityApi.currentCustomerId(), IntentType.DEPOSIT);
        if (quoted.isEmpty()) {
            // Expired, cancelled, or never the caller's. Either way there is
            // nothing to confirm, so the flow starts again rather than showing a
            // quote that cannot be acted on.
            form.setIntentId(null);
            return "redirect:/deposits/amount";
        }

        model.addAttribute("intent", quoted.get());
        model.addAttribute("channelTitle", titleFor(form.getChannel()));
        model.addAttribute("sourceLabel", sourceLabelFor(form.getChannel()));
        model.addAttribute("sourceDetail", sourceDetailFor(form.getChannel()));
        model.addAttribute("prompt", promptFor(form.getChannel()));
        // A deposit's destination is the account it lands in.
        accountApi.find(quoted.get().destinationAccountId()).ifPresent(account -> {
            model.addAttribute("account", account);
            model.addAttribute("accountLabel", Labels.readable(account.accountType()) + " account");
        });
        return "deposit/review";
    }

    @PostMapping("/deposits/review")
    String confirmReview(@ModelAttribute(FORM) DepositForm form) {
        return form.getIntentId() == null ? "redirect:/deposits/amount" : "redirect:/deposits/pin";
    }

    /** What the money is coming from, as the row on the review calls it. */
    private static String sourceLabelFor(PaymentChannel channel) {
        return switch (channel) {
            case MPESA -> "M-Pesa";
            case CARD -> "Debit card";
            case AGENT -> "Konexio agent";
            case INTERNAL -> throw new IllegalStateException("A deposit cannot arrive by internal transfer");
        };
    }

    private String sourceDetailFor(PaymentChannel channel) {
        return channel == PaymentChannel.MPESA
                ? customerApi.find(identityApi.currentCustomerId())
                        .map(profile -> Masks.phone(profile.phone()))
                        .orElse(null)
                : null;
    }

    /**
     * What happens after Confirm, said before it is pressed. Each channel asks
     * something different of the customer, and finding that out afterwards is how
     * a deposit gets abandoned halfway.
     */
    private static String promptFor(PaymentChannel channel) {
        return switch (channel) {
            case MPESA -> "You'll get an M-Pesa prompt on your phone. Enter your M-Pesa PIN there to approve.";
            case CARD -> "You'll be taken to your bank to approve the card payment.";
            case AGENT -> "You'll get a code to show the agent with your cash.";
            case INTERNAL -> throw new IllegalStateException("A deposit cannot arrive by internal transfer");
        };
    }

    /**
     * Checks the amount against the same band the screen states, so a number
     * outside it is refused here rather than two screens later. The payment
     * module checks again when the intent is created, which is the check that
     * counts — this one exists so the customer hears about it while their
     * fingers are still on the number.
     */
    private Optional<String> outsideLimits(DepositForm form, BigDecimal amount) {
        return paymentApi.limitsFor(identityApi.currentCustomerId(), IntentType.DEPOSIT, form.getChannel())
                .flatMap(limits -> {
                    if (amount.compareTo(limits.minimum().amount()) < 0) {
                        return Optional.of("The smallest deposit is %s".formatted(limits.minimum()));
                    }
                    if (amount.compareTo(limits.maximum().amount()) > 0) {
                        return Optional.of("The largest deposit is %s".formatted(limits.maximum()));
                    }
                    return Optional.empty();
                });
    }

    private String amountError(DepositForm form, Model model, String message) {
        model.addAttribute("amountError", message);
        addAmountDetails(form, model);
        return "deposit/amount";
    }

    /**
     * The band comes from the payment module, and is stated only if it exists:
     * limits are policy and a fresh database has none, so an unconfigured
     * environment says nothing rather than promising a range nobody approved.
     */
    private void addAmountDetails(DepositForm form, Model model) {
        model.addAttribute("channelTitle", titleFor(form.getChannel()));
        model.addAttribute("quickAmounts", properties.depositQuickAmounts());
        if (!model.containsAttribute("typedAmount")) {
            model.addAttribute("typedAmount", form.getAmount() == null ? "" : form.getAmount().toPlainString());
        }
        Optional<TransactionLimits> limits =
                paymentApi.limitsFor(identityApi.currentCustomerId(), IntentType.DEPOSIT, form.getChannel());
        limits.ifPresent(band -> model.addAttribute("limits", band));
    }

    /** The screen says where the money is coming from, in the words the customer chose it by. */
    private static String titleFor(PaymentChannel channel) {
        return switch (channel) {
            case MPESA -> "Deposit from M-Pesa";
            case CARD -> "Deposit by card";
            case AGENT -> "Deposit at an agent";
            case INTERNAL -> throw new IllegalStateException("A deposit cannot arrive by internal transfer");
        };
    }


    // ---------------------------------------------------------- 3.1d the PIN

    @GetMapping("/deposits/pin")
    String pin(@ModelAttribute(FORM) DepositForm form, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/deposits/amount";
        }
        return addPinDetails(form, model);
    }

    /**
     * The PIN, the token and the confirmation, in that order and in one request.
     *
     * <p>Identity checks the PIN and mints a token bound to this intent; the
     * payment module burns that token inside the transaction that confirms the
     * deposit. So a confirmation that fails does not spend the customer's PIN
     * entry, and a token cannot be carried to a different payment.
     *
     * <p>Every refusal along that path — wrong PIN, locked credential, an intent
     * that is no longer pending — arrives as an {@link ApiException} with a
     * message written for the customer, and is shown on this screen rather than
     * turned into a failure page.
     */
    @PostMapping("/deposits/pin")
    String confirmWithPin(
            @ModelAttribute(FORM) DepositForm form,
            @RequestParam(name = "pin", required = false) String pin,
            Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/deposits/amount";
        }

        String entered = pin == null ? "" : pin.trim();
        if (entered.length() != identityApi.pinLength()) {
            return pinError(form, model, "Enter all %d digits of your PIN".formatted(identityApi.pinLength()));
        }

        try {
            StepUpTokenIssued stepUp = identityApi.issueStepUp(
                    StepUpIntentType.DEPOSIT, form.getIntentId(), entered);
            paymentApi.confirmDeposit(form.getIntentId(), identityApi.currentCustomerId(), stepUp.stepUpToken());
        } catch (ApiException refused) {
            return pinError(form, model, refused.getMessage());
        }
        return "redirect:/deposits/done";
    }

    private String pinError(DepositForm form, Model model, String message) {
        model.addAttribute("pinError", message);
        return addPinDetails(form, model);
    }

    private String addPinDetails(DepositForm form, Model model) {
        model.addAttribute("pinLength", identityApi.pinLength());
        model.addAttribute("amount", form.getAmount() == null
                ? null
                : Money.of(form.getAmount(), currency()));
        accountApi.findMain(identityApi.currentCustomerId()).ifPresent(account ->
                model.addAttribute("accountLabel", Labels.readable(account.accountType()) + " account"));
        return "deposit/pin";
    }

    private String currency() {
        return accountApi.findMain(identityApi.currentCustomerId())
                .map(account -> account.balance().currency())
                .orElse("KES");
    }


    // ------------------------------------------------------ 3.1e the outcome

    /**
     * How the deposit ended, which is not always "successfully" — and the screen
     * says which.
     *
     * <p>Every deposit channel is asynchronous: confirming asks M-Pesa, the card
     * network or an agent for the money, and the answer arrives later as a
     * callback. So a deposit is {@code PROCESSING} the moment this screen is
     * first drawn, and telling the customer their money has arrived would be
     * false — the balance behind it would still be the old one. The screen
     * reports the state it finds and reloading it shows the state as it is then.
     *
     * <p>The session is finished with here: the deposit exists on its own now,
     * and a refresh should not be able to walk back into a flow that is over.
     */
    @GetMapping("/deposits/done")
    String done(@ModelAttribute(FORM) DepositForm form, SessionStatus session, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/dashboard";
        }

        UUID customerId = identityApi.currentCustomerId();
        Optional<PaymentIntentView> deposit =
                paymentApi.findIfOwned(form.getIntentId(), customerId, IntentType.DEPOSIT);
        if (deposit.isEmpty()) {
            return "redirect:/dashboard";
        }

        PaymentIntentView intent = deposit.get();
        model.addAttribute("intent", intent);
        model.addAttribute("outcome", DepositOutcome.of(intent));
        model.addAttribute("sourceLabel", sourceLabelFor(intent.channel()));
        model.addAttribute("moment", Moments.dateAndTime(
                intent.completedAt() == null ? intent.confirmedAt() : intent.completedAt(), properties.zone()));
        accountApi.find(intent.destinationAccountId()).ifPresent(account -> {
            model.addAttribute("account", account);
            model.addAttribute("accountLabel", Labels.readable(account.accountType()) + " account");
        });

        session.setComplete();
        return "deposit/done";
    }

    /**
     * The channel binds straight onto the session form, so a value the enum does
     * not have is a binding error rather than something to parse by hand — and
     * the {@link BindingResult} has to be declared, or Spring answers 400 before
     * this method runs and the customer sees a blank error page instead of the
     * screen they were on.
     *
     * <p>An unrecognised channel is a refusal, not a default. The three on the
     * screen are the three the payment module can clear; quietly picking one for
     * a request that named something else would deposit through a route the
     * customer did not choose.
     */
    @PostMapping("/deposits")
    String chooseSource(@ModelAttribute(FORM) DepositForm form, BindingResult errors, Model model) {
        // INTERNAL is a channel, but not one money can be paid in through: it is
        // the transfer between two customer accounts, and has no clearing account
        // for the other leg. So it is refused here rather than reaching a service
        // that would refuse it less kindly.
        if (errors.hasErrors() || !form.hasChannel() || form.getChannel().isInternal()) {
            form.setChannel(null);
            model.addAttribute("sourceError", "Choose where the money is coming from");
            return source(form, model);
        }
        return "redirect:/deposits/amount";
    }
}
