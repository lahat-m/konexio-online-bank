/**
 * Site: the pages a customer actually looks at.
 *
 * <p>Server-rendered Thymeleaf, not a second API. The flows in README.md
 * ("The website") are one flow per URL, and each one is a controller that
 * renders a template — no client-side router, no JSON round trip to fill in a
 * page the server could have rendered already.
 *
 * <p>Owns no tables and holds no rules. When a screen needs to do something, it
 * calls the module that owns it through that module's API, exactly as the REST
 * controllers do; the difference is only what comes back out, HTML rather than
 * JSON. That is what stops "the web version" from becoming a second, divergent
 * implementation of registration or transfers.
 *
 * <p>Everything visual lives in {@code static/css/konexio.css} as custom
 * properties and classes. No template carries a colour, a size or a
 * {@code style} attribute, which is what keeps a change of brand to one file —
 * and is what lets the pages ship under a {@code default-src 'self'} content
 * security policy, since there is nothing inline for it to block.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Site")
package com.konexio.bank.site;
