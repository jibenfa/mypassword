package com.example.coffeecat.mypassword.next;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.spec.KeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKeyFactory;
import javax.crypto.SecretKey;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Stores per-input metadata encrypted with a key held by Android Keystore. */
final class SavedEntryStore {
    private static final String PREFERENCES = "saved_entries";
    private static final String KEY_ALIAS = "mypassword_saved_entries_key";
    private static final int IV_LENGTH = 12;
    private static final int BACKUP_ITERATIONS = 200_000;
    private static final int BACKUP_SALT_LENGTH = 16;

    private final SharedPreferences preferences;

    SavedEntryStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    Entry load(String fingerprint, String secondInput) throws GeneralSecurityException, IOException {
        String id = entryId(fingerprint, secondInput);
        String encoded = preferences.getString(id, null);
        if (encoded == null) return null;

        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        if (packed.length <= IV_LENGTH) throw new IOException("Saved entry is incomplete");
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = new byte[packed.length - IV_LENGTH];
        System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
        System.arraycopy(packed, IV_LENGTH, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = cipher.doFinal(ciphertext);
        try {
            JSONObject json = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            if (!secondInput.equals(json.optString("secondInput", null))) {
                throw new IOException("Saved entry does not match its lookup input");
            }
            return new Entry(json.optInt("length", 8), json.optString("note", ""));
        } catch (JSONException e) {
            throw new IOException("Saved entry could not be read", e);
        }
    }

    void save(String fingerprint, String secondInput, int length, String note)
            throws GeneralSecurityException, IOException, JSONException {
        String id = entryId(fingerprint, secondInput);
        JSONObject json = new JSONObject();
        json.put("secondInput", secondInput);
        json.put("length", length);
        json.put("note", note);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        byte[] packed = ByteBuffer.allocate(cipher.getIV().length + ciphertext.length)
                .put(cipher.getIV()).put(ciphertext).array();
        preferences.edit().putString(id, Base64.encodeToString(packed, Base64.NO_WRAP)).apply();
    }

    List<NoteMatch> searchByNote(String fingerprint, String query)
            throws GeneralSecurityException, IOException, JSONException {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<NoteMatch> matches = new ArrayList<>();
        if (needle.isEmpty()) return matches;

        for (Map.Entry<String, ?> stored : preferences.getAll().entrySet()) {
            if (!(stored.getValue() instanceof String)) continue;
            String id = stored.getKey();
            JSONObject entry = decryptStoredValue(id, (String) stored.getValue());
            String secondInput = entry.optString("secondInput", null);
            if (secondInput == null || !entryId(fingerprint, secondInput).equals(id)) continue;
            String note = entry.optString("note", "");
            if (note.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(new NoteMatch(secondInput, note, entry.optInt("length", 8)));
            }
        }
        return matches;
    }

    byte[] exportBackup(String masterPassword) throws GeneralSecurityException, IOException, JSONException {
        JSONObject payload = new JSONObject();
        payload.put("format", 1);
        org.json.JSONArray entries = new org.json.JSONArray();
        for (Map.Entry<String, ?> stored : preferences.getAll().entrySet()) {
            if (!(stored.getValue() instanceof String)) continue;
            String id = stored.getKey();
            JSONObject entry = decryptStoredValue(id, (String) stored.getValue());
            entries.put(entry);
        }
        payload.put("entries", entries);

        byte[] salt = new byte[BACKUP_SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        SecretKey backupKey = deriveBackupKey(masterPassword, salt, BACKUP_ITERATIONS);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, backupKey, new GCMParameterSpec(128, iv));
        cipher.updateAAD("MyPassword backup v1".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));

        JSONObject envelope = new JSONObject();
        envelope.put("app", "MyPassword");
        envelope.put("format", 1);
        envelope.put("iterations", BACKUP_ITERATIONS);
        envelope.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        envelope.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        envelope.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        return envelope.toString().getBytes(StandardCharsets.UTF_8);
    }

    int importBackup(byte[] backup, String masterPassword, String fingerprint)
            throws GeneralSecurityException, IOException, JSONException {
        final JSONObject envelope;
        try {
            envelope = new JSONObject(new String(backup, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Backup file is not valid", e);
        }
        if (!"MyPassword".equals(envelope.optString("app")) || envelope.optInt("format") != 1) {
            throw new IOException("Unsupported backup file");
        }
        int iterations = envelope.optInt("iterations", -1);
        if (iterations < 100_000 || iterations > 1_000_000) throw new IOException("Invalid backup settings");

        byte[] salt = decodeField(envelope, "salt");
        byte[] iv = decodeField(envelope, "iv");
        byte[] encrypted = decodeField(envelope, "data");
        if (salt.length != BACKUP_SALT_LENGTH || iv.length != IV_LENGTH || encrypted.length < 16) {
            throw new IOException("Backup file is incomplete");
        }
        SecretKey backupKey = deriveBackupKey(masterPassword, salt, iterations);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, backupKey, new GCMParameterSpec(128, iv));
        cipher.updateAAD("MyPassword backup v1".getBytes(StandardCharsets.UTF_8));
        final JSONObject payload;
        try {
            payload = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
        } catch (javax.crypto.AEADBadTagException e) {
            throw new GeneralSecurityException("Backup password is incorrect or file was changed", e);
        } catch (JSONException e) {
            throw new IOException("Backup contents are invalid", e);
        }
        if (payload.optInt("format") != 1) throw new IOException("Unsupported backup contents");

        org.json.JSONArray entries = payload.optJSONArray("entries");
        if (entries == null) throw new IOException("Backup entries are missing");
        int imported = 0;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) throw new IOException("Backup entry is invalid");
            String secondInput = entry.optString("secondInput", null);
            if (secondInput == null || secondInput.isEmpty()) throw new IOException("Backup entry is missing input");
            int length = entry.optInt("length", 8);
            String note = entry.optString("note", "");
            save(fingerprint, secondInput, length, note);
            imported++;
        }
        return imported;
    }

    private JSONObject decryptStoredValue(String id, String encoded)
            throws GeneralSecurityException, IOException, JSONException {
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        if (packed.length <= IV_LENGTH) throw new IOException("Saved entry is incomplete");
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = new byte[packed.length - IV_LENGTH];
        System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
        System.arraycopy(packed, IV_LENGTH, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
        try {
            return new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Saved entry could not be read", e);
        }
    }

    private byte[] decodeField(JSONObject object, String key) throws IOException {
        try {
            return Base64.decode(object.getString(key), Base64.DEFAULT);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Backup file is invalid", e);
        }
    }

    private SecretKey deriveBackupKey(String password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            KeySpec keySpec = spec;
            byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(keySpec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private String entryId(String fingerprint, String secondInput) {
        return LegacyPasswordAlgorithm.sha256(fingerprint + '\u0000' + secondInput);
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            throw new GeneralSecurityException("Unexpected saved-data key type");
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    static final class Entry {
        final int length;
        final String note;

        Entry(int length, String note) {
            this.length = length >= 8 && length <= 12 ? length : 8;
            this.note = note;
        }
    }

    static final class NoteMatch {
        final String secondInput;
        final String note;
        final int length;

        NoteMatch(String secondInput, String note, int length) {
            this.secondInput = secondInput;
            this.note = note;
            this.length = length >= 8 && length <= 12 ? length : 8;
        }
    }
}
