package com.konexio.bank.identity.web;

import com.konexio.bank.identity.config.SigningKeys;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public signing keys.
 *
 * <p>This application verifies its own tokens from the in-process key source, so
 * nothing here is on its critical path — the endpoint exists for external
 * clients (a partner service, a gateway) that need to check a Konexio token
 * without calling us.
 *
 * <p>Cacheable for five minutes: long enough to spare the round trips, short
 * enough that a rotated key is picked up quickly. Every key stays published
 * until tokens signed with it have expired, so a cached copy is never the reason
 * a valid token is rejected.
 */
@RestController
class JwksController {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final SigningKeys signingKeys;

    JwksController(SigningKeys signingKeys) {
        this.signingKeys = signingKeys;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
                .body(signingKeys.publicJwkSet().toJSONObject());
    }
}
