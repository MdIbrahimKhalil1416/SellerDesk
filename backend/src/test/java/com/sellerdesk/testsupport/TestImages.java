package com.sellerdesk.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Tiny byte arrays that start like real image files (only the first bytes are checked). */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] jpeg() {
        return jpegOfSize(64);
    }

    /** JPEG magic bytes followed by zeros, {@code size} bytes in total. */
    public static byte[] jpegOfSize(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    public static byte[] png() {
        byte[] bytes = Arrays.copyOf(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 64);
        return bytes;
    }

    public static byte[] webp() {
        return Arrays.copyOf("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII), 64);
    }

    /** Starts with "MZ", like a Windows .exe. */
    public static byte[] exe() {
        return Arrays.copyOf("MZ\u0090\0\u0003\0".getBytes(StandardCharsets.ISO_8859_1), 64);
    }
}
