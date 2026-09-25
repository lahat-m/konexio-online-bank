package com.konexio.bank.identity.domain;

/**
 * A phone-and-PIN login attempt.
 *
 * @param deviceId    stable per installation; when present the device is
 *                    registered and named in the access token
 * @param platform    {@code ANDROID}, {@code IOS} or {@code WEB}; defaults to
 *                    {@code WEB} when a device id arrives without one
 * @param deviceModel free text for the customer's "your devices" screen
 */
public record LoginCommand(
        String phone,
        String pin,
        String deviceId,
        String platform,
        String deviceModel) {}
