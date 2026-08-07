package com.kamyarps.tuneslink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class SseParserTest {
    @Test
    public void parsesSplitBuffersCrLfCommentsAndMultipleDataLines() throws Exception {
        SseParser parser = new SseParser();
        List<SseParser.Event> events = new ArrayList<>();
        byte[] bytes = (": heartbeat\r\nid: 42\r\nevent: state\r\n"
                + "data: {\"title\":\"Café\",\r\n"
                + "data: \"playing\":true}\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) parser.accept(new byte[]{value}, 0, 1, events::add);
        assertEquals(1, events.size());
        assertEquals("state", events.get(0).name());
        assertEquals("42", events.get(0).id());
        assertEquals("{\"title\":\"Café\",\n\"playing\":true}", events.get(0).data());
    }

    @Test
    public void ignoresMalformedEventWithoutDataAndParsesUnauthorized() throws Exception {
        SseParser parser = new SseParser();
        List<SseParser.Event> events = new ArrayList<>();
        byte[] bytes = "event: state\nunknown\n\nevent: unauthorized\ndata: {}\n\n"
                .getBytes(StandardCharsets.UTF_8);
        parser.accept(bytes, 0, bytes.length, events::add);
        assertEquals(1, events.size());
        assertEquals("unauthorized", events.get(0).name());
    }

    @Test
    public void rejectsOversizedEvents() {
        SseParser parser = new SseParser();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes("data: ".getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(new byte[SseParser.MAX_EVENT_BYTES]);
        assertThrows(IOException.class,
                () -> parser.accept(bytes.toByteArray(), 0, bytes.size(), ignored -> { }));
    }
}
