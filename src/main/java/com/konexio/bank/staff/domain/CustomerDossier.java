package com.konexio.bank.staff.domain;

import com.konexio.bank.account.AccountView;
import com.konexio.bank.customer.CustomerProfile;
import java.util.List;

/**
 * Everything the bank holds for one customer on a single screen: the profile and
 * every account, closed ones included.
 *
 * <p>Assembled here rather than by the client making three calls, because the
 * first thing anyone in support does is all three of them.
 */
public record CustomerDossier(CustomerProfile profile, List<AccountView> accounts) {

    public CustomerDossier {
        accounts = List.copyOf(accounts);
    }
}
