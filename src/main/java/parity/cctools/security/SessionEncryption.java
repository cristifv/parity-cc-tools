package parity.cctools.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.serializer.support.SerializationFailedException;

/**
 * Session attributes are encrypted before they reach Postgres (AES-256-GCM, a random 96-bit nonce per
 * write, version byte 1): the session holds the operator's OAuth2 tokens (refresh token included), and a
 * database reader or a backup must not be able to use them (PLAN.md §7: tokens server-side only). The
 * key is 32 bytes, base64, from {@code parity-cc-tools.session.key-file}; rotating it ends every session.
 */
public final class SessionEncryption {

    private static final byte VERSION = 1;
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final ClassLoader loader;

    public SessionEncryption(String base64Key, ClassLoader loader) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new parity.cctools.lifecycle.ConfigurationRefusedException("parity-cc-tools.session.key-file isn't base64");
        }
        if (raw.length != 32) {
            throw new parity.cctools.lifecycle.ConfigurationRefusedException("parity-cc-tools.session.key-file must hold 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.loader = loader;
    }

    /** Object to encrypted bytes: Java serialization, then AES-GCM. */
    public Converter<Object, byte[]> serializer() {
        return value -> {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
                try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                    out.writeObject(value);
                }
                return seal(bytes.toByteArray());
            } catch (IOException | GeneralSecurityException e) {
                throw new SerializationFailedException("a session attribute can't be stored", e);
            }
        };
    }

    /** Encrypted bytes to object. A value that doesn't decrypt (another key, tampering) fails. */
    public Converter<byte[], Object> deserializer() {
        return sealed -> {
            try {
                byte[] plain = open(sealed);
                try (ObjectInputStream in = new LoaderInputStream(new ByteArrayInputStream(plain), loader)) {
                    return in.readObject();
                }
            } catch (IOException | ClassNotFoundException | GeneralSecurityException e) {
                throw new SerializationFailedException("a session attribute can't be read", e);
            }
        };
    }

    byte[] seal(byte[] plain) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        c.updateAAD(new byte[] {VERSION});
        byte[] body = c.doFinal(plain);
        return ByteBuffer.allocate(1 + NONCE + body.length).put(VERSION).put(nonce).put(body).array();
    }

    byte[] open(byte[] sealed) throws GeneralSecurityException {
        if (sealed == null || sealed.length < 1 + NONCE + TAG_BITS / 8 || sealed[0] != VERSION) {
            throw new GeneralSecurityException("not a sealed session attribute");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 1, NONCE));
        c.updateAAD(new byte[] {VERSION});
        return c.doFinal(sealed, 1 + NONCE, sealed.length - 1 - NONCE);
    }

    /** Deserializes with the application's class loader (Spring Session's own does the same). */
    private static final class LoaderInputStream extends ObjectInputStream {
        private final ClassLoader loader;

        LoaderInputStream(ByteArrayInputStream in, ClassLoader loader) throws IOException {
            super(in);
            this.loader = loader;
        }

        @Override
        protected Class<?> resolveClass(java.io.ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            try {
                return Class.forName(desc.getName(), false, loader);
            } catch (ClassNotFoundException e) {
                return super.resolveClass(desc);
            }
        }
    }
}
