package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.identity.StepUpTokenIssued;
import com.konexio.bank.payment.IntentType;
import com.konexio.bank.payment.PaymentApi;
import com.konexio.bank.payment.PaymentIntentView;
import com.konexio.bank.payment.RecipientView;
import com.konexio.bank.shared.error.ApiException;
import com.konexio.bank.shared.money.Money;
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
 * Sending money to another Konexio customer (docs/screens/3.3a onwards).
 *
 * <p>The one flow that starts by naming a person rather than an amount, because
 * the mistake it has to prevent is different: a deposit sent to the wrong
 * channel bounces, and a transfer sent to the wrong account number arrives —
 * correctly, at somebody else. So the account number is checked and the name
 * shown back before anything else is asked.
 *
 * <p>That name comes from the payment module masked ({@code GRACE MU***}) and
 * rate limited, because "who owns this number" is exactly the question somebody
 * sweeping account numbers wants answered.
 */
@Controller
@SessionAttributes(TransferFlowController.FORM)
class TransferFlowController {

    static final String FORM = "transfer";

    /** Enough to recognise a habit, few enough to fit under the field. */
    private static final int RECENT_RECIPIENTS = 5;

    /**
     * What {@code payment.payment_intent} will hold and what the API validates.
     * Enforced here too, so an over-long note is a sentence under the field
     * rather than a constraint violation on the way to the database.
     */
    private static final int NOTE_LIMIT = 140;

    private final IdentityApi identityApi;
    private final AccountApi accountApi;
    private final PaymentApi paymentApi;
    private final SiteProperties properties;

    TransferFlowController(
            IdentityApi identityApi, AccountApi accountApi, PaymentApi paymentApi, SiteProperties properties) {
        this.identityApi = identityApi;
        this.accountApi = accountApi;
        this.paymentApi = paymentApi;
        this.properties = properties;
    }

    @ModelAttribute(FORM)
    TransferForm transferForm() {
        return new TransferForm();
    }

    // ---------------------------------------------------- 3.3a the recipient

    @GetMapping("/transfers")
    String recipient(@ModelAttribute(FORM) TransferForm form, Model model) {
        UUID customerId = identityApi.currentCustomerId();
        if (accountApi.findMain(customerId).isEmpty()) {
            return "redirect:/dashboard";
        }
        return addRecipientDetails(form, model);
    }

    @PostMapping("/transfers")
    String findRecipient(
            @ModelAttribute(FORM) TransferForm form,
            @RequestParam(name = "accountNumber", required = false) String typed,
            Model model) {
        UUID customerId = identityApi.currentCustomerId();
        model.addAttribute("typedAccountNumber", typed);

        String accountNumber = AccountNumbers.parse(typed).orElse(null);
        if (accountNumber == null) {
            return recipientError(form, model, "A Konexio account number is 12 digits");
        }
        if (isTheirOwn(accountNumber, customerId)) {
            return recipientError(form, model, "That is your own account. Enter the account you are sending to.");
        }

        try {
            RecipientView recipient = paymentApi.findRecipient(accountNumber, customerId);
            form.setRecipientAccountNumber(accountNumber);
            form.setRecipientName(recipient.maskedName());
            form.setRecipientMaskedAccountNumber(recipient.maskedAccountNumber());
            form.setFirstTimeRecipient(recipient.firstTimeRecipient());
        } catch (ApiException refused) {
            // Not found, or asked too often. Both are the payment module's answer
            // and both are written for the customer.
            return recipientError(form, model, refused.getMessage());
        }
        return "redirect:/transfers/confirm";
    }

    /**
     * Sending to yourself is not a transfer, and the ledger would refuse it as an
     * entry with the same account on both legs. Caught here so the customer is
     * told plainly rather than being shown their own masked name back.
     */
    private boolean isTheirOwn(String accountNumber, UUID customerId) {
        return accountApi.findByAccountNumber(accountNumber)
                .filter(account -> account.isOwnedBy(customerId))
                .isPresent();
    }

    private String recipientError(TransferForm form, Model model, String message) {
        model.addAttribute("recipientError", message);
        return addRecipientDetails(form, model);
    }

    // ------------------------------------------------ 3.3b the name check

    /**
     * The name, shown back before an amount is ever asked for.
     *
     * <p>Reached only through a lookup: without a recipient on the form there is
     * nothing to confirm, so a direct visit — or a refresh after the session was
     * cleared — starts again at the account number rather than drawing an empty
     * card.
     */
    @GetMapping("/transfers/confirm")
    String confirmRecipient(@ModelAttribute(FORM) TransferForm form, Model model) {
        if (!form.hasRecipient()) {
            return "redirect:/transfers";
        }
        model.addAttribute("recipient", RecipientCard.of(form));
        return "transfer/confirm";
    }

    /**
     * "Yes, send to Joseph". Nothing is written here: the answer is kept on the
     * form and the amount is asked for next, because a transfer that has not been
     * quoted yet is only an intention.
     */
    @PostMapping("/transfers/confirm")
    String acceptRecipient(@ModelAttribute(FORM) TransferForm form) {
        if (!form.hasRecipient()) {
            return "redirect:/transfers";
        }
        return "redirect:/transfers/amount";
    }

    // ---------------------------------------------------------- 3.3c how much

    @GetMapping("/transfers/amount")
    String amount(@ModelAttribute(FORM) TransferForm form, Model model) {
        if (!form.hasRecipient()) {
            return "redirect:/transfers";
        }
        return addAmountDetails(form, model);
    }

    /**
     * The {@link BindingResult} is declared and not read, for the reason the
     * withdrawal flow gives: {@code amount} binds onto the form as a
     * {@code BigDecimal}, and {@code 3,500} — which is what the screen shows and
     * what a customer types — is not one. Without somewhere to put that failure
     * the request is answered 400 before this method runs.
     */
    @PostMapping("/transfers/amount")
    String chooseAmount(
            @ModelAttribute(FORM) TransferForm form,
            BindingResult errors,
            @RequestParam(name = "amount", required = false) String typedAmount,
            @RequestParam(name = "note", required = false) String typedNote,
            Model model) {
        if (!form.hasRecipient()) {
            return "redirect:/transfers";
        }
        form.setAmount(null);
        model.addAttribute("typedAmount", typedAmount);

        // Kept whatever else fails, so a rejected amount does not also cost the
        // customer the note they typed above it.
        String note = typedNote == null || typedNote.isBlank() ? null : typedNote.trim();
        form.setNote(note);

        if (note != null && note.length() > NOTE_LIMIT) {
            return amountError(form, model, "A note can be up to %d characters".formatted(NOTE_LIMIT));
        }

        Optional<BigDecimal> amount = Amounts.parse(typedAmount);
        if (amount.isEmpty()) {
            return amountError(form, model, "Enter an amount, in shillings and cents");
        }
        Optional<String> tooMuch = tooMuch(amount.get());
        if (tooMuch.isPresent()) {
            return amountError(form, model, tooMuch.get());
        }

        form.setAmount(amount.get());
        try {
            quote(form);
        } catch (ApiException refused) {
            // The limits, the closed account, the balance with the fee added: all
            // the payment module's to enforce, and all already written for a
            // customer to read.
            return amountError(form, model, refused.getMessage());
        }
        return "redirect:/transfers/review";
    }

    /**
     * Asking for more than is there, said on this screen in the balance's own
     * figures rather than three screens later at the PIN.
     *
     * <p>The ledger is what actually stops an account going negative, under the
     * row lock, and the quote below checks again with the fee included.
     */
    private Optional<String> tooMuch(BigDecimal amount) {
        return accountApi.findMain(identityApi.currentCustomerId())
                .filter(account -> amount.compareTo(account.balance().amount()) > 0)
                .map(account -> "That is more than your available balance of %s. Enter a smaller amount."
                        .formatted(written(account.balance())));
    }

    /**
     * {@code KES 24,500.00}, grouped as the screen writes every other figure.
     * {@link Money#toString()} does not group, and a balance shown as
     * {@code KES 24500.00} beside a field that groups what is typed into it reads
     * like two different numbers.
     */
    private static String written(Money money) {
        return money.currency() + " " + Numbers.amount(money.amount());
    }

    /** Quotes the transfer, or re-quotes the one already in hand. */
    private void quote(TransferForm form) {
        UUID customerId = identityApi.currentCustomerId();
        PaymentIntentView quoted = form.getIntentId() == null
                ? paymentApi.quoteTransfer(
                        customerId, form.getRecipientAccountNumber(), form.getAmount(), form.getNote())
                : paymentApi.requoteTransfer(form.getIntentId(), customerId, form.getAmount(), form.getNote());
        form.setIntentId(quoted.id());
    }

    private String amountError(TransferForm form, Model model, String message) {
        model.addAttribute("amountError", message);
        return addAmountDetails(form, model);
    }

    private String addAmountDetails(TransferForm form, Model model) {
        model.addAttribute("recipient", RecipientCard.of(form));
        model.addAttribute("quickAmounts", properties.transferQuickAmounts());
        model.addAttribute("noteLimit", NOTE_LIMIT);
        accountApi.findMain(identityApi.currentCustomerId())
                .ifPresent(account -> {
                    model.addAttribute("account", account);
                    model.addAttribute("available", written(account.balance()));
                });
        if (!model.containsAttribute("typedAmount")) {
            model.addAttribute("typedAmount", form.getAmount() == null ? "" : form.getAmount().toPlainString());
        }
        return "transfer/amount";
    }

    // --------------------------------------------------------- 3.3d the review

    /**
     * The whole transfer in one place, from the quote rather than from the form:
     * the fee and the balance afterwards are the payment module's figures, and
     * this screen is the last chance to read them before the PIN.
     */
    @GetMapping("/transfers/review")
    String review(@ModelAttribute(FORM) TransferForm form, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/transfers/amount";
        }

        UUID customerId = identityApi.currentCustomerId();
        Optional<PaymentIntentView> quoted =
                paymentApi.findIfOwned(form.getIntentId(), customerId, IntentType.TRANSFER);
        if (quoted.isEmpty()) {
            // Expired, or never theirs. Either way there is nothing to review, and
            // the amount screen will quote again.
            form.setIntentId(null);
            return "redirect:/transfers/amount";
        }

        PaymentIntentView intent = quoted.get();
        model.addAttribute("recipient", RecipientCard.of(form));
        model.addAttribute("amount", written(intent.amount()));
        // The mock's button reads "Send KES 3,500". It keeps the cents here: this
        // is the last figure before the PIN, and 3,500.50 sent as "3,500" is the
        // kind of rounding a customer notices afterwards.
        model.addAttribute("sendLabel", "Send " + written(intent.amount()));
        model.addAttribute("fee", written(intent.fee()));
        model.addAttribute("balanceAfter", written(intent.quotedBalanceAfter()));
        model.addAttribute("note", intent.note());
        accountApi.find(intent.sourceAccountId()).ifPresent(account -> {
            model.addAttribute("account", account);
            model.addAttribute("accountLabel", Labels.readable(account.accountType()) + " account");
        });
        return "transfer/review";
    }

    @PostMapping("/transfers/review")
    String confirmReview(@ModelAttribute(FORM) TransferForm form) {
        return form.getIntentId() == null ? "redirect:/transfers/amount" : "redirect:/transfers/pin";
    }

    // ------------------------------------------------------------ 3.3e the PIN

    @GetMapping("/transfers/pin")
    String pin(@ModelAttribute(FORM) TransferForm form, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/transfers/amount";
        }
        return addPinDetails(form, model);
    }

    /**
     * The PIN, the token and the confirmation, in that order and in one request.
     *
     * <p>Identity checks the PIN and mints a token bound to this transfer; the
     * payment module burns it inside the transaction that moves the money. So a
     * confirmation that fails does not spend the customer's PIN entry, and a
     * token cannot be carried to a different payment.
     *
     * <p>This is the moment the money actually leaves. The ledger checks the
     * balance again here under the row lock, because between the review and now
     * another payment can have gone out.
     */
    @PostMapping("/transfers/pin")
    String confirmWithPin(
            @ModelAttribute(FORM) TransferForm form,
            @RequestParam(name = "pin", required = false) String pin,
            Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/transfers/amount";
        }

        String entered = pin == null ? "" : pin.trim();
        if (entered.length() != identityApi.pinLength()) {
            return pinError(form, model, "Enter all %d digits of your PIN".formatted(identityApi.pinLength()));
        }

        try {
            StepUpTokenIssued stepUp =
                    identityApi.issueStepUp(StepUpIntentType.TRANSFER, form.getIntentId(), entered);
            paymentApi.confirmTransfer(form.getIntentId(), identityApi.currentCustomerId(), stepUp.stepUpToken());
        } catch (ApiException refused) {
            // A wrong PIN, a burned token, a balance that moved underneath: all
            // said here, with the keypad still on screen to try again.
            return pinError(form, model, refused.getMessage());
        }
        return "redirect:/transfers/done";
    }

    private String pinError(TransferForm form, Model model, String message) {
        model.addAttribute("pinError", message);
        return addPinDetails(form, model);
    }

    private String addPinDetails(TransferForm form, Model model) {
        model.addAttribute("pinLength", identityApi.pinLength());
        model.addAttribute("recipient", RecipientCard.of(form));
        paymentApi.findIfOwned(form.getIntentId(), identityApi.currentCustomerId(), IntentType.TRANSFER)
                .ifPresent(intent -> {
                    model.addAttribute("amount", written(intent.amount()));
                    model.addAttribute("sendLabel", "Send " + written(intent.amount()));
                });
        return "transfer/pin";
    }

    // -------------------------------------------------------- 3.3f the receipt

    /**
     * How the transfer ended.
     *
     * <p>The money moved when the PIN was confirmed — one ledger entry, both
     * accounts — so this screen can show a balance, a reference and a time that
     * are all already true, and can say the recipient has it rather than that it
     * is on its way.
     *
     * <p>The session is finished with here: the transfer exists on its own now,
     * and leaving a spent quote on it only gives a refresh something stale to
     * find.
     */
    @GetMapping("/transfers/done")
    String done(@ModelAttribute(FORM) TransferForm form, SessionStatus session, Model model) {
        if (form.getIntentId() == null) {
            return "redirect:/dashboard";
        }

        UUID customerId = identityApi.currentCustomerId();
        Optional<PaymentIntentView> transfer =
                paymentApi.findIfOwned(form.getIntentId(), customerId, IntentType.TRANSFER);
        if (transfer.isEmpty()) {
            return "redirect:/dashboard";
        }

        PaymentIntentView intent = transfer.get();
        model.addAttribute("intent", intent);
        model.addAttribute("outcome", TransferOutcome.of(intent));
        model.addAttribute("recipient", RecipientCard.of(form));
        model.addAttribute("amount", written(intent.amount()));
        model.addAttribute("sentAt", Moments.dateAndTime(intent.completedAt(), properties.zone()));
        accountApi.find(intent.sourceAccountId())
                .ifPresent(account -> model.addAttribute("balanceNow", written(account.balance())));

        session.setComplete();
        return "transfer/done";
    }

    private String addRecipientDetails(TransferForm form, Model model) {
        model.addAttribute("recent", paymentApi
                .recentRecipients(identityApi.currentCustomerId(), RECENT_RECIPIENTS)
                .stream()
                .map(RecentPerson::of)
                .toList());
        if (!model.containsAttribute("typedAccountNumber")) {
            model.addAttribute("typedAccountNumber",
                    Numbers.grouped(form.getRecipientAccountNumber()));
        }
        return "transfer/recipient";
    }
}
