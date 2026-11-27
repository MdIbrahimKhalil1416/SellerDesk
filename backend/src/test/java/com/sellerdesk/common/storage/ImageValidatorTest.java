package com.sellerdesk.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sellerdesk.common.error.ImageTooLargeException;
import com.sellerdesk.testsupport.TestImages;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageValidatorTest {

    private final ImageValidator validator = new ImageValidator();

    @Test
    void jpegPngAndWebpPassByTheirBytes() {
        assertThat(validator.validate(TestImages.jpeg())).isEqualTo(ImageValidator.ImageType.JPEG);
        assertThat(validator.validate(TestImages.png())).isEqualTo(ImageValidator.ImageType.PNG);
        assertThat(validator.validate(TestImages.webp())).isEqualTo(ImageValidator.ImageType.WEBP);
    }

    @Test
    void gifIsRejected() {
        assertThatThrownBy(() -> validator.validate("GIF89a....".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(ImageTypeNotAllowedException.class)
                .extracting("code").isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
    }

    /** A Windows program renamed to .png and sent with Content-Type image/png: only the bytes count. */
    @Test
    void renamedExeIsRejected() {
        assertThatThrownBy(() -> validator.validate(TestImages.exe()))
                .isInstanceOf(ImageTypeNotAllowedException.class);
    }

    @Test
    void riffFileThatIsNotWebpIsRejected() {
        byte[] wav = "RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> validator.validate(wav)).isInstanceOf(ImageTypeNotAllowedException.class);
    }

    @Test
    void emptyAndTooShortFilesAreRejected() {
        assertThatThrownBy(() -> validator.validate(new byte[0])).isInstanceOf(ImageTypeNotAllowedException.class);
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(ImageTypeNotAllowedException.class);
        assertThatThrownBy(() -> validator.validate(new byte[] {(byte) 0xFF, (byte) 0xD8}))
                .isInstanceOf(ImageTypeNotAllowedException.class);
    }

    @Test
    void exactly5MbPassesOneByteMoreIsTooLarge() {
        assertThat(validator.validate(TestImages.jpegOfSize((int) ImageValidator.MAX_BYTES)))
                .isEqualTo(ImageValidator.ImageType.JPEG);
        assertThatThrownBy(() -> validator.validate(TestImages.jpegOfSize((int) ImageValidator.MAX_BYTES + 1)))
                .isInstanceOf(ImageTooLargeException.class)
                .extracting("code").isEqualTo("IMAGE_TOO_LARGE");
    }
}
