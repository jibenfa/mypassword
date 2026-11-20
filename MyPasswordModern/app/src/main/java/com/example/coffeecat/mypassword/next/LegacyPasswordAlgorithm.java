package com.example.coffeecat.mypassword.next;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Compatibility implementation of SHA-256 based password algorithms. */
final class LegacyPasswordAlgorithm {
    private static final String SYMBOLS = "~!@#$%^&*()_+-=";

    private LegacyPasswordAlgorithm() {}

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(Charset.defaultCharset()));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                String part = Integer.toHexString(b & 0xff);
                if (part.length() == 1) hex.append('0');
                hex.append(part);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String fingerprint(String masterPassword) {
        return sha256(masterPassword).substring(0, 18);
    }

    static String getCheckFragment(String masterPassword) {
        return sha256(masterPassword).substring(18, 60);
    }

    static boolean isMasterPasswordValid(String masterPassword, String storedCheckFragment) {
        if (storedCheckFragment == null || masterPassword == null) return false;
        return getCheckFragment(masterPassword).equals(storedCheckFragment);
    }

    static String generate(String secondInput, String fingerprint) {
        return generate(secondInput, fingerprint, 8);
    }

    static String generate(String secondInput, String fingerprint, int length) {
        if (length < 8 || length > 12) {
            throw new IllegalArgumentException("Password length must be between 8 and 12");
        }
        String hash = sha256(secondInput + fingerprint);

        int a = 0;
        String part1;
        do {
            part1 = hash.substring(a, a + 4);
            a++;
        } while (part1.toUpperCase().equals(part1.toLowerCase()) && a + 4 < hash.length());

        int b = hash.length();
        String part2;
        do {
            part2 = hash.substring(b - 3, b);
            b--;
        } while (part2.toLowerCase().equals(part2.toUpperCase()) && b - 3 >= 0);

        int c = 0;
        String part3;
        do {
            part3 = hash.substring(c, c + 1);
            c++;
        } while (!part3.toLowerCase().equals(part3.toUpperCase()) && c + 1 < hash.length());
        c %= 7;

        int p = hash.length();
        String part4;
        do {
            part4 = hash.substring(p - 1, p);
            p--;
        } while (part4.toLowerCase().equals(part4.toUpperCase()) && p - 1 >= 0);

        p %= SYMBOLS.length();
        String symbol = SYMBOLS.substring(p, p + 1);
        String edgeParts = part1.toUpperCase() + part2.toLowerCase();
        String legacyPassword = edgeParts.substring(0, c) + symbol + edgeParts.substring(c, 7);
        if (length == 8) return legacyPassword;

        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789~!@#$%^&*()-_=+";
        String extension = sha256(hash + "|length:" + length);
        StringBuilder password = new StringBuilder(legacyPassword);
        for (int i = 0; password.length() < length; i++) {
            int value = Integer.parseInt(extension.substring(i * 2, i * 2 + 2), 16);
            password.append(alphabet.charAt(value % alphabet.length()));
        }
        return password.toString();
    }
}
