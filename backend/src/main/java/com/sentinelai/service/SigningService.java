package com.sentinelai.service;

import com.sentinelai.model.PromptLog;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Signs and verifies audit records using Ed25519 asymmetric cryptography.
 *
 * <p>Signing is optional — if {@code SIGNING_PRIVATE_KEY} and
 * {@code SIGNING_PUBLIC_KEY} are not configured, this service is disabled and
 * all sign/verify calls are no-ops. Records created while signing is disabled
 * have a {@code null} signature and show as "unsigned" in the dashboard.</p>
 *
 * <h2>Canonical form</h2>
 * <p>The signature covers these fields joined by {@code |} in a fixed order:</p>
 * <pre>
 *   {id}|{requestTime}|{prompt}|{response}|{model}|{provider}|{riskScore}
 * </pre>
 * <p>Null fields (response, riskScore) are represented as an empty string.
 * Metadata is excluded — it is caller-supplied context, not part of the AI exchange.</p>
 *
 * <h2>Key format</h2>
 * <p>Keys are supplied as Base64-encoded PEM files via environment variables.
 * Generate them with:</p>
 * <pre>
 *   openssl genpkey -algorithm ed25519 -out private.pem
 *   openssl pkey -in private.pem -pubout -out public.pem
 *   export SIGNING_PRIVATE_KEY=$(base64 -i private.pem)
 *   export SIGNING_PUBLIC_KEY=$(base64 -i public.pem)
 * </pre>
 */
@Slf4j
@Service
public class SigningService {

    @Value("${sentinelai.signing.private-key:}")
    private String encodedPrivateKey;

    @Value("${sentinelai.signing.public-key:}")
    private String encodedPublicKey;

    private KeyPair keyPair;

    @PostConstruct
    void init() {
        if (encodedPrivateKey.isBlank() || encodedPublicKey.isBlank()) {
            log.info("Audit record signing is DISABLED — set SIGNING_PRIVATE_KEY and SIGNING_PUBLIC_KEY to enable");
            return;
        }
        try {
            PrivateKey privateKey = loadPrivateKey(encodedPrivateKey);
            PublicKey publicKey = loadPublicKey(encodedPublicKey);
            this.keyPair = new KeyPair(publicKey, privateKey);
            log.info("Audit record signing is ENABLED — Ed25519");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Ed25519 signing keypair — check SIGNING_PRIVATE_KEY and SIGNING_PUBLIC_KEY", e);
        }
    }

    public boolean isEnabled() {
        return keyPair != null;
    }

    /**
     * Computes an Ed25519 signature over the canonical form of the record.
     *
     * @return Base64-encoded signature, or {@code null} if signing is disabled
     */
    public String sign(PromptLog record) {
        if (!isEnabled()) return null;
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(canonical(record).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            log.error("Failed to sign audit record id={}: {}", record.getId(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Verifies the stored signature against the record's current field values.
     *
     * @return {@code true} if the signature is valid and the record is unmodified,
     *         {@code false} if verification fails or signing is disabled
     */
    public boolean verify(PromptLog record) {
        if (!isEnabled() || record.getSignature() == null) return false;
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(keyPair.getPublic());
            sig.update(canonical(record).getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(record.getSignature()));
        } catch (Exception e) {
            log.warn("Signature verification failed for record id={}: {}", record.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Returns the public key as a PEM-formatted string for out-of-band distribution.
     */
    public String getPublicKeyPem() {
        if (!isEnabled()) return null;
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    // ── Canonical form ────────────────────────────────────────────────────────

    // Fixed format in UTC — immune to nanosecond/microsecond precision differences
    // between Java and PostgreSQL. Pattern: HH:mm:ss:SSS MM/dd/yyyy
    private static final DateTimeFormatter CANONICAL_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss:SSS MM/dd/yyyy").withZone(ZoneOffset.UTC);

    private String canonical(PromptLog record) {
        String requestTimeUtc = CANONICAL_TIME.format(record.getRequestTime().toInstant());
        return String.join("|",
                record.getId().toString(),
                requestTimeUtc,
                record.getPrompt(),
                record.getResponse() != null ? record.getResponse() : "",
                record.getModel(),
                record.getProvider(),
                record.getRiskScore() != null ? record.getRiskScore().toPlainString() : ""
        );
    }

    // ── Key loading ───────────────────────────────────────────────────────────

    private PrivateKey loadPrivateKey(String base64Pem) throws Exception {
        byte[] derBytes = pemToDer(base64Pem);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(derBytes));
    }

    private PublicKey loadPublicKey(String base64Pem) throws Exception {
        byte[] derBytes = pemToDer(base64Pem);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePublic(new X509EncodedKeySpec(derBytes));
    }

    /** Decodes a Base64-encoded PEM file to its raw DER bytes. */
    private byte[] pemToDer(String base64Pem) {
        String pem = new String(Base64.getDecoder().decode(base64Pem));
        String inner = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(inner);
    }
}
