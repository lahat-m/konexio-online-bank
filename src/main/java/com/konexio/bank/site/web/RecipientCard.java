package com.konexio.bank.site.web;

/**
 * The person a transfer is going to, as the screens that build it name them.
 *
 * <p>Every string is worked out here rather than in the template, for the reason
 * {@link RecentPerson} gives: a masked name is not a name, and a template
 * expression splitting one is a 500 waiting for the first recipient it does not
 * fit. They are record components rather than methods on purpose — a template
 * reaching a package-private record's accessor works, and calling its other
 * methods does not.
 *
 * <p>The wording names them — "Send to Joseph", "Note for Joseph" — because a
 * screen that says "Continue" twice tells the customer nothing about who is
 * about to be paid. The first name survives masking: only the surname is
 * starred out.
 *
 * @param sendTitle    the flow name in the header of 3.3c
 * @param confirmLabel the primary button on 3.3b, which answers "is this the
 *                     right person?"
 * @param noteHint     their statement, not his or hers: the bank asks for a
 *                     name, not a gender, so there is nothing here to guess from
 *                     and no reason to guess
 */
record RecipientCard(
        String maskedName,
        String maskedAccountNumber,
        String initials,
        String sendTitle,
        String confirmLabel,
        String noteLabel,
        String noteHint,
        boolean firstTime) {

    static RecipientCard of(TransferForm form) {
        String maskedName = form.getRecipientName();
        String firstName = Names.first(maskedName);
        // Empty when the lookup came back without a name — a profile missing
        // behind an open account. Rare, and not a reason to write "Send to " and
        // stop.
        boolean named = !firstName.isBlank();

        return new RecipientCard(
                maskedName,
                form.getRecipientMaskedAccountNumber(),
                Names.initials(maskedName),
                named ? "Send to " + firstName : "Send money",
                named ? "Yes, send to " + firstName : "Yes, send money",
                named ? "Note for " + firstName + " (optional)" : "Note (optional)",
                named
                        ? firstName + " will see this on their statement."
                        : "The person you are paying will see this on their statement.",
                form.isFirstTimeRecipient());
    }
}
