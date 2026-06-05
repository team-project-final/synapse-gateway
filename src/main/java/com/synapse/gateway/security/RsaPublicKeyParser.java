package com.synapse.gateway.security;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 공개키를 PEM(-----BEGIN PUBLIC KEY-----, X.509 SubjectPublicKeyInfo) 또는
 * base64 DER 문자열에서 파싱한다. 둘 다 실패하면 IllegalStateException으로 fail-fast.
 */
public final class RsaPublicKeyParser {

    private RsaPublicKeyParser() {
    }

    public static RSAPublicKey parse(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("gateway.jwt.public-key 가 설정되지 않았습니다");
        }
        String normalized = key
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(normalized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception exception) {
            throw new IllegalStateException("gateway.jwt.public-key 가 유효한 RSA 공개키가 아닙니다", exception);
        }
    }
}
