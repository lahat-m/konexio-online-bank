package com.konexio.bank.site.web;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.identity.IdentityApi;
import com.konexio.bank.identity.StepUpIntentType;
import com.konexio.bank.identity.StepUpTokenIssued;
import com.konexio.bank.loan.LoanApi;
import com.konexio.bank.loan.LoanOfferView;
import com.konexio.bank.loan.LoanView;
import com.konexio.bank.shared.error.ApiException;
import com.konexio.bank.shared.money.Money;
import com.konexio.bank.site.config.SiteProperties;
import java.util.Optional;
import java.util.UUID;
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
 * Borrowing, and what is owed afterwards (docs/screens/6.1 to 6.5).
 *
 * <p>One screen shows the money, the next shows what it costs, and only then is
 * the PIN asked for — the same shape as every other flow that moves money,
 * because accepting a loan moves money too.
 *
 * <p>{@code /loans} answers two different questions depending on the customer:
 * somebody who owes nothing is shown what they could borrow, and somebody who
 * owes something is shown what they owe. An offer screen for a customer who
 * already has a loan would be an offer they cannot take.
 */
@Controller
@SessionAttributes(LoanFlowController.FORM)
class LoanFlowController {

    static final String FORM = "loanForm";

    private final IdentityApi identityApi;
    private final AccountApi accountApi;
    private final LoanApi loanApi;
    private final SiteProperties properties;

    LoanFlowController(
            IdentityApi identityApi, AccountApi accountApi, LoanApi loanApi, SiteProperties properties) {
        this.identityApi = identityApi;
        this.accountApi = accountApi;
        this.loanApi = loanApi;
        this.properties = properties;
    }

    @ModelAttribute(FORM)
    LoanForm loanForm() {
        return new LoanForm();
    }

    // --------------------------------------------------------- 6.1 the offer

    @GetMapping("/loans")
    String loans(Model model) {
        UUID customerId = identityApi.currentCustomerId();

        Optional<LoanView> outstanding = loanApi.findOutstanding(customerId);
        if (outstanding.isPresent()) {
            return "redirect:/loans/" + outstanding.get().id();
        }

        try {
            loanApi.currentOffers(customerId).stream().findFirst().ifPresent(view -> addOffer(view, model));
        } catch (ApiException nothingToOffer) {
            // Not eligible, or nothing priced to offer. The module says why — no
            // KYC, no main account, no approved pricing — and that sentence is
            // better on the screen than the 422 it would otherwise become.
            model.addAttribute("noOffer", nothingToOffer.getMessage());
        }
        return "loan/offer";
    }

    // --------------------------------------------------------- 6.2 the terms

    @GetMapping("/loans/offers/{offerId}")
    String terms(@PathVariable UUID offerId, @ModelAttribute(FORM) LoanForm form, Model model) {
        UUID customerId = identityApi.currentCustomerId();
        LoanOfferView offer = loanApi.offer(offerId, customerId);
        form.startOn(offerId);

        addOffer(offer, model);
        return "loan/terms";
    }

    @PostMapping("/loans/offers/{offerId}")
    String acceptTerms(
            @PathVariable UUID offerId,
            @ModelAttribute(FORM) LoanForm form,
            @RequestParam(name = "termsAccepted", required = false) boolean termsAccepted,
            Model model) {
        UUID customerId = identityApi.currentCustomerId();
        LoanOfferView offer = loanApi.offer(offerId, customerId);
        form.startOn(offerId);
        form.setTermsAccepted(termsAccepted);

        if (!termsAccepted) {
            // The module refuses without it too. Said here so the customer is told
            // which box, rather than shown the module's refusal.
            addOffer(offer, model);
            model.addAttribute("termsError", "Tick the box to accept the loan terms");
            return "loan/terms";
        }
        return "redirect:/loans/offers/" + offerId + "/pin";
    }

    // ----------------------------------------------------------- 6.3 the PIN

    @GetMapping("/loans/offers/{offerId}/pin")
    String pin(@PathVariable UUID offerId, @ModelAttribute(FORM) LoanForm form, Model model) {
        if (!form.isFor(offerId) || !form.isTermsAccepted()) {
            return "redirect:/loans/offers/" + offerId;
        }
        addPinDetails(loanApi.offer(offerId, identityApi.currentCustomerId()), model);
        return "loan/pin";
    }

    /**
     * The PIN, the token and the acceptance, in one request.
     *
     * <p>Identity mints a token bound to this offer and the loan module burns it
     * inside the transaction that opens the loan account, pays the principal out
     * and writes the schedule. So a token minted for one offer cannot accept
     * another, and an acceptance that fails does not spend the PIN entry.
     */
    @PostMapping("/loans/offers/{offerId}/pin")
    String acceptWithPin(
            @PathVariable UUID offerId,
            @ModelAttribute(FORM) LoanForm form,
            @RequestParam(name = "pin", required = false) String pin,
            Model model) {
        if (!form.isFor(offerId) || !form.isTermsAccepted()) {
            return "redirect:/loans/offers/" + offerId;
        }

        UUID customerId = identityApi.currentCustomerId();
        LoanOfferView offer = loanApi.offer(offerId, customerId);

        String entered = pin == null ? "" : pin.trim();
        if (entered.length() != identityApi.pinLength()) {
            return pinError(offer, model, "Enter all %d digits of your PIN".formatted(identityApi.pinLength()));
        }

        LoanView loan;
        try {
            StepUpTokenIssued stepUp =
                    identityApi.issueStepUp(StepUpIntentType.LOAN_ACCEPTANCE, offerId, entered);
            loan = loanApi.accept(
                    offerId, customerId, offer.disburseToAccountId(), true, stepUp.stepUpToken());
        } catch (ApiException refused) {
            // A wrong PIN, an offer that expired while they read it, a loan taken
            // in another tab: all the module's to decide, all written for reading.
            return pinError(offer, model, refused.getMessage());
        }
        return "redirect:/loans/" + loan.id() + "/disbursed";
    }

    // ------------------------------------------------------ 6.4 the money out

    @GetMapping("/loans/{loanId}/disbursed")
    String disbursed(
            @PathVariable UUID loanId, @ModelAttribute(FORM) LoanForm form,
            SessionStatus session, Model model) {
        UUID customerId = identityApi.currentCustomerId();
        LoanView loan = loanApi.loan(loanId, customerId);

        model.addAttribute("loan", LoanCard.of(loan, properties.zone()));
        accountApi.find(loan.disbursedToAccountId()).ifPresent(account -> {
            model.addAttribute("into", intoAccount(account));
            model.addAttribute("balance", written(account.balance()));
        });

        // The flow is over: the loan exists on its own now.
        session.setComplete();
        return "loan/disbursed";
    }

    // --------------------------------------------------- 6.5 the loan account

    @GetMapping("/loans/{loanId}")
    String loan(@PathVariable UUID loanId, Model model) {
        UUID customerId = identityApi.currentCustomerId();
        LoanView loan = loanApi.loan(loanId, customerId);

        model.addAttribute("loan", LoanCard.of(loan, properties.zone()));
        accountApi.find(loan.disbursedToAccountId())
                .ifPresent(account -> model.addAttribute("into", intoAccount(account)));
        return "loan/account";
    }

    // ----------------------------------------------------------------- shared

    private void addOffer(LoanOfferView offer, Model model) {
        model.addAttribute("offer", LoanOfferCard.of(offer));
        accountApi.find(offer.disburseToAccountId())
                .ifPresent(account -> model.addAttribute("into", intoAccount(account)));
    }

    private String pinError(LoanOfferView offer, Model model, String message) {
        model.addAttribute("pinError", message);
        addPinDetails(offer, model);
        return "loan/pin";
    }

    private void addPinDetails(LoanOfferView offer, Model model) {
        addOffer(offer, model);
        model.addAttribute("pinLength", identityApi.pinLength());
    }

    /** "Main account ••••3310", the account the money lands in. */
    private String intoAccount(AccountView account) {
        return Labels.readable(account.accountType()) + " account " + account.maskedNumber();
    }

    private static String written(Money money) {
        return money.currency() + " " + Numbers.amount(money.amount());
    }
}
