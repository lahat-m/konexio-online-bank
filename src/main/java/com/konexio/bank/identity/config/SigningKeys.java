package com.konexio.bank.identity.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.stereotype.Component;

/**
 * The RSA keys this application signs and verifies its own tokens with.
 *
 * <p>Every configured key stays in the set and in the published JWK set; only
 * {@code activeKid} signs new tokens. That is what makes rotation non-breaking:
 * after switching the active key, tokens signed by the previous one keep
 * verifying until they expire, then the old key can be dropped.
 *
 * <p>The application does not fetch its own JWKS over HTTP — issuer and resource
 * server are the same process, so verification reads this in-process
 * {@link JWKSource} directly. {@code /.well-known/jwks.json} exists for external
 * clients.
 *
 * <p>With no keys configured, a key pair is generated at startup so a developer
 * can run the application with no setup. It is ephemeral: a restart invalidates
 * every token issued before it, which is exactly why the warning is loud and why
 * a real environment must configure {@code app.identity.jwt.keys}.
 *
 * <p>{@code identity.signing_key} tracks key metadata and rotation state for
 * operators; the runtime role has no INSERT or UPDATE on it (see the grants
 * migration), so keys are published to it by the rotation job, not from here.
 */
@Component
public class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);
    private static final int DEVELOPMENT_KEY_SIZE = 2048;

    private final JWKSet jwkSet;
    private final String activeKid;

    SigningKeys(IdentityProperties properties) {
        List<IdentityProperties.SigningKeyPair> configured = properties.jwt().keys();
        if (configured == null || configured.isEmpty()) {
            RSAKey generated = generateDevelopmentKey();
            this.jwkSet = new JWKSet(generated);
            this.activeKid = generated.getKeyID();
            log.warn("No app.identity.jwt.keys configured — generated the ephemeral signing key '{}'. "
                    + "Every token issued becomes invalid when this application restarts. "
                    + "Configure real keys outside local development.", activeKid);
        } else {
            List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>(configured.size());
            for (IdentityProperties.SigningKeyPair pair : configured) {
                keys.add(load(pair));
            }
            this.jwkSet = new JWKSet(keys);
            this.activeKid = resolveActiveKid(properties.jwt().activeKid(), keys);
            log.info("Loaded {} signing key(s); signing with '{}'", keys.size(), activeKid);
        }
    }

    /** Includes the private keys: used by the encoder to sign and the decoder to verify. */
    public JWKSource<SecurityContext> jwkSource() {
        return new ImmutableJWKSet<>(jwkSet);
    }

    /** Public halves only — what {@code /.well-known/jwks.json} serves. */
    public JWKSet publicJwkSet() {
        return jwkSet.toPublicJWKSet();
    }

    public String activeKid() {
        return activeKid;
    }

    private static RSAKey load(IdentityProperties.SigningKeyPair pair) {
        if (pair.kid() == null || pair.publicKey() == null || pair.privateKey() == null) {
            throw new IllegalStateException(
                    "Each app.identity.jwt.keys entry needs a kid, a public-key and a private-key resource");
        }
        RSAPublicKey publicKey = read(pair.publicKey(), RsaKeyConverters.x509()::convert);
        RSAPrivateKey privateKey = read(pair.privateKey(), RsaKeyConverters.pkcs8()::convert);
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(pair.kid())
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }

    private static <T> T read(Resource resource, java.util.function.Function<InputStream, T> converter) {
        try (InputStream in = resource.getInputStream()) {
            return converter.apply(in);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read signing key " + resource, e);
        }
    }

    private static String resolveActiveKid(String configuredKid, List<com.nimbusds.jose.jwk.JWK> keys) {
        if (configuredKid == null || configuredKid.isBlank()) {
            if (keys.size() > 1) {
                throw new IllegalStateException(
                        "app.identity.jwt.active-kid must be set when more than one signing key is configured");
            }
            return keys.getFirst().getKeyID();
        }
        boolean known = keys.stream().anyMatch(key -> configuredKid.equals(key.getKeyID()));
        if (!known) {
            throw new IllegalStateException(
                    "app.identity.jwt.active-kid '%s' is not among the configured keys".formatted(configuredKid));
        }
        return configuredKid;
    }

    private static RSAKey generateDevelopmentKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(DEVELOPMENT_KEY_SIZE);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("dev-" + UUID.randomUUID().toString().substring(0, 8))
                    .keyUse(KeyUse.SIGNATURE)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation is unavailable", e);
        }
    }
}
