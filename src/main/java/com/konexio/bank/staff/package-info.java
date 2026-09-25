/**
 * Staff: what the bank's own people can see, and the one thing they can change.
 *
 * <p>Named after who uses it rather than after what it reads, because it reads
 * nearly everything. Only two of its endpoints are about the audit trail; the
 * rest find a customer, open their accounts, follow a reference into the books.
 * Calling the module {@code audit} would have described a fifth of it.
 *
 * <p>Owns no tables. Every answer comes from another module's API — customers
 * from {@code customer}, accounts from {@code account}, the books from
 * {@code ledger}, the security trail from {@code identity}, the audit log from
 * {@code shared} — which is what keeps a staff view from becoming a second,
 * divergent version of a rule that already lives somewhere.
 *
 * <p>The {@code audit} schema stays with {@code shared.audit}, where the writer
 * is: every module in the bank appends to {@code audit.audit_log}, so the writer
 * has to sit somewhere everything may depend on. This module reads that trail,
 * and adds to it — looking a customer up is itself recorded.
 *
 * <p>It has exactly one write path of its own: reversing a journal entry. That
 * is not an oversight. A member of staff cannot edit a balance, change a
 * customer's KYC level or close an account from here, because none of those are
 * corrections — they are the bank deciding something, and a decision goes
 * through the flow that owns it. A posting made in error is different: the books
 * cannot be edited at all, so the only way to put money back is to post its
 * mirror image, and somebody has to be able to do that.
 *
 * <p>Access is by role on a staff token ({@code OPS}, {@code COMPLIANCE},
 * {@code ADMIN}) and is decided in the security filter chain rather than here,
 * so a new endpoint under {@code /api/staff} is denied to customers by default
 * rather than by somebody remembering an annotation.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Staff")
package com.konexio.bank.staff;
