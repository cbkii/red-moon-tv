/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class RootProtocolTest {
    private static final String TOKEN = "12345678-1234-1234-1234-123456789abc";
    private interface Failing { void run() throws Exception; }
    private void rejects(Failing operation) throws Exception {
        try { operation.run(); fail("Invalid frame accepted"); } catch (IOException expected) {}
    }
    @Test public void only_fixed_commands_and_valid_slider_values_can_be_serialised() throws Exception {
        for (int operation = 0; operation <= 4; operation++) {
            RootProtocol.Request request = new RootProtocol.Request(1, TOKEN, operation, operation == 3 ? 66 : -1);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            RootProtocol.writeRequest(new DataOutputStream(bytes), request);
            RootProtocol.Request parsed = RootProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            assertEquals(operation, parsed.operation); assertEquals(request.value, parsed.value);
        }
        for (int value : new int[]{-1, 101, Integer.MAX_VALUE}) rejects(() -> new RootProtocol.Request(1, TOKEN, 3, value));
        rejects(() -> new RootProtocol.Request(1, TOKEN, 5, -1));
        rejects(() -> new RootProtocol.Request(1, TOKEN, 1, 66));
        rejects(() -> new RootProtocol.Request(0, TOKEN, 1, -1));
        rejects(() -> new RootProtocol.Request(1, "arbitrary command", 1, -1));
    }
    private byte[] reply(RootProtocol.Reply r) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RootProtocol.writeReply(new DataOutputStream(bytes), r); return bytes.toByteArray();
    }
    private RootProtocol.Reply good() {
        RootProtocol.Reply r = new RootProtocol.Reply();
        r.sequence = 1; r.token = TOKEN; r.uid = 0; r.pid = 42; r.worker = TOKEN; r.level = 66;
        return r;
    }
    private RootProtocol.Reply parse(byte[] bytes) throws Exception {
        return RootProtocol.readReply(new DataInputStream(new ByteArrayInputStream(bytes)), new RootProtocol.Request(1, TOKEN, 1, -1));
    }
    @Test public void stale_non_root_and_malformed_replies_are_rejected() throws Exception {
        assertEquals(66, parse(reply(good())).level);
        RootProtocol.Reply wrong = good(); wrong.uid = 2000; rejects(() -> parse(reply(wrong)));
        RootProtocol.Reply stale = good(); stale.sequence = 2; rejects(() -> parse(reply(stale)));
        RootProtocol.Reply badToken = good(); badToken.token = "00000000-0000-0000-0000-000000000000"; rejects(() -> parse(reply(badToken)));
        RootProtocol.Reply level = good(); level.level = 101; rejects(() -> parse(reply(level)));
        byte[] bytes = reply(good()); bytes[0] = 0; rejects(() -> parse(bytes));
        rejects(() -> parse(new byte[]{1, 2}));
    }
    @Test public void error_and_diagnostic_output_has_a_fixed_memory_bound() throws Exception {
        RootProtocol.Reply r = good();
        char[] huge = new char[100000]; java.util.Arrays.fill(huge, 'x');
        r.diagnostics = new String(huge); r.error = new String(huge);
        byte[] bytes = reply(r);
        assertTrue(bytes.length < 9000);
        RootProtocol.Reply result = parse(bytes);
        assertEquals(4096, result.diagnostics.length()); assertEquals(2048, result.error.length());
    }
    @Test public void boot_identity_never_accepts_wall_clock_or_process_approximations() {
        assertEquals(TOKEN, BootIdentity.validate(TOKEN.toUpperCase(java.util.Locale.ROOT)));
        assertNull(BootIdentity.validate("2026-10-31")); assertNull(BootIdentity.validate("1234"));
        assertNull(BootIdentity.validate(null)); assertNull(BootIdentity.validate(TOKEN + "\n"));
    }
}
