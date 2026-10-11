package com.klywo.hermeswatch.tts;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

/** Pure bounds policy: mandatory length, body maximum, MP3 content type, deadline. */
public class TtsHttpBoundsTest {
    @Test public void frozenBoundsAreExact() {
        assertEquals(262144, TtsHttp.MAX_BODY_BYTES);
        assertEquals(15000, TtsHttp.DOWNLOAD_DEADLINE_MS);
        assertEquals(5000, TtsHttp.CONNECT_TIMEOUT_MS);
        assertEquals("audio/mpeg", TtsHttp.AUDIO_CONTENT_TYPE);
    }

    @Test public void contentLengthIsMandatoryAndBounded() throws Exception {
        try { TtsHttp.boundedContentLength(-1); fail("Missing length accepted"); } catch (IOException expected) { }
        try { TtsHttp.boundedContentLength(TtsHttp.MAX_BODY_BYTES + 1L); fail("Oversized length accepted"); } catch (IOException expected) { }
        assertEquals(0, TtsHttp.boundedContentLength(0));
        assertEquals(TtsHttp.MAX_BODY_BYTES, TtsHttp.boundedContentLength(TtsHttp.MAX_BODY_BYTES));
    }

    @Test public void contentMustBeMp3() throws Exception {
        try { TtsHttp.requireMp3ContentType(null); fail("Missing type accepted"); } catch (IOException expected) { }
        try { TtsHttp.requireMp3ContentType("text/html"); fail("Wrong type accepted"); } catch (IOException expected) { }
        try { TtsHttp.requireMp3ContentType("audio/mpeg4"); fail("Similar type accepted"); } catch (IOException expected) { }
        TtsHttp.requireMp3ContentType("audio/mpeg");
        TtsHttp.requireMp3ContentType("audio/mpeg; charset=binary");
        TtsHttp.requireMp3ContentType(" audio/mpeg ");
    }
}
