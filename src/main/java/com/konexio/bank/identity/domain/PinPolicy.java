package com.konexio.bank.identity.domain;

/**
 * Rejects the PINs that a shoulder-surfer or a stolen-phone attacker would try
 * first. The rules are deliberately few: a 4-digit PIN has only 10,000
 * combinations, so what protects it is the lockout after five wrong attempts,
 * not an ever-growing list of banned patterns that mostly frustrates customers.
 */
final class PinPolicy {

    private PinPolicy() {}

    static void validate(String pin, int requiredLength) {
        if (pin == null || pin.length() != requiredLength || !pin.chars().allMatch(Character::isDigit))
            throw new IdentityExceptions.WeakPin(
                    "The PIN must be exactly %d digits.".formatted(requiredLength));
        if (isAllSameDigit(pin)) throw new IdentityExceptions.WeakPin("The PIN cannot be the same digit repeated.");
        if (isSequential(pin)) throw new IdentityExceptions.WeakPin("The PIN cannot be a run of consecutive digits.");
    }

    private static boolean isAllSameDigit(String pin) {
        return pin.chars().distinct().count() == 1;
    }

    private static boolean isSequential(String pin) {
        int direction = pin.charAt(1) - pin.charAt(0);
        if (direction != 1 && direction != -1) {
            return false;
        }
        for (int i = 2; i < pin.length(); i++)
            if (pin.charAt(i) - pin.charAt(i - 1) != direction) {
                return false;
            }
        return true;
    }
}
