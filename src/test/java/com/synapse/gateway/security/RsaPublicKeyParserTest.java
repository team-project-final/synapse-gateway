package com.synapse.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;

class RsaPublicKeyParserTest {

    @Test
    void parsesPemEncodedKey() {
        RSAPublicKey parsed = RsaPublicKeyParser.parse(TestKeys.publicKeyPem());
        assertThat(parsed.getEncoded()).isEqualTo(TestKeys.publicKey().getEncoded());
    }

    @Test
    void parsesBase64DerKey() {
        RSAPublicKey parsed = RsaPublicKeyParser.parse(TestKeys.publicKeyBase64());
        assertThat(parsed.getEncoded()).isEqualTo(TestKeys.publicKey().getEncoded());
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> RsaPublicKeyParser.parse("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsGarbageKey() {
        assertThatThrownBy(() -> RsaPublicKeyParser.parse("not-a-valid-key"))
                .isInstanceOf(IllegalStateException.class);
    }
}
