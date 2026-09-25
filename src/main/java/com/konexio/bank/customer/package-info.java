/**
 * Customer: the banking-side profile behind a login.
 *
 * <p>Owns the {@code customer} schema. The profile's primary key <em>is</em> the
 * identity module's {@code customer_id} (a real foreign key, since this is one
 * database), so this module holds what the bank knows about a person — name,
 * contact details, KYC level, standing — while identity holds what they use to
 * prove who they are.
 *
 * <p>Other modules use {@link com.konexio.bank.customer.CustomerApi}; nothing
 * inside {@code domain} or {@code web} is visible to them.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Customer")
package com.konexio.bank.customer;
