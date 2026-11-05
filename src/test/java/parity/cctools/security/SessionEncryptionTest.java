package parity.cctools.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/** Session attributes are sealed (AES-GCM): they round-trip, and a changed byte or another key fails. */
class SessionEncryptionTest {

    static String key(int b) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) b);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void roundTripsAndRefusesTamperingAndOtherKeys() {
        SessionEncryption a = new SessionEncryption(key(1), getClass().getClassLoader());
        SessionEncryption b = new SessionEncryption(key(2), getClass().getClassLoader());
        LinkedHashMap<String, String> value = new LinkedHashMap<>();
        value.put("refresh_token", "rt-secret-value");
        byte[] sealed = a.serializer().convert(value);
        assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("rt-secret-value");
        assertThat(a.deserializer().convert(sealed)).isEqualTo(value);
        assertThatThrownBy(() -> b.deserializer().convert(sealed)).isInstanceOf(RuntimeException.class);
        byte[] changed = sealed.clone();
        changed[changed.length - 1] ^= 1;
        assertThatThrownBy(() -> a.deserializer().convert(changed)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new SessionEncryption(Base64.getEncoder().encodeToString(new byte[16]), null))
                .isInstanceOf(parity.cctools.lifecycle.ConfigurationRefusedException.class);
    }
}
