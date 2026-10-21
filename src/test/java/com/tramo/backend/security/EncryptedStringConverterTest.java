package com.tramo.backend.security;

import com.tramo.backend.security.crypto.EncryptedStringConverter;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class EncryptedStringConverterTest {
    @Test
    void preservesTheExistingFormatAndAuthenticatesCiphertext() {
        var cipher = new EncryptedStringConverter(Base64.getEncoder().encodeToString(new byte[32]));
        String stored = cipher.convertToDatabaseColumn("token");
        assertThat(stored).isNotEqualTo("token");
        assertThat(cipher.convertToEntityAttribute(stored)).isEqualTo("token");
        assertThat(cipher.convertToDatabaseColumn(null)).isNull();
        assertThat(cipher.convertToEntityAttribute(null)).isNull();
        byte[] differentKey = new byte[32]; differentKey[0] = 1;
        var rotated = new EncryptedStringConverter(Base64.getEncoder().encodeToString(differentKey));
        assertThatThrownBy(() -> rotated.convertToEntityAttribute(stored)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.convertToEntityAttribute("invalid-ciphertext")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidKeyConfigurationAtStartup() {
        for (int size : new int[]{0, 16, 24, 31, 33}) {
            assertThatThrownBy(() -> new EncryptedStringConverter(Base64.getEncoder().encodeToString(new byte[size])))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new EncryptedStringConverter("not-base64")).isInstanceOf(IllegalArgumentException.class);
    }
}
