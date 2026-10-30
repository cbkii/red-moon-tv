/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.jmstudios.redmoon.tv;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Fixed, bounded pipe protocol. No shell, paths, vendor keys or arbitrary methods in requests. */
public final class RootProtocol {
    public static final int MAGIC = 0x41345233;
    public static final int HELLO = 0, READ = 1, CLAIM = 2, WRITE = 3, RELEASE = 4;
    private RootProtocol() {}
    public static final class Request {
        public final int sequence, operation, value;
        public final String token;
        public Request(int sequence, String token, int operation, int value) throws IOException {
            if (sequence < 1 || BootIdentity.validate(token) == null ||
                operation < HELLO || operation > RELEASE ||
                (operation == WRITE ? value < 0 || value > 100 : value != -1))
                throw new IOException("Invalid root request");
            this.sequence = sequence; this.token = token; this.operation = operation; this.value = value;
        }
    }
    public static void writeRequest(DataOutput out, Request request) throws IOException {
        out.writeInt(MAGIC); out.writeInt(request.sequence); out.writeUTF(request.token);
        out.writeInt(request.operation); out.writeInt(request.value);
    }
    public static Request readRequest(DataInput in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Root protocol mismatch");
        return new Request(in.readInt(), text(in, 36), in.readInt(), in.readInt());
    }
    public static final class Reply {
        public int sequence, uid, pid, level = -1;
        public String token = "", worker = "", context = "", diagnostics = "", error = "";
    }
    public static void writeReply(DataOutput out, Reply r) throws IOException {
        out.writeInt(MAGIC); out.writeInt(r.sequence); out.writeUTF(r.token);
        out.writeInt(r.uid); out.writeInt(r.pid); out.writeUTF(r.worker);
        out.writeInt(r.level); out.writeUTF(bound(r.context, 2048));
        out.writeUTF(bound(r.diagnostics, 4096)); out.writeUTF(bound(r.error, 2048));
    }
    public static Reply readReply(DataInput in, Request request) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Root reply protocol mismatch");
        Reply r = new Reply();
        r.sequence = in.readInt(); r.token = text(in, 36); r.uid = in.readInt(); r.pid = in.readInt();
        r.worker = text(in, 36); r.level = in.readInt(); r.context = text(in, 2048);
        r.diagnostics = text(in, 4096); r.error = text(in, 2048);
        if (r.sequence != request.sequence || !r.token.equals(request.token) || r.uid != 0 ||
            r.pid <= 0 || BootIdentity.validate(r.worker) == null || r.level < -1 || r.level > 100)
            throw new IOException("Unexpected root worker/reply identity");
        return r;
    }
    private static String text(DataInput in, int limit) throws IOException {
        String value = in.readUTF(); // readUTF itself has a 65535-byte allocation ceiling.
        if (value.length() > limit) throw new IOException("Oversized root field");
        return value;
    }
    private static String bound(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(limit, value.length()));
    }
}
