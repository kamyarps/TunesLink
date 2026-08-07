package com.kamyarps.tuneslink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Incremental, dependency-free parser for the subset of SSE used by TunesLink. */
final class SseParser {
    static final int MAX_EVENT_BYTES = 64 * 1024;

    record Event(String name, String data, String id) {}

    interface Sink {
        void event(Event event) throws IOException;
    }

    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private String eventName = "message";
    private String eventId = "";
    private int eventBytes;
    private boolean skipLf;

    void accept(byte[] bytes, int offset, int length, Sink sink) throws IOException {
        if (bytes == null || offset < 0 || length < 0 || offset + length > bytes.length)
            throw new IllegalArgumentException("Invalid SSE buffer range");
        for (int index = offset; index < offset + length; index++) {
            int value = bytes[index] & 0xff;
            if (skipLf) {
                skipLf = false;
                if (value == '\n') continue;
            }
            if (value == '\r' || value == '\n') {
                processLine(sink);
                if (value == '\r') skipLf = true;
                continue;
            }
            line.write(value);
            eventBytes++;
            if (eventBytes > MAX_EVENT_BYTES) throw new IOException("SSE event is too large");
        }
    }

    private void processLine(Sink sink) throws IOException {
        String value = decode(line.toByteArray());
        line.reset();
        if (value.isEmpty()) {
            if (data.length() > 0) {
                data.setLength(data.length() - 1);
                sink.event(new Event(eventName, data.toString(), eventId));
            }
            data.setLength(0);
            eventName = "message";
            eventBytes = 0;
            return;
        }
        if (value.charAt(0) == ':') return;
        int separator = value.indexOf(':');
        String field = separator < 0 ? value : value.substring(0, separator);
        String fieldValue = separator < 0 ? "" : value.substring(separator + 1);
        if (fieldValue.startsWith(" ")) fieldValue = fieldValue.substring(1);
        switch (field) {
            case "event" -> eventName = fieldValue;
            case "data" -> data.append(fieldValue).append('\n');
            case "id" -> {
                if (fieldValue.indexOf('\0') < 0) eventId = fieldValue;
            }
            default -> { }
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("SSE contains invalid UTF-8", exception);
        }
    }
}
