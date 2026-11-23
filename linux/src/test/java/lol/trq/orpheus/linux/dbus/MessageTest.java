/* SPDX-License-Identifier: MIT */
package lol.trq.orpheus.linux.dbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageTest {
    @Test
    void headerFieldsAndBodySurviveEncoding() throws Exception {
        Message call = Message.call(
                "org.example.Player",
                "/org/mpris/MediaPlayer2",
                "org.mpris.MediaPlayer2.Player",
                "SetPosition",
                "ox",
                "/track/9",
                42_000_000L);
        byte[] data = call.encode(77);
        assertEquals(data.length - 16, Message.remaining(Arrays.copyOf(data, 16)));
        Message back = Message.decode(data);
        assertEquals(Message.CALL, back.type());
        assertEquals(77, back.serial());
        assertEquals("org.example.Player", back.destination());
        assertEquals("/org/mpris/MediaPlayer2", back.path());
        assertEquals("SetPosition", back.member());
        assertEquals(List.of("/track/9", 42_000_000L), back.body());

        Message reply =
                Message.decode(new Message(Message.CALL, 77, 0, null, null, null, null, null, ":1.5", "", List.of())
                        .errorReply("org.example.Error.NotSupported", "no")
                        .encode(3));
        assertEquals(Message.ERROR, reply.type());
        assertEquals(77, reply.replySerial());
        assertEquals(":1.5", reply.destination());
        assertEquals("no", reply.text());
    }

    @Test
    void oversizedAndInconsistentMessagesAreRefused() throws Exception {
        byte[] data = Message.signal("/a", "a.b", "C", "s", "x").encode(1);
        byte[] header = Arrays.copyOf(data, 16);
        header[7] = 0x7F;
        assertThrows(WireException.class, () -> Message.remaining(header));
        byte[] wrongVersion = Arrays.copyOf(data, 16);
        wrongVersion[3] = 2;
        assertThrows(WireException.class, () -> Message.remaining(wrongVersion));
        byte[] unknownOrder = data.clone();
        unknownOrder[0] = 'x';
        assertThrows(WireException.class, () -> Message.decode(unknownOrder));
        assertThrows(WireException.class, () -> Message.decode(Arrays.copyOf(data, data.length - 1)));
        byte[] lying = data.clone();
        lying[4] = 1;
        assertThrows(WireException.class, () -> Message.decode(lying));
    }
}
