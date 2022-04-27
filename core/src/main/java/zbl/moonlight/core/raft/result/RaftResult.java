package zbl.moonlight.core.raft.result;

import zbl.moonlight.core.raft.request.Entry;

import java.nio.ByteBuffer;

public record RaftResult (int term, byte isSuccess) {
    public static final int RAFT_RESULT_LENGTH = 5;

    public static final byte SUCCESS = (byte) 0x01;
    public static final byte FAILURE = (byte) 0x02;

    public static Entry fromBytes(byte[] bytes) {
        return null;
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(RAFT_RESULT_LENGTH);
        return buffer.putInt(term).put(isSuccess).array();
    }
}
