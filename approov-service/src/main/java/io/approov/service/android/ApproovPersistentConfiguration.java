//
// MIT License
//
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * ApproovPersistentConfiguration stores the persistent configuration the app sets: options in the form of the
 * bundled approov.config that the file form of initialize applies at the next launch, over the options of the
 * bundled file. The record is kept in the no-backup files of the app, so it never reaches a backup or another
 * device, and it is written atomically. It records the hash of the bundled file and the version of the app it
 * was stored with, so that a new release of the app starts from its own bundled file. All file access is
 * serialized by one lock of this class, never the ApproovService monitor.
 */
final class ApproovPersistentConfiguration {
    // logging tag
    private static final String TAG = "ApproovService";

    // the directory in the no-backup files of the app that holds the record
    static final String DIRECTORY = "approov";

    // the name of the record
    static final String FILE_NAME = "override.json";

    // the prefix and suffix of the temporary file a record is written to, unique per write
    private static final String TEMP_PREFIX = "override";
    private static final String TEMP_SUFFIX = ".tmp";

    // the age after which a temporary file is left over from a write that never finished, in milliseconds
    static final long STALE_TEMP_AGE_MS = 60 * 1000;

    // the largest options accepted, in bytes of UTF-8
    static final int MAX_SIZE = 64 * 1024;

    // the longest source tag accepted, in bytes of UTF-8
    static final int MAX_SOURCE_SIZE = 1024;

    // the largest record read, above anything a valid call can store
    private static final int MAX_RECORD_SIZE = 128 * 1024;

    // the version of the record format
    static final int VERSION = 1;

    // the members of the record
    private static final String V = "v";
    private static final String SOURCE = "source";
    private static final String BUNDLED_HASH = "bundledHash";
    private static final String APP_VERSION = "appVersion";
    private static final String OPTIONS = "options";

    // the lock that serializes every access to the record
    private static final Object LOCK = new Object();

    // true once the record in force for this process has been read
    private static boolean launchRead = false;

    // the record in force for this process, while it is not yet checked against the bundled file, or null
    private static Record launchRecord = null;

    // the persistent configuration in force for this process, once checked, or null for none
    private static Stored launchStored = null;

    // the line about the record of this process deleted instead of applied, or null for none
    private static Notice launchNotice = null;

    // when true, a write stops halfway with an IOException, so tests can check the old record survives
    static volatile boolean failWriteForTesting = false;

    /**
     * Stored is a valid persistent configuration: its options and its source tag.
     */
    static final class Stored {
        // the options, as parsed
        final Map<String, Object> options;

        // the source tag, or null for none
        final String source;

        Stored(Map<String, Object> options, String source) {
            this.options = options;
            this.source = source;
        }
    }

    /**
     * Notice is the line about a persistent configuration deleted instead of applied, logged when the options of
     * the bundled file are applied, so that it follows the logging level they set.
     */
    static final class Notice {
        // true for a WARNING line, false for an INFO line
        final boolean warning;

        // the line
        final String message;

        Notice(boolean warning, String message) {
            this.warning = warning;
            this.message = message;
        }
    }

    /**
     * Launch is what the file form of initialize uses for this process: the persistent configuration in force,
     * if any, and the line about one deleted instead, if any.
     */
    static final class Launch {
        // the persistent configuration in force, or null for none
        final Stored stored;

        // the line about a deleted persistent configuration, or null for none
        final Notice notice;

        Launch(Stored stored, Notice notice) {
            this.stored = stored;
            this.notice = notice;
        }
    }

    // Record is a record read from the device: its parts, not yet checked against the bundled file
    private static final class Record {
        // the source tag, or null for none
        final String source;

        // the hash of the bundled file the record was stored with, or null if there was none
        final String bundledHash;

        // the version of the app the record was stored with, or null if it was not known
        final String appVersion;

        // the options
        final Map<String, Object> options;

        Record(String source, String bundledHash, String appVersion, Map<String, Object> options) {
            this.source = source;
            this.bundledHash = bundledHash;
            this.appVersion = appVersion;
            this.options = options;
        }
    }

    // InvalidRecordException reports a record that cannot be used, with the reason logged when it is deleted
    private static final class InvalidRecordException extends Exception {
        InvalidRecordException(String reason) {
            super(reason);
        }
    }

    /**
     * Construction is disallowed as this is a static only class.
     */
    private ApproovPersistentConfiguration() {
    }

    /**
     * Validates and stores a persistent configuration, to be applied by the file form of initialize at the next
     * launch. Nothing is applied in this process. An identical record already stored is not written again.
     *
     * @param context is any context of the app
     * @param json is the JSON object of the options
     * @param source is the source tag, or null for none
     * @throws ApproovException if the options are not valid or cannot be stored, with nothing stored
     */
    static void store(Context context, String json, String source) throws ApproovException {
        if (context == null)
            throw new IllegalArgumentException("setPersistentConfiguration: null context");
        if (json == null)
            throw new ApproovException("setPersistentConfiguration: no configuration");

        // the options are checked as strictly as the bundled file, and a leading byte order mark is ignored as
        // it is in the file
        String text = ((json.length() > 0) && (json.charAt(0) == 0xFEFF)) ? json.substring(1) : json;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_SIZE)
            throw new ApproovException("setPersistentConfiguration: larger than 64 KiB");
        if (source != null) {
            if (ApproovConfigFile.Json.hasLoneSurrogate(source))
                throw new ApproovException("setPersistentConfiguration: source not valid Unicode");
            if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_SIZE)
                throw new ApproovException("setPersistentConfiguration: source longer than 1024 bytes");
        }

        // the bundled file, read before the lock as initialize reads it, for its hash and for the options checks
        byte[] bundled;
        try {
            bundled = ApproovConfigFile.readAsset(context);
        } catch (ApproovException e) {
            throw new ApproovException("setPersistentConfiguration: " + e.getMessage());
        }
        String bundledHash = (bundled != null) ? sha256(bundled) : null;
        Map<String, Object> options;
        try {
            options = ApproovConfigFile.parsePersistentOptions(text,
                    (bundled != null) ? ApproovConfigFile.rootOf(bundled) : null);
        } catch (ApproovException e) {
            throw new ApproovException("setPersistentConfiguration: " + e.getMessage());
        }

        // the record holds the options exactly as given, which the check above showed to be one JSON object
        String appVersion = appVersion(context);
        String record = "{\"" + V + "\":" + VERSION
                + ",\"" + SOURCE + "\":" + ((source != null) ? ApproovConfigFile.Json.quote(source) : "null")
                + ",\"" + BUNDLED_HASH + "\":" + ((bundledHash != null) ? ("\"" + bundledHash + "\"") : "null")
                + ",\"" + APP_VERSION + "\":"
                + ((appVersion != null) ? ApproovConfigFile.Json.quote(appVersion) : "null")
                + ",\"" + OPTIONS + "\":" + text.trim() + "}";
        byte[] content = record.getBytes(StandardCharsets.UTF_8);
        synchronized (LOCK) {
            // the configuration in force for this process is read before it can be replaced, so that a record
            // stored now applies only from the next launch
            readForLaunch(context, bundled, true);

            // an identical record is not written again
            File file;
            try {
                file = recordFile(context);
            } catch (ApproovException e) {
                throw new ApproovException("setPersistentConfiguration: " + e.getMessage());
            }
            byte[] existing = readFile(file);
            if ((existing != null) && Arrays.equals(existing, content)) {
                ApproovLog.d(TAG, "setPersistentConfiguration: unchanged");
                return;
            }
            writeAtomically(file, content);
        }
        ApproovLog.i(TAG, "persistent configuration stored, applies at the next launch");
        if (ApproovLog.isDebugEnabled()) {
            StringBuilder keys = new StringBuilder();
            for (String key : new TreeSet<>(options.keySet()))
                keys.append((keys.length() == 0) ? "" : ", ").append(ApproovConfigFile.loggableKey(key));
            ApproovLog.d(TAG, "setPersistentConfiguration keys: " + keys);
        }
    }

    /**
     * Deletes any stored persistent configuration. Nothing changes in this process: the next launch uses the
     * bundled file alone. The bundled file is not read.
     *
     * @param context is any context of the app
     * @throws ApproovException if the record exists and cannot be deleted
     */
    static void clear(Context context) throws ApproovException {
        if (context == null)
            throw new IllegalArgumentException("clearPersistentConfiguration: null context");
        boolean deleted;
        synchronized (LOCK) {
            // the configuration in force for this process is read before it is deleted, so this process keeps it;
            // it is checked against the bundled file when initialize reads that file
            readForLaunch(context, null, false);
            File file;
            try {
                file = recordFile(context);
            } catch (ApproovException e) {
                throw new ApproovException("clearPersistentConfiguration: " + e.getMessage());
            }
            deleteStaleTempFiles(file.getParentFile());
            deleted = file.exists();
            if (deleted && !file.delete() && file.exists())
                throw new ApproovException("clearPersistentConfiguration: delete failed");
        }
        if (deleted)
            ApproovLog.i(TAG, "persistent configuration cleared, the next launch uses the bundled approov.config");
    }

    /**
     * Gets the source tag of the stored persistent configuration.
     *
     * @param context is any context of the app
     * @return the source tag, or null if there is no valid record or it has no source tag
     */
    static String getSource(Context context) {
        if (context == null)
            throw new IllegalArgumentException("getPersistentConfigurationSource: null context");
        synchronized (LOCK) {
            try {
                byte[] content = readFile(recordFile(context));
                return (content != null) ? parseRecord(content).source : null;
            } catch (ApproovException | InvalidRecordException e) {
                return null;
            }
        }
    }

    /**
     * Gets the persistent configuration in force for this process: the record stored before the process first
     * looked at it. The first call reads the record and deletes it if it cannot be used, or if it was stored with
     * a bundled file other than the current one or by another version of the app; later calls return the same
     * answer whatever was stored since.
     * The lines about a deleted record are returned rather than logged, for initialize to log them after the
     * logging level of the options.
     *
     * @param context is any context of the app
     * @param bundled is the content of the bundled file, or null if there is none
     * @return the persistent configuration in force, if any, and the line about one deleted, if any
     */
    static Launch forLaunch(Context context, byte[] bundled) {
        synchronized (LOCK) {
            readForLaunch(context, bundled, true);
            return new Launch(launchStored, launchNotice);
        }
    }

    // reads the record in force for this process once, under the lock, and checks it against the bundled file
    // when that is known; a record that cannot be used is deleted only while it is still the one on the device
    private static void readForLaunch(Context context, byte[] bundled, boolean bundledKnown) {
        if (launchRead) {
            if (bundledKnown && (launchRecord != null))
                checkForLaunch(bundled, appVersion(context), null);
            return;
        }
        launchRead = true;
        File file;
        try {
            file = recordFile(context);
        } catch (ApproovException e) {
            return;
        }
        byte[] content = readFile(file);
        if (content == null)
            return;

        // a record that cannot be used is deleted, so it is reported once
        try {
            launchRecord = parseRecord(content);
        } catch (InvalidRecordException e) {
            delete(file);
            launchNotice = new Notice(true, "persistent configuration deleted: " + e.getMessage());
            return;
        }
        if (bundledKnown)
            checkForLaunch(bundled, appVersion(context), file);
    }

    // checks the record of this process against the bundled file and the version of the app, deleting the file
    // given if it does not match
    private static void checkForLaunch(byte[] bundled, String appVersion, File file) {
        Record record = launchRecord;
        launchRecord = null;

        // a record stored with another bundled file belongs to an earlier release of the app
        String bundledHash = (bundled != null) ? sha256(bundled) : null;
        boolean sameBundle = (bundledHash == null) ? (record.bundledHash == null)
                : bundledHash.equals(record.bundledHash);
        if (!sameBundle) {
            if (file != null)
                delete(file);
            launchNotice = new Notice(false, "persistent configuration deleted: store release changed the bundled "
                    + ApproovConfigFile.ASSET_NAME);
            return;
        }

        // a record stored by another version of the app, even one that bundles the same file, is not used
        if (!Objects.equals(appVersion, record.appVersion)) {
            if (file != null)
                delete(file);
            launchNotice = new Notice(false, "persistent configuration deleted: app version changed");
            return;
        }
        launchStored = new Stored(record.options, record.source);
    }

    /**
     * Gets the version of the app, as its version code and version name joined by "/". It never throws, so a
     * package manager that fails cannot fail initialize.
     *
     * @param context is any context of the app
     * @return the version of the app, or null if it cannot be read
     */
    @SuppressWarnings("deprecation")
    static String appVersion(Context context) {
        try {
            PackageManager manager = context.getPackageManager();
            if (manager == null)
                return null;
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
            if (info == null)
                return null;
            long code = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ? info.getLongVersionCode()
                    : info.versionCode;
            return code + "/" + ((info.versionName != null) ? info.versionName : "");
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    // parses a record, checking every part of it but not the values of the options, which are checked as they
    // are applied
    private static Record parseRecord(byte[] content) throws InvalidRecordException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidRecordException("unreadable");
        }
        Object value;
        try {
            value = new ApproovConfigFile.Json(text).parseDocument();
        } catch (IllegalArgumentException e) {
            throw new InvalidRecordException("unreadable");
        }
        if (!(value instanceof Map))
            throw new InvalidRecordException("not valid");
        Map<?, ?> record = (Map<?, ?>) value;

        // the version first, so a record of another format is reported as such
        Object version = record.get(V);
        if (!(version instanceof Double) || ((Double) version != VERSION))
            throw new InvalidRecordException("unknown version");
        Object source = record.get(SOURCE);
        Object bundledHash = record.get(BUNDLED_HASH);
        Object appVersion = record.get(APP_VERSION);
        Object options = record.get(OPTIONS);
        if (!(source instanceof String) && (source != ApproovConfigFile.JSON_NULL))
            throw new InvalidRecordException("not valid");
        if (!(bundledHash instanceof String) && (bundledHash != ApproovConfigFile.JSON_NULL))
            throw new InvalidRecordException("not valid");

        // a record without the version of the app is one whose version is not known
        if ((appVersion != null) && !(appVersion instanceof String) && (appVersion != ApproovConfigFile.JSON_NULL))
            throw new InvalidRecordException("not valid");
        if (!(options instanceof Map))
            throw new InvalidRecordException("not valid");
        @SuppressWarnings("unchecked")
        Map<String, Object> optionsMap = (Map<String, Object>) options;
        for (String key : ApproovConfigFile.FILE_ONLY_KEYS) {
            if (optionsMap.containsKey(key))
                throw new InvalidRecordException("not valid");
        }
        return new Record((source instanceof String) ? (String) source : null,
                (bundledHash instanceof String) ? (String) bundledHash : null,
                (appVersion instanceof String) ? (String) appVersion : null, optionsMap);
    }

    // the record file in the no-backup files of the app
    private static File recordFile(Context context) throws ApproovException {
        File base;
        try {
            base = context.getNoBackupFilesDir();
        } catch (RuntimeException e) {
            base = null;
        }
        if (base == null)
            throw new ApproovException("no files directory");
        return new File(new File(base, DIRECTORY), FILE_NAME);
    }

    // reads a record file, or null if it is missing, unreadable or too large to be a record
    private static byte[] readFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if ((out.size() + count) > MAX_RECORD_SIZE)
                    return new byte[0];
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return new byte[0];
        }
    }

    // writes the record to a temporary file, flushed to the device, then renames it over the record, so that a
    // failure at any point leaves the previous record whole
    private static void writeAtomically(File file, byte[] content) throws ApproovException {
        File directory = file.getParentFile();
        if ((directory == null) || (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()))
            throw new ApproovException("setPersistentConfiguration: cannot create the directory");
        deleteStaleTempFiles(directory);

        // a temporary file of its own for each write, so that two processes of the app never share one
        File temp;
        try {
            temp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory);
        } catch (IOException | RuntimeException e) {
            throw new ApproovException("setPersistentConfiguration: write failed");
        }
        try (FileOutputStream out = new FileOutputStream(temp)) {
            if (failWriteForTesting) {
                out.write(content, 0, content.length / 2);
                throw new IOException("write failed for testing");
            }
            out.write(content);
            out.flush();
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            delete(temp);
            throw new ApproovException("setPersistentConfiguration: write failed");
        }
        if (!temp.renameTo(file)) {
            delete(temp);
            throw new ApproovException("setPersistentConfiguration: write failed");
        }
    }

    // deletes the temporary files a write stopped by the end of its process left in the directory of the record;
    // a write of this process never runs at the same time, as both hold the lock, and only files older than
    // STALE_TEMP_AGE_MS are deleted, so that a write running in another process of the app is never hit
    private static void deleteStaleTempFiles(File directory) {
        File[] files = (directory != null) ? directory.listFiles() : null;
        if (files == null)
            return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX) && file.isFile()
                    && ((now - file.lastModified()) > STALE_TEMP_AGE_MS))
                delete(file);
        }
    }

    // deletes a file, logging a failure
    private static void delete(File file) {
        if (!file.delete() && file.exists())
            ApproovLog.w(TAG, "persistent configuration: delete failed");
    }

    // the SHA-256 of the content in lowercase hex
    static String sha256(byte[] content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(content))
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    // forgets the persistent configuration read for this process, as if the app were launched again
    static void resetForTesting() {
        synchronized (LOCK) {
            launchRead = false;
            launchRecord = null;
            launchStored = null;
            launchNotice = null;
            failWriteForTesting = false;
        }
    }
}
