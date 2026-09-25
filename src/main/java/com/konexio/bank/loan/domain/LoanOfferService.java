package com.konexio.bank.loan.domain;

import com.konexio.bank.account.AccountApi;
import com.konexio.bank.account.AccountView;
import com.konexio.bank.customer.CustomerApi;
import com.konexio.bank.customer.CustomerProfile;
import com.konexio.bank.loan.LoanOfferView;
import com.konexio.bank.loan.LoanStatus;
import com.konexio.bank.loan.OfferStatus;
import com.konexio.bank.loan.config.LoanProperties;
import com.konexio.bank.shared.money.Money;
import java.util.HashMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a customer can borrow, and on what terms.
 *
 * <p>An offer is priced once and stored, not recomputed per request. Pricing is
 * time-versioned, so the terms a customer was shown have to survive until they
 * decide — and {@code uq_loan_offer_open} makes asking twice return the same
 * offer rather than a second one at a possibly different price.
 */
@Service
public class LoanOfferService {

    private static final List<LoanStatus> OPEN_LOANS = List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE);

    private final LoanOfferRepository offers;
    private final LoanRepository loans;
    private final LoanProductStore products;
    private final CustomerApi customerApi;
    private final AccountApi accountApi;
    private final CreditReferencePort creditReference;
    private final LoanProperties properties;

    LoanOfferService(
            LoanOfferRepository offers,
            LoanRepository loans,
            LoanProductStore products,
            CustomerApi customerApi,
            AccountApi accountApi,
            CreditReferencePort creditReference,
            LoanProperties properties) {
        this.offers = offers;
        this.loans = loans;
        this.products = products;
        this.customerApi = customerApi;
        this.accountApi = accountApi;
        this.creditReference = creditReference;
        this.properties = properties;
    }

    /**
     * The offers on the table right now (screen 6.1).
     *
     * <p>Reading this makes offers, because an offer has to exist as a row before
     * it can be accepted. It is safe to call repeatedly: an open offer is
     * returned as it stands, and the partial unique index is what guarantees a
     * second one is never created alongside it.
     *
     * @throws LoanExceptions.NotEligible (422) with a reason, when there is
     *     nothing this customer could be offered
     */
    @Transactional
    public List<LoanOfferView> currentOffers(UUID customerId) {
        AccountView mainAccount = requireEligible(customerId);

        List<LoanProductStore.PricedProduct> priced = products.pricedProducts();
        if (priced.isEmpty()) {
            throw new LoanExceptions.NotEligible("NO_PRODUCTS_AVAILABLE",
                    "There are no loan products available at the moment.");
        }

        Map<UUID, LoanOffer> open = expireStale(offers.findByCustomerIdAndStatus(customerId, OfferStatus.OFFERED));
        // Flush the expiries before pricing anything new. Hibernate orders a
        // flush inserts-first, so without this the replacement offer is inserted
        // while the stale one is still OFFERED, and uq_loan_offer_open refuses it.
        offers.flush();

        List<LoanOfferView> current = new ArrayList<>(priced.size());
        for (LoanProductStore.PricedProduct product : priced) {
            LoanOffer offer = open.get(product.id());
            if (offer == null) {
                offer = offers.saveAndFlush(price(customerId, mainAccount, product));
            }
            current.add(toView(offer, product));
        }
        return current;
    }

    /**
     * One offer, by id (screen 6.2).
     *
     * <p>{@code noRollbackFor} on the expiry: the offer is marked EXPIRED on the
     * way past, and that mark is the point — it is what stops the list showing a
     * dead offer. Letting the 410 roll it back would mean the same offer expiring
     * again on every read.
     *
     * @throws LoanExceptions.OfferExpired (410) when its window has passed
     */
    @Transactional(noRollbackFor = LoanExceptions.OfferExpired.class)
    public LoanOfferView offer(UUID offerId, UUID customerId) {
        LoanOffer offer = offers.findByIdAndCustomerId(offerId, customerId)
                .orElseThrow(LoanExceptions.OfferNotFound::new);
        if (offer.isExpired(Instant.now())) {
            offer.markExpired();
            throw new LoanExceptions.OfferExpired();
        }
        return toView(offer, products.findPriced(offer.getProductId()).orElse(null));
    }

    /**
     * The rules a customer has to meet before anything is priced for them.
     *
     * <p>All four are the bank's own, not the database's — nothing here is
     * enforced by a constraint, so this is the only place they hold. The last is:
     * {@code uq_loan_one_open_per_customer} refuses a second open loan whatever
     * this says.
     *
     * @return the account a loan would be paid into
     */
    private AccountView requireEligible(UUID customerId) {
        CustomerProfile profile = customerApi.ensureProfile(customerId);
        if (!profile.isActive()) {
            throw new LoanExceptions.NotEligible("PROFILE_NOT_ACTIVE",
                    "This profile is %s and cannot borrow.".formatted(profile.status()));
        }
        if (!profile.isKycVerified()) {
            throw new LoanExceptions.NotEligible("KYC_INCOMPLETE",
                    "Your identity check is not complete, so a loan cannot be offered yet.");
        }
        if (loans.existsByCustomerIdAndStatusIn(customerId, OPEN_LOANS)) {
            throw new LoanExceptions.NotEligible("ACTIVE_LOAN_EXISTS",
                    "You already have a loan to repay. Settle it before taking another.");
        }
        return accountApi.findMain(customerId)
                .filter(account -> account.status().isOpen())
                .orElseThrow(() -> new LoanExceptions.NotEligible("NO_MAIN_ACCOUNT",
                        "A loan is paid into your main account, and you do not have an open one."));
    }

    /**
     * Prices one product for one customer, recording what the bureau said at that
     * moment.
     *
     * <p>Interest is a flat rate over the term, rounded half-up to the currency's
     * two places: the customer is quoted a number, and the number they are quoted
     * is the number that is stored.
     */
    private LoanOffer price(UUID customerId, AccountView mainAccount, LoanProductStore.PricedProduct product) {
        CreditReferencePort.CreditStanding standing = creditReference.check(customerId);
        if (standing.score() < properties.minimumCreditScore()) {
            throw new LoanExceptions.NotEligible("CREDIT_SCORE_TOO_LOW",
                    "Your credit score is below what this product requires.");
        }

        BigDecimal interest = product.principal()
                .multiply(product.interestRate())
                .setScale(2, RoundingMode.HALF_UP);
        LocalDate dueDate = LocalDate.now(ZoneOffset.UTC).plusDays(product.termDays());

        return new LoanOffer(
                customerId,
                product.id(),
                mainAccount.id(),
                product.principal(),
                interest,
                product.processingFee(),
                product.currency(),
                dueDate,
                standing.score(),
                standing.reference(),
                Instant.now().plusSeconds(product.offerTtlSeconds()));
    }

    /** Marks anything past its window EXPIRED, and returns what is left, by product. */
    private Map<UUID, LoanOffer> expireStale(List<LoanOffer> open) {
        Instant now = Instant.now();
        Map<UUID, LoanOffer> live = new HashMap<>();
        for (LoanOffer offer : open) {
            if (offer.isExpired(now)) {
                offer.markExpired();
            } else {
                live.put(offer.getProductId(), offer);
            }
        }
        return live;
    }

    static LoanOfferView toView(LoanOffer offer, LoanProductStore.PricedProduct product) {
        String currency = offer.getCurrency();
        return new LoanOfferView(
                offer.getId(),
                product == null ? null : product.code(),
                product == null ? null : product.name(),
                offer.getDisburseToAccountId(),
                Money.of(offer.getPrincipal(), currency),
                Money.of(offer.getInterestAmount(), currency),
                Money.of(offer.getProcessingFee(), currency),
                Money.of(offer.totalRepayable(), currency),
                offer.getDueDate(),
                product == null ? 0 : product.termDays(),
                offer.getStatus(),
                offer.getExpiresAt());
    }
}
