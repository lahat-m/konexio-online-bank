package com.konexio.bank.site.web;

import com.konexio.bank.payment.RecentRecipient;

/**
 * A recent recipient, ready to draw.
 *
 * <p>The initials are worked out here rather than in the template: deriving
 * {@code GM} from {@code GRACE MU***} is two string operations, and a template
 * expression doing them is a 500 waiting for the first recipient whose masked
 * name has no space in it.
 */
record RecentPerson(String accountNumber, String maskedName, String maskedAccountNumber, String initials) {

    static RecentPerson of(RecentRecipient recipient) {
        return new RecentPerson(
                recipient.accountNumber(),
                recipient.maskedName(),
                recipient.maskedAccountNumber(),
                Names.initials(recipient.maskedName()));
    }
}
