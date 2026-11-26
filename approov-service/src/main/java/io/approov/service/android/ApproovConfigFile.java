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
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * ApproovConfigFile holds what the approov.config file bundled in the app assets gives initialize: the
 * account ID, the comment and the options to apply, each applied through the public setter named by its
 * key. The file is a JSON object, or a 3.5.x file whose first non-blank line is the account ID and which
 * carries no options. A persistent configuration stored by the app may replace the options of the file key
 * by key. Reading it never logs anything: the problems found are kept as lines naming keys only, so that
 * neither the account ID nor any other value of the file reaches the log.
 */
final class ApproovConfigFile {
    // the name of the file in the app assets
    static final String ASSET_NAME = "approov.config";

    // the name logged for a key that comes from a persistent configuration
    static final String PERSISTENT_NAME = ASSET_NAME + " (persistent)";

    // the largest file read, far above a full signed account configuration
    static final int MAX_SIZE = 1024 * 1024;

    // the deepest nesting of JSON objects and arrays accepted
    static final int MAX_DEPTH = 128;

    // the deepest nesting accepted in the options of a persistent configuration, the options object counted as
    // one, below MAX_DEPTH so that the record, which wraps the options in one more object, can always be read back
    static final int MAX_PERSISTENT_DEPTH = 126;

    // the byte order mark, ignored at the start of the file
    private static final char BYTE_ORDER_MARK = 0xFEFF;

    // the keys of the file
    static final String CONFIG = "config";
    static final String COMMENT = "comment";
    static final String DEV_KEY = "devKey";
    static final String TOKEN_HEADER = "tokenHeader";
    static final String TRACE_ID_HEADER = "traceIDHeader";
    static final String STATUS_HEADER = "statusHeader";
    static final String BINDING_HEADER = "bindingHeader";
    static final String MUTATOR = "mutator";
    static final String MESSAGE_SIGNING = "messageSigning";
    static final String LOG_LEVEL = "logLevel";
    static final String SUBSTITUTION_HEADERS = "substitutionHeaders";
    static final String SUBSTITUTION_QUERY_PARAMS = "substitutionQueryParams";
    static final String EXCLUSION_URL_REGEXES = "exclusionURLRegexes";

    // the keys a persistent configuration may not hold, as only the bundled file sets the account
    static final List<String> FILE_ONLY_KEYS = Collections.unmodifiableList(Arrays.asList(CONFIG, COMMENT, DEV_KEY));

    // every key this version knows
    private static final Set<String> KNOWN_KEYS = new HashSet<>(Arrays.asList(CONFIG, COMMENT, DEV_KEY,
            TOKEN_HEADER, TRACE_ID_HEADER, STATUS_HEADER, BINDING_HEADER, MUTATOR, MESSAGE_SIGNING, LOG_LEVEL,
            SUBSTITUTION_HEADERS, SUBSTITUTION_QUERY_PARAMS, EXCLUSION_URL_REGEXES));

    // the members of a tokenHeader object
    private static final String NAME = "name";
    private static final String PREFIX = "prefix";

    // the JSON null, as distinct from a missing key
    static final Object JSON_NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    // Action applies one option through a public setter
    interface Action {
        void apply() throws Exception;
    }

    /**
     * Option is one setting from the file: its key, the line logged if its setter rejects it and the
     * setter call.
     */
    static final class Option {
        // the key of the file the option comes from
        final String key;

        // the error line logged if the setter rejects the option, naming the key only
        final String rejected;

        // the setter call
        final Action action;

        Option(String key, String rejected, Action action) {
            this.key = key;
            this.rejected = rejected;
            this.action = action;
        }
    }

    /**
     * Diagnostic is a line about the file logged when its options are applied: an unknown key at INFO or
     * a skipped value at ERROR. It names keys only, never a value.
     */
    static final class Diagnostic {
        // true for an ERROR line, false for an INFO line
        final boolean error;

        // the key and what is wrong with it, naming keys only
        final String detail;

        // the line, naming keys only
        final String message;

        Diagnostic(boolean error, String detail, String message) {
            this.error = error;
            this.detail = detail;
            this.message = message;
        }
    }

    // the keys and values the options are parsed from
    private final Map<String, Object> root;

    // true if a persistent configuration replaced options of the bundled file
    private boolean persistent;

    // the source tag of the persistent configuration, or null for none
    private String persistentSource;

    // the keys whose values come from a persistent configuration, named as such in the log
    private final Set<String> persistentKeys;

    // the line about a persistent configuration deleted instead of applied, logged with the options, or null
    private ApproovPersistentConfiguration.Notice persistentNotice;

    // the account ID, empty for bypass mode
    private final String config;

    // the comment passed to the SDK, or null for none
    private final String comment;

    // the logging level, applied before anything else, or null if the file sets none
    private ApproovLogLevel logLevel;

    // the options other than the logging level, in the order they are applied
    private final List<Option> options = new ArrayList<>();

    // the lines about unknown keys and skipped values, in the order they are found
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    // a 3.5.x file: the account ID only
    private ApproovConfigFile(String config) {
        this.root = new LinkedHashMap<>();
        this.root.put(CONFIG, config);
        this.persistentKeys = Collections.emptySet();
        this.config = config;
        this.comment = null;
    }

    // a JSON file
    private ApproovConfigFile(Map<String, Object> root) throws ApproovException {
        this(root, Collections.<String>emptySet());
    }

    // a JSON file whose given keys come from a persistent configuration
    private ApproovConfigFile(Map<String, Object> root, Set<String> persistentKeys) throws ApproovException {
        this.root = root;
        this.persistentKeys = persistentKeys;

        // the account ID is required, so a missing or wrong config fails the whole file
        Object value = root.get(CONFIG);
        if ((value == null) || (value == JSON_NULL))
            throw new ApproovException(ASSET_NAME + " no config");
        if (!(value instanceof String))
            throw new ApproovException(ASSET_NAME + " config: wrong type");
        config = (String) value;

        // the comment is a string, or null for none
        Object commentValue = root.get(COMMENT);
        if ((commentValue == null) || (commentValue == JSON_NULL) || (commentValue instanceof String)) {
            comment = (commentValue instanceof String) ? (String) commentValue : null;
        } else {
            comment = null;
            skip(COMMENT, "wrong type");
        }

        // the options in the order they are applied; the binding header comes after the substitution headers,
        // so a binding header that is also a substitution header is the one skipped
        parseLogLevel(root);
        parseDevKey(root);
        parseTokenHeader(root);
        parseNullableHeader(root, TRACE_ID_HEADER);
        parseNullableHeader(root, STATUS_HEADER);
        parseMutator(root);
        parseMessageSigning(root);
        parseSubstitutionHeaders(root);
        parseStringList(root, SUBSTITUTION_QUERY_PARAMS);
        parseStringList(root, EXCLUSION_URL_REGEXES);
        parseNullableHeader(root, BINDING_HEADER);

        // keys this version does not know are ignored, so a newer file still initializes
        for (String key : new TreeSet<>(root.keySet())) {
            if (!KNOWN_KEYS.contains(key))
                unknownKey(key);
        }
    }

    /**
     * Reads and parses the file from the app assets.
     *
     * @param context is the context whose assets hold the file
     * @return the parsed file
     * @throws ApproovException if the file is missing, cannot be read, is not valid or has no config
     */
    static ApproovConfigFile read(Context context) throws ApproovException {
        byte[] content = readAsset(context);
        if (content == null)
            throw new ApproovException(ASSET_NAME + " not found in the app assets");
        return parse(content);
    }

    /**
     * Reads the bytes of the file from the app assets.
     *
     * @param context is the context whose assets hold the file
     * @return the content of the file, or null if the app assets have no such file
     * @throws ApproovException if the file cannot be read or is larger than 1 MiB
     */
    static byte[] readAsset(Context context) throws ApproovException {
        AssetManager assets = context.getAssets();
        if (assets == null)
            throw new ApproovException(ASSET_NAME + " unreadable");
        try (InputStream in = assets.open(ASSET_NAME)) {
            return readAll(in);
        } catch (TooLargeException e) {
            throw new ApproovException(ASSET_NAME + " larger than 1 MiB");
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            // never the exception message, which may quote what was read
            throw new ApproovException(ASSET_NAME + " unreadable");
        }
    }

    // TooLargeException reports a file above MAX_SIZE, which is read no further
    private static final class TooLargeException extends IOException {
    }

    // reads the whole stream, up to MAX_SIZE
    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null)
            throw new IOException("no stream");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if ((out.size() + count) > MAX_SIZE)
                throw new TooLargeException();
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    /**
     * Parses the content of the file. Content whose first character other than whitespace is not "{" is a
     * 3.5.x file, whose first non-blank line is the account ID. A 3.5.x account ID is never valid JSON on its
     * own and never holds "{" or a quote, so content starting with "[", content that is a JSON string,
     * number, true, false or null, and a line holding "{" or a quote are rejected instead.
     *
     * @param content is the content of the file
     * @return the parsed file
     * @throws ApproovException if the content is not valid or has no config
     */
    static ApproovConfigFile parse(byte[] content) throws ApproovException {
        if (content.length > MAX_SIZE)
            throw new ApproovException(ASSET_NAME + " larger than 1 MiB");
        String text = decode(content);

        // the first character that is not Unicode whitespace decides the format, and the same whitespace is
        // trimmed from the 3.5.x line, so content starting with "{" or "[" is never a 3.5.x file
        int start = 0;
        while ((start < text.length()) && isUnicodeWhitespace(text.charAt(start)))
            start++;
        if (start == text.length())
            throw new ApproovException(ASSET_NAME + " no config");
        char first = text.charAt(start);
        if (first == '{')
            return parseObject(text);
        if (first == '[')
            throw new ApproovException(ASSET_NAME + " not a JSON object");

        // a top-level JSON scalar is not a 3.5.x account ID, which starts with "#" or is a JWT
        try {
            new Json(text).check();
            throw new ApproovException(ASSET_NAME + " not a JSON object");
        } catch (Json.NumberOutOfRangeException e) {
            throw new ApproovException(ASSET_NAME + " not valid JSON");
        } catch (IllegalArgumentException e) {
            // not JSON, so a 3.5.x file
        }

        // a 3.5.x file holds the account ID on its first non-blank line and no options
        int end = start;
        while ((end < text.length()) && !isLineBreak(text.charAt(end)))
            end++;
        while (isUnicodeWhitespace(text.charAt(end - 1)))
            end--;
        String line = text.substring(start, end);

        // an account ID never holds "{" or a quote, so such a line is broken JSON, for example behind an
        // invisible character, and is never passed to the SDK
        if ((line.indexOf('{') >= 0) || (line.indexOf('"') >= 0))
            throw new ApproovException(ASSET_NAME + " not valid JSON");
        return new ApproovConfigFile(line);
    }

    // a JSON file, rejected whole if it is not exactly one valid JSON object
    private static ApproovConfigFile parseObject(String text) throws ApproovException {
        Map<String, Object> root;
        try {
            root = new Json(text).parseObjectDocument();
        } catch (Json.DuplicateKeyException e) {
            throw new ApproovException(ASSET_NAME + " duplicate key " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException(ASSET_NAME + " not valid JSON");
        }
        return new ApproovConfigFile(root);
    }

    // determines whether a character is Unicode White_Space
    static boolean isUnicodeWhitespace(char c) {
        return ((c >= '\t') && (c <= '\r')) || (c == 0x85) || Character.isSpaceChar(c);
    }

    // determines whether a character ends a line
    private static boolean isLineBreak(char c) {
        return ((c >= '\n') && (c <= '\r')) || (c == 0x85) || (c == 0x2028) || (c == 0x2029);
    }

    /**
     * Gets a key as it may be logged: quoted and escaped as a JSON string, so that no key can break or forge a
     * log line, and cut after 40 characters.
     *
     * @param key is the key as found in the file
     * @return the key for a log line or an error message
     */
    static String loggableKey(String key) {
        return loggable(key, 40);
    }

    /**
     * Gets a string as it may be logged: quoted and escaped as a JSON string, so that it can neither break nor
     * forge a log line, and cut after the given number of characters.
     *
     * @param value is the string to log
     * @param limit is the number of characters shown before the string is cut
     * @return the string for a log line or an error message
     */
    static String loggable(String value, int limit) {
        boolean cut = value.length() > limit;
        String shown = cut ? value.substring(0, limit) : value;
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < shown.length(); i++) {
            char c = shown.charAt(i);
            boolean pairedHigh = Character.isHighSurrogate(c) && ((i + 1) < shown.length())
                    && Character.isLowSurrogate(shown.charAt(i + 1));
            boolean pairedLow = Character.isLowSurrogate(c) && (i > 0)
                    && Character.isHighSurrogate(shown.charAt(i - 1));
            if (c == '"')
                out.append("\\\"");
            else if (c == '\\')
                out.append("\\\\");
            else if (c == '\b')
                out.append("\\b");
            else if (c == '\f')
                out.append("\\f");
            else if (c == '\n')
                out.append("\\n");
            else if (c == '\r')
                out.append("\\r");
            else if (c == '\t')
                out.append("\\t");
            else if ((c < 0x20) || (Character.isSurrogate(c) && !pairedHigh && !pairedLow))
                out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else
                out.append(c);
        }
        out.append('"');
        return cut ? (out + "...") : out.toString();
    }

    // the text of the content, which must be UTF-8, without any leading byte order mark
    private static String decode(byte[] content) throws ApproovException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ApproovException(ASSET_NAME + " not valid UTF-8");
        }
        if ((text.length() > 0) && (text.charAt(0) == BYTE_ORDER_MARK))
            text = text.substring(1);
        return text;
    }

    String getConfig() {
        return config;
    }

    String getComment() {
        return comment;
    }

    ApproovLogLevel getLogLevel() {
        return logLevel;
    }

    List<Option> getOptions() {
        return Collections.unmodifiableList(options);
    }

    List<Diagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    boolean isPersistent() {
        return persistent;
    }

    String getPersistentSource() {
        return persistentSource;
    }

    ApproovPersistentConfiguration.Notice getPersistentNotice() {
        return persistentNotice;
    }

    void setPersistentNotice(ApproovPersistentConfiguration.Notice notice) {
        persistentNotice = notice;
    }

    /**
     * Gets the keys and values of a file, as parsed, for checking a persistent configuration against it.
     *
     * @param content is the content of the file
     * @return the keys and values of the file, or null if the file is not valid
     */
    static Map<String, Object> rootOf(byte[] content) {
        try {
            return parse(content).root;
        } catch (ApproovException e) {
            return null;
        }
    }

    /**
     * Gets the file with the options of a persistent configuration in place of its own: each key of the
     * persistent configuration replaces the whole value of the same key in the file, so an object or a list
     * replaces the bundled one rather than adding to it. The account ID, comment and development key always
     * come from the file.
     *
     * @param options are the options of the persistent configuration
     * @param source is the source tag of the persistent configuration, or null for none
     * @return the file with the merged options
     * @throws ApproovException if there was a problem
     */
    ApproovConfigFile withPersistent(Map<String, Object> options, String source) throws ApproovException {
        Map<String, Object> merged = new LinkedHashMap<>(root);
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if (!FILE_ONLY_KEYS.contains(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
                keys.add(entry.getKey());
            }
        }
        ApproovConfigFile file = new ApproovConfigFile(merged, keys);
        file.persistent = true;
        file.persistentSource = source;
        return file;
    }

    /**
     * Parses the options of a persistent configuration, checking them as strictly as the file: the content must
     * be one valid JSON object nested no deeper than MAX_PERSISTENT_DEPTH, so that its record can be read back, it
     * may not hold the account ID, comment or development key, and every known key
     * must have a value its setter accepts, also against the bundled file. An unknown key is accepted. Error
     * messages name keys only, never values.
     *
     * @param text is the JSON text of the options
     * @param bundled is the keys and values of the bundled file, or null if there is no valid one
     * @return the options, as parsed
     * @throws ApproovException if the options are not valid
     */
    static Map<String, Object> parsePersistentOptions(String text, Map<String, Object> bundled)
            throws ApproovException {
        Object value;
        try {
            value = new Json(text, MAX_PERSISTENT_DEPTH).parseDocument();
        } catch (Json.DuplicateKeyException e) {
            throw new ApproovException("duplicate key " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApproovException("not valid JSON");
        }
        if (!(value instanceof Map))
            throw new ApproovException("not a JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) value;
        for (String key : FILE_ONLY_KEYS) {
            if (options.containsKey(key))
                throw new ApproovException(key + ": not allowed");
        }

        // each known key is checked as the file form checks it, with an account ID only to satisfy the parser
        Map<String, Object> root = new LinkedHashMap<>(options);
        root.put(CONFIG, "");
        ApproovConfigFile file = new ApproovConfigFile(root);
        for (Diagnostic diagnostic : file.diagnostics) {
            if (diagnostic.error)
                throw new ApproovException(diagnostic.detail);
        }

        // what only the setters find: a regular expression that does not compile and a binding header that is
        // also a substitution header
        Object regexes = options.get(EXCLUSION_URL_REGEXES);
        if (regexes instanceof List) {
            for (Object regex : (List<?>) regexes) {
                try {
                    Pattern.compile((String) regex);
                } catch (PatternSyntaxException e) {
                    throw new ApproovException(EXCLUSION_URL_REGEXES + ": invalid regular expression");
                }
            }
        }
        if (isBindingASubstitutionHeader(options))
            throw new ApproovException(BINDING_HEADER + ": a substitution header");

        // the same clash between a key of the options and a key the bundled file keeps
        if (bundled != null) {
            Map<String, Object> merged = new LinkedHashMap<>(bundled);
            merged.putAll(options);
            if (isBindingASubstitutionHeader(merged)) {
                if (options.containsKey(BINDING_HEADER))
                    throw new ApproovException(BINDING_HEADER + ": a substitution header");
                throw new ApproovException(SUBSTITUTION_HEADERS + ": element is the binding header");
            }
        }
        return options;
    }

    // determines whether the binding header of the keys is also one of their substitution headers
    private static boolean isBindingASubstitutionHeader(Map<String, Object> keys) {
        Object binding = keys.get(BINDING_HEADER);
        Object headers = keys.get(SUBSTITUTION_HEADERS);
        if ((binding instanceof String) && (headers instanceof Map)) {
            for (Object header : ((Map<?, ?>) headers).keySet()) {
                if (((String) binding).equalsIgnoreCase((String) header))
                    return true;
            }
        }
        return false;
    }

    // records a key or element skipped for a wrong type or value
    private void skip(String key, String reason) {
        diagnostics.add(new Diagnostic(true, key + ": " + reason,
                origin(key) + ": " + key + ": " + reason + ", skipped"));
    }

    // records a key that is ignored, a member of tokenHeader named as tokenHeader.member
    private void unknownKey(String key) {
        String origin = key.startsWith(TOKEN_HEADER + ".") ? origin(TOKEN_HEADER) : origin(key);
        diagnostics.add(new Diagnostic(false, "unknown key " + loggableKey(key),
                origin + ": unknown key " + loggableKey(key) + " ignored"));
    }

    // the line logged when a setter rejects a value
    private String rejected(String key, String reason) {
        return origin(key) + ": " + key + ": " + reason + ", skipped";
    }

    // the name logged for where the value of a key comes from: the bundled file or a persistent configuration
    private String origin(String key) {
        return persistentKeys.contains(key) ? PERSISTENT_NAME : ASSET_NAME;
    }

    // the logging level, one of the ApproovLogLevel names
    private void parseLogLevel(Map<String, Object> root) {
        Object value = root.get(LOG_LEVEL);
        if (value == null)
            return;
        if (!(value instanceof String)) {
            skip(LOG_LEVEL, "wrong type");
            return;
        }
        for (ApproovLogLevel level : ApproovLogLevel.values()) {
            if (level.name().equals(value)) {
                logLevel = level;
                return;
            }
        }
        skip(LOG_LEVEL, "unknown value");
    }

    // the development key, a non-empty string
    private void parseDevKey(Map<String, Object> root) {
        Object value = root.get(DEV_KEY);
        if (value == null)
            return;
        if (!(value instanceof String)) {
            skip(DEV_KEY, "wrong type");
            return;
        }
        String devKey = (String) value;
        if (devKey.isEmpty()) {
            skip(DEV_KEY, "empty value");
            return;
        }
        options.add(new Option(DEV_KEY, rejected(DEV_KEY, "rejected"), () -> ApproovService.setDevKey(devKey)));
    }

    // the token header, an object with a non-empty name and an optional prefix, empty by default
    private void parseTokenHeader(Map<String, Object> root) {
        Object value = root.get(TOKEN_HEADER);
        if (value == null)
            return;
        if (!(value instanceof Map)) {
            skip(TOKEN_HEADER, "wrong type");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> header = (Map<String, Object>) value;
        Object nameValue = header.get(NAME);
        if (!(nameValue instanceof String) || ((String) nameValue).isEmpty()) {
            skip(TOKEN_HEADER, "name missing or not a non-empty string");
            return;
        }
        String name = (String) nameValue;
        String prefix = "";
        if (header.containsKey(PREFIX)) {
            if (!(header.get(PREFIX) instanceof String)) {
                skip(TOKEN_HEADER, "prefix: wrong type");
                return;
            }
            prefix = (String) header.get(PREFIX);
        }
        for (String member : new TreeSet<>(header.keySet())) {
            if (!NAME.equals(member) && !PREFIX.equals(member))
                unknownKey(TOKEN_HEADER + "." + member);
        }
        String tokenPrefix = prefix;
        options.add(new Option(TOKEN_HEADER, rejected(TOKEN_HEADER, "rejected"),
                () -> ApproovService.setTokenHeader(name, tokenPrefix)));
    }

    // a header that null disables: the trace ID, status and binding headers
    private void parseNullableHeader(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value == null)
            return;
        String header = null;
        if (value != JSON_NULL) {
            if (!(value instanceof String)) {
                skip(key, "wrong type");
                return;
            }
            if (((String) value).isEmpty()) {
                skip(key, "empty name");
                return;
            }
            header = (String) value;
        }
        String name = header;
        if (TRACE_ID_HEADER.equals(key)) {
            options.add(new Option(key, rejected(key, "rejected"), () -> ApproovService.setTraceIDHeader(name)));
        } else if (STATUS_HEADER.equals(key)) {
            options.add(new Option(key, rejected(key, "rejected"), () -> ApproovService.setStatusHeader(name)));
        } else {
            options.add(new Option(key, rejected(key, "a substitution header"),
                    () -> ApproovService.setBindingHeader(name)));
        }
    }

    // the service mutator, by the name of a built-in mutator
    private void parseMutator(Map<String, Object> root) {
        Object value = root.get(MUTATOR);
        if (value == null)
            return;
        if (!(value instanceof String)) {
            skip(MUTATOR, "wrong type");
            return;
        }
        ApproovServiceMutator mutator;
        if ("CLOSE_FAILURE".equals(value)) {
            mutator = ApproovServiceMutator.CLOSE_FAILURE;
        } else if ("ALWAYS_PROCEED".equals(value)) {
            mutator = ApproovServiceMutator.ALWAYS_PROCEED;
        } else {
            skip(MUTATOR, "unknown value");
            return;
        }
        options.add(new Option(MUTATOR, rejected(MUTATOR, "rejected"), () -> ApproovService.setServiceMutator(mutator)));
    }

    // the message signing switch, a boolean
    private void parseMessageSigning(Map<String, Object> root) {
        Object value = root.get(MESSAGE_SIGNING);
        if (value == null)
            return;
        if (!(value instanceof Boolean)) {
            skip(MESSAGE_SIGNING, "wrong type");
            return;
        }
        if ((Boolean) value)
            options.add(new Option(MESSAGE_SIGNING, rejected(MESSAGE_SIGNING, "rejected"),
                    ApproovService::enableMessageSigning));
        else
            options.add(new Option(MESSAGE_SIGNING, rejected(MESSAGE_SIGNING, "rejected"),
                    ApproovService::disableMessageSigning));
    }

    // the substitution headers, an object of header name to required prefix, which may be empty, applied in
    // order of header name
    private void parseSubstitutionHeaders(Map<String, Object> root) {
        Object value = root.get(SUBSTITUTION_HEADERS);
        if (value == null)
            return;
        if (!(value instanceof Map)) {
            skip(SUBSTITUTION_HEADERS, "wrong type");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) value;
        for (String header : new TreeSet<>(headers.keySet())) {
            Object prefix = headers.get(header);
            if (header.isEmpty()) {
                skip(SUBSTITUTION_HEADERS, "element with an empty name skipped");
            } else if (!(prefix instanceof String)) {
                skip(SUBSTITUTION_HEADERS, "element with a prefix of the wrong type skipped");
            } else {
                options.add(new Option(SUBSTITUTION_HEADERS,
                        rejected(SUBSTITUTION_HEADERS, "element is the binding header"),
                        () -> ApproovService.addSubstitutionHeader(header, (String) prefix)));
            }
        }
    }

    // the substitution query parameters or the exclusion URL regular expressions (in JSON escaping), each an
    // array of non-empty strings
    private void parseStringList(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value == null)
            return;
        if (!(value instanceof List)) {
            skip(key, "wrong type");
            return;
        }
        for (Object element : (List<?>) value) {
            if (!(element instanceof String) || ((String) element).isEmpty()) {
                skip(key, "element that is not a non-empty string skipped");
                continue;
            }
            String name = (String) element;
            if (SUBSTITUTION_QUERY_PARAMS.equals(key)) {
                options.add(new Option(key, rejected(key, "rejected"),
                        () -> ApproovService.addSubstitutionQueryParam(name)));
            } else {
                options.add(new Option(key, rejected(key, "invalid regular expression"),
                        () -> ApproovService.addExclusionURLRegex(name)));
            }
        }
    }

    /**
     * Json is a strict JSON parser (RFC 8259) for one document. The platform org.json parser accepts
     * comments, unquoted strings and other syntax that is not JSON, and keeps the last of duplicate keys,
     * so it cannot reject a file that is not one valid JSON object. Keys are compared by exact code points.
     * Objects are LinkedHashMap, arrays ArrayList, numbers Double and null JSON_NULL. Error messages give
     * an offset only, never content, except a duplicate key, which is named.
     */
    static final class Json {
        // DuplicateKeyException reports a key found twice in one object, with the key as its message
        static final class DuplicateKeyException extends IllegalArgumentException {
            DuplicateKeyException(String key) {
                super(key);
            }
        }

        // the text being parsed
        private final String text;

        // the offset of the next character
        private int pos;

        // the deepest nesting of objects and arrays accepted
        private final int maxDepth;

        Json(String text) {
            this(text, MAX_DEPTH);
        }

        Json(String text, int maxDepth) {
            this.text = text;
            this.maxDepth = maxDepth;
        }

        // NumberOutOfRangeException reports a number that is not finite as a double
        static final class NumberOutOfRangeException extends IllegalArgumentException {
            NumberOutOfRangeException(String message) {
                super(message);
            }
        }

        // checks that the text is one valid JSON document of any type
        void check() {
            parseValueDocument();
        }

        // parses the text as one valid JSON document of any type
        Object parseDocument() {
            return parseValueDocument();
        }

        /**
         * Gets a string as a JSON string literal. Every character below 0x20, the quote and the backslash are
         * escaped; the string must hold no lone surrogate.
         *
         * @param value is the string
         * @return the JSON string literal
         */
        static String quote(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"')
                    out.append("\\\"");
                else if (c == '\\')
                    out.append("\\\\");
                else if (c < 0x20)
                    out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                else
                    out.append(c);
            }
            return out.append('"').toString();
        }

        /**
         * Determines whether a string holds a lone surrogate, which no JSON text read by this parser can hold.
         *
         * @param value is the string
         * @return true if a surrogate is not in a pair, false otherwise
         */
        static boolean hasLoneSurrogate(String value) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c) && ((i + 1) < value.length())
                        && Character.isLowSurrogate(value.charAt(i + 1))) {
                    i++;
                } else if (Character.isSurrogate(c)) {
                    return true;
                }
            }
            return false;
        }

        // the offset of the first character at or after the start that is not JSON whitespace
        static int skipWhitespace(String text, int start) {
            int i = start;
            while ((i < text.length()) && isWhitespace(text.charAt(i)))
                i++;
            return i;
        }

        private static boolean isWhitespace(char c) {
            return (c == ' ') || (c == '\t') || (c == '\n') || (c == '\r');
        }

        // parses a document that must be one object
        @SuppressWarnings("unchecked")
        Map<String, Object> parseObjectDocument() {
            Object value = parseValueDocument();
            if (!(value instanceof Map))
                throw new IllegalArgumentException("not an object");
            return (Map<String, Object>) value;
        }

        // parses a document of one value of any type
        private Object parseValueDocument() {
            pos = skipWhitespace(text, 0);
            Object value = parseValue(0);
            pos = skipWhitespace(text, pos);
            if (pos != text.length())
                throw error("content after the end");
            return value;
        }

        private IllegalArgumentException error(String reason) {
            return new IllegalArgumentException(reason + " at offset " + pos);
        }

        // parses a value inside the given number of objects and arrays
        private Object parseValue(int depth) {
            if (pos >= text.length())
                throw error("unexpected end");
            char c = text.charAt(pos);
            if (c == '{')
                return parseObject(depth + 1);
            if (c == '[')
                return parseArray(depth + 1);
            if (c == '"')
                return parseString();
            if ((c == '-') || ((c >= '0') && (c <= '9')))
                return parseNumber();
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (text.startsWith("null", pos)) {
                pos += 4;
                return JSON_NULL;
            }
            throw error("unexpected character");
        }

        private Map<String, Object> parseObject(int depth) {
            if (depth > maxDepth)
                throw error("nesting too deep");
            Map<String, Object> object = new LinkedHashMap<>();
            pos++;
            pos = skipWhitespace(text, pos);
            if ((pos < text.length()) && (text.charAt(pos) == '}')) {
                pos++;
                return object;
            }
            while (true) {
                pos = skipWhitespace(text, pos);
                if ((pos >= text.length()) || (text.charAt(pos) != '"'))
                    throw error("expected a string key");
                String key = parseString();
                if (object.containsKey(key))
                    throw new DuplicateKeyException(loggableKey(key));
                pos = skipWhitespace(text, pos);
                expect(':');
                pos = skipWhitespace(text, pos);
                object.put(key, parseValue(depth));
                pos = skipWhitespace(text, pos);
                if ((pos < text.length()) && (text.charAt(pos) == ',')) {
                    pos++;
                    continue;
                }
                expect('}');
                return object;
            }
        }

        private List<Object> parseArray(int depth) {
            if (depth > maxDepth)
                throw error("nesting too deep");
            List<Object> array = new ArrayList<>();
            pos++;
            pos = skipWhitespace(text, pos);
            if ((pos < text.length()) && (text.charAt(pos) == ']')) {
                pos++;
                return array;
            }
            while (true) {
                pos = skipWhitespace(text, pos);
                array.add(parseValue(depth));
                pos = skipWhitespace(text, pos);
                if ((pos < text.length()) && (text.charAt(pos) == ',')) {
                    pos++;
                    continue;
                }
                expect(']');
                return array;
            }
        }

        private void expect(char c) {
            if ((pos >= text.length()) || (text.charAt(pos) != c))
                throw error("expected '" + c + "'");
            pos++;
        }

        private String parseString() {
            StringBuilder out = new StringBuilder();
            pos++;
            while (true) {
                if (pos >= text.length())
                    throw error("unterminated string");
                char c = text.charAt(pos++);
                if (c == '"')
                    return checkSurrogates(out.toString());
                if (c < 0x20) {
                    pos--;
                    throw error("control character in a string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length())
                    throw error("unterminated string");
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(escape);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        out.append(parseUnicodeEscape());
                        break;
                    default:
                        pos--;
                        throw error("invalid escape");
                }
            }
        }

        private char parseUnicodeEscape() {
            if ((pos + 4) > text.length())
                throw error("invalid unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = text.charAt(pos + i);
                int digit;
                if ((c >= '0') && (c <= '9'))
                    digit = c - '0';
                else if ((c >= 'a') && (c <= 'f'))
                    digit = c - 'a' + 10;
                else if ((c >= 'A') && (c <= 'F'))
                    digit = c - 'A' + 10;
                else
                    throw error("invalid unicode escape");
                value = (value << 4) | digit;
            }
            pos += 4;
            return (char) value;
        }

        // a decoded string with every surrogate in a pair; an escape can encode a lone one, which is rejected
        private String checkSurrogates(String value) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c) && ((i + 1) < value.length())
                        && Character.isLowSurrogate(value.charAt(i + 1))) {
                    i++;
                } else if (Character.isSurrogate(c)) {
                    throw error("lone surrogate");
                }
            }
            return value;
        }

        // parses a number, which must be finite as a double; no key uses a number, so only its validity matters
        private Double parseNumber() {
            int start = pos;
            if (text.charAt(pos) == '-')
                pos++;
            if ((pos < text.length()) && (text.charAt(pos) == '0')) {
                pos++;
            } else if (!digits()) {
                throw error("invalid number");
            }
            if ((pos < text.length()) && (text.charAt(pos) == '.')) {
                pos++;
                if (!digits())
                    throw error("invalid number");
            }
            if ((pos < text.length()) && ((text.charAt(pos) == 'e') || (text.charAt(pos) == 'E'))) {
                pos++;
                if ((pos < text.length()) && ((text.charAt(pos) == '+') || (text.charAt(pos) == '-')))
                    pos++;
                if (!digits())
                    throw error("invalid number");
            }
            double value = Double.parseDouble(text.substring(start, pos));
            if (Double.isInfinite(value))
                throw new NumberOutOfRangeException("number out of range at offset " + start);
            return value;
        }

        // consumes one or more decimal digits, returning false if there are none
        private boolean digits() {
            int start = pos;
            while ((pos < text.length()) && (text.charAt(pos) >= '0') && (text.charAt(pos) <= '9'))
                pos++;
            return pos > start;
        }
    }
}
