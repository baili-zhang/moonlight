package zbl.moonlight.core.raft.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import zbl.moonlight.core.raft.log.RaftLog;
import zbl.moonlight.core.raft.request.AppendEntries;
import zbl.moonlight.core.raft.request.Entry;
import zbl.moonlight.core.raft.request.RaftRequest;
import zbl.moonlight.core.raft.response.RaftResponse;
import zbl.moonlight.core.raft.state.StateMachine;
import zbl.moonlight.core.raft.state.RaftRole;
import zbl.moonlight.core.raft.state.RaftState;
import zbl.moonlight.core.socket.client.ServerNode;
import zbl.moonlight.core.socket.client.SocketClient;
import zbl.moonlight.core.socket.interfaces.SocketServerHandler;
import zbl.moonlight.core.socket.request.SocketRequest;
import zbl.moonlight.core.socket.response.SocketResponse;
import zbl.moonlight.core.socket.server.SocketServer;
import zbl.moonlight.core.utils.ByteBufferUtils;
import zbl.moonlight.core.utils.NumberUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static zbl.moonlight.core.raft.request.ClientRequest.RAFT_CLIENT_REQUEST_GET;
import static zbl.moonlight.core.raft.request.ClientRequest.RAFT_CLIENT_REQUEST_SET;

public class RaftServerHandler implements SocketServerHandler {
    private final static Logger logger = LogManager.getLogger("RaftServerHandler");

    private final RaftState raftState;
    private final SocketServer socketServer;
    private final SocketClient socketClient;
    private final LogIndexMap logIndexMap;
    private final StateMachine stateMachine;

    public RaftServerHandler(SocketServer server, StateMachine machine,
                             SocketClient client, RaftState state) {
        socketServer = server;
        stateMachine = machine;
        raftState = state;
        socketClient = client;
        logIndexMap = new LogIndexMap();
    }

    @Override
    public void handleRequest(SocketRequest request) throws Exception {
        byte[] data = request.data();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte method = buffer.get();

        switch (method) {
            case RaftRequest.REQUEST_VOTE ->
                    handleRequestVoteRpc(request.selectionKey(), buffer);
            case RaftRequest.APPEND_ENTRIES ->
                    handleAppendEntriesRpc(request.selectionKey(), buffer);
            case RaftRequest.CLIENT_REQUEST ->
                    handleClientRequest(request.selectionKey(), buffer);
        }
    }

    @Override
    public void handleAfterLatchAwait() {
        final int commitIndex = raftState.commitIndex();
        for(SelectionKey key : logIndexMap.keySet()) {
            Integer logIndex = logIndexMap.peek(key);
            while (logIndex != null && logIndex <= commitIndex) {
                byte[] data = RaftResponse.clientRequestSuccessWithoutResult();
                SocketResponse response = new SocketResponse(key, data, null);
                socketServer.offerInterruptibly(response);
                logIndex = logIndexMap.peekAfterPoll(key);
            }
        }
    }

    private void handleRequestVoteRpc(SelectionKey selectionKey, ByteBuffer buffer)
            throws IOException {
        String host = getString(buffer);
        int port = buffer.getInt();
        ServerNode candidate = new ServerNode(host, port);
        int term = buffer.getInt();
        int lastLogIndex = buffer.getInt();
        int lastLogTerm = buffer.getInt();

        int currentTerm = raftState.currentTerm();
        Entry lastEntry = raftState.lastEntry();

        logger.debug("Handle [RequestVote] RPC request: { candidate: {}, term: {}, " +
                "lastLogIndex: {}, lastLogTerm: {} }", candidate, term, lastLogIndex, lastLogTerm);

        if(term < currentTerm) {
            byte[] data = RaftResponse.requestVoteFailure(currentTerm, raftState.currentNode());
            sendResult(selectionKey, data);

            logger.info("[RequestVote: term({}) < currentTerm({})] -- [{}] " +
                            "-- Has not voted for candidate: {}.", term, currentTerm,
                    raftState.currentNode(), candidate);
            return;
        } else if(term > currentTerm) {
            raftState.setCurrentTerm(term);
            raftState.setRaftRole(RaftRole.Follower);
        }

        /* ?????? voteFor ????????????????????? setCurrentTerm ???????????? */
        ServerNode voteFor = raftState.voteFor();
        logger.debug("[{}] -- Current [voteFor] is {}",
                raftState.currentNode(), voteFor);

        if (voteFor == null || voteFor.equals(candidate)) {
            if(lastLogTerm > lastEntry.term()) {
                requestVoteSuccess(currentTerm, candidate, selectionKey);
                return;
            } else if (lastLogTerm == lastEntry.term()) {
                if(lastLogIndex >= raftState.indexOfLastLogEntry()) {
                    requestVoteSuccess(currentTerm, candidate, selectionKey);
                    return;
                }
            }
        }

        logger.info("[RequestVote: voteFor != null && voteFor.equals(candidate)] -- [{}] " +
                        "-- Has not voted for candidate: {}.",
                raftState.currentNode(), candidate);
        byte[] data = RaftResponse.requestVoteFailure(currentTerm, raftState.currentNode());
        sendResult(selectionKey, data);
    }

    private synchronized void requestVoteSuccess(int currentTerm, ServerNode candidate,
                                    SelectionKey selectionKey) throws IOException {
        ServerNode voteFor = raftState.voteFor();
        if (voteFor == null || voteFor.equals(candidate)) {
            byte[] data = RaftResponse.requestVoteSuccess(currentTerm, raftState.currentNode());
            raftState.setVoteFor(candidate);
            sendResult(selectionKey, data);
            /* ???????????????????????????????????????????????????????????????????????????????????????????????? Leader???????????????????????????????????? */
            raftState.resetElectionTimeout();
        }
    }

    private void handleAppendEntriesRpc(SelectionKey selectionKey, ByteBuffer buffer) throws IOException {
        String host = getString(buffer);
        int port = buffer.getInt();
        ServerNode leader = new ServerNode(host, port);
        int term = buffer.getInt();
        int prevLogIndex = buffer.getInt();
        int prevLogTerm = buffer.getInt();
        int leaderCommit = buffer.getInt();
        Entry[] entries = ByteBufferUtils.isOver(buffer)
                ? new Entry[0] : getEntries(buffer);

        ServerNode currentNode = raftState.currentNode();
        int currentTerm = raftState.currentTerm();
        Entry leaderPrevEntry = raftState.getEntryByIndex(prevLogIndex);

        if(entries.length != 0) {
            logger.info("[{}] -- [AppendEntries] -- leader={}, term={}, " +
                            "prevLogIndex={}, prevLogTerm={}, leaderCommit={} -- {}",
                    currentNode, leader, term, prevLogIndex, prevLogTerm, leaderCommit,
                    Arrays.toString(entries));
        }


        /* Term ????????????AppendEntries ???????????? */
        if(term < currentTerm) {
            byte[] data = RaftResponse.appendEntriesFailure(currentTerm, raftState.currentNode());
            sendResult(selectionKey, data);
            return;
        } else if(term > currentTerm) {
            raftState.setCurrentTerm(term);
            raftState.setRaftRole(RaftRole.Follower);
        }

        /* ????????? leader ??? term >= currentTerm ?????????????????????????????? */
        raftState.resetElectionTimeout();
        /* ?????? leaderNode, ????????????????????????????????????????????? leader ?????? */
        raftState.setLeaderNode(leader);
        logger.debug("[{}] Received [AppendEntries], reset election timeout.",
                currentNode);

        /* raft ??????????????????AppendEntries ???????????? */
        if(leaderPrevEntry == null) {
            byte[] data = RaftResponse.appendEntriesFailure(currentTerm, raftState.currentNode());
            sendResult(selectionKey, data);
            return;
        }

        /* raft ??????????????????AppendEntries ???????????? */
        if(leaderPrevEntry != RaftLog.BEGIN_ENTRY && leaderPrevEntry.term() != prevLogTerm) {
            raftState.setMaxIndex(prevLogIndex);
            byte[] data = RaftResponse.appendEntriesFailure(currentTerm, raftState.currentNode());
            sendResult(selectionKey, data);
            return;
        }

        raftState.setMaxIndex(prevLogIndex);
        raftState.append(entries);
        byte[] data = RaftResponse.appendEntriesSuccess(currentTerm, raftState.currentNode(),
                raftState.indexOfLastLogEntry());
        sendResult(selectionKey, data);

        if(leaderCommit > raftState.commitIndex()) {
            raftState.setCommitIndex(Math.min(leaderCommit, raftState.indexOfLastLogEntry()));
        }

        if(raftState.commitIndex() > raftState.lastApplied()) {
            raftState.apply(raftState.getEntriesByRange(raftState.lastApplied(),
                    raftState.commitIndex()));
        }
    }

    private void handleClientRequest(SelectionKey selectionKey, ByteBuffer buffer) throws IOException {
        /* ??????????????????????????????????????? */
        byte flag = buffer.get();
        /* ??? buffer ??????????????????????????????????????? */
        int len = buffer.limit() - buffer.position();
        byte[] command = new byte[len];
        buffer.get(command);

        ServerNode currentNode = raftState.currentNode();

        if(flag == RAFT_CLIENT_REQUEST_GET) {
            logger.info("[{}] do client [RAFT_CLIENT_REQUEST_GET] request.", currentNode);
            stateMachine.exec(selectionKey, command);
            return;
        }

        logger.info("[{}] do client [RAFT_CLIENT_REQUEST_SET] request, command is: {}",
                currentNode, new String(command));

        /* ????????? RAFT_CLIENT_REQUEST_SET???????????????????????? */
        switch (raftState.raftRole()) {
            /* leader ????????????????????????????????????????????????????????? AppendEntries ??????????????? socketClient */
            case Leader -> {
                int logIndex = raftState.append(new Entry(raftState.currentTerm(), command));
                logger.info("[{}] -- [Leader] -- Received client request, log max index is {}.",
                        currentNode, logIndex);

                raftState.resetHeartbeatTimeout();
                ConcurrentHashMap<ServerNode, Integer> nextIndex = raftState.nextIndex();
                for(ServerNode node : nextIndex.keySet()) {
                    int prevLogIndex = logIndex - 1;
                    int prevLogTerm = prevLogIndex == 0 ? 0
                            : raftState.getEntryTermByIndex(prevLogIndex);
                    Entry[] entries = raftState.getEntriesByRange(prevLogIndex,
                            raftState.indexOfLastLogEntry());
                    /* ?????? AppendEntries ?????? */
                    AppendEntries appendEntries = new AppendEntries(raftState.currentNode(),
                            raftState.currentTerm(), prevLogIndex, prevLogTerm,
                            raftState.commitIndex(), entries);
                    /* ?????????????????????????????? */
                    SocketRequest request = SocketRequest
                            .newUnicastRequest(appendEntries.toBytes(), node);
                    socketClient.offerInterruptibly(request);

                    logger.info("[{}] -- Send [{}] to {}", currentNode, appendEntries, node);
                }

                /* ????????????????????? */
                raftState.resetHeartbeatTimeout();

                /* ??? logIndex ????????? logIndexMap */
                logIndexMap.offer(selectionKey, logIndex);
            }

            /* follower ?????????????????????????????????????????????????????? leader */
            case Follower -> {
                if(raftState.leaderNode() == null) {
                    logger.info("[{}] is [Follower], leaderNode is [null].", currentNode);
                    sendResult(selectionKey, RaftResponse.clientRequestFailure());
                } else {
                    logger.info("[{}] is [Follower], leaderNode is [{}].", currentNode,
                            raftState.leaderNode());
                    ByteBuffer data = ByteBuffer.allocate(command.length + 2);
                    data.put(RaftRequest.CLIENT_REQUEST)
                            .put(RAFT_CLIENT_REQUEST_SET).put(command);
                    SocketRequest request = SocketRequest
                            .newUnicastRequest(data.array(), raftState.leaderNode(), selectionKey);
                    socketClient.offerInterruptibly(request);
                }
            }

            /* candidate ??????????????????????????????????????? */
            case Candidate -> {

            }
        }
    }

    private byte[] getBytes(ByteBuffer buffer) {
        int len = buffer.getInt();
        byte[] bytes = new byte[len];
        buffer.get(bytes);
        return bytes;
    }

    private String getString(ByteBuffer buffer) {
        return new String(getBytes(buffer));
    }

    private Entry[] getEntries(ByteBuffer buffer) {
        List<Entry> entries = new ArrayList<>();
        while (!ByteBufferUtils.isOver(buffer)) {
            entries.add(getEntry(buffer));
        }

        return entries.toArray(Entry[]::new);
    }

    private Entry getEntry(ByteBuffer buffer) {
        int len = buffer.getInt() - NumberUtils.INT_LENGTH;
        int term = buffer.getInt();
        byte[] command = new byte[len];
        buffer.get(command);

        return new Entry(term, command);
    }

    private void sendResult(SelectionKey selectionKey, byte[] data) {
        SocketResponse response = new SocketResponse(selectionKey, data, null);
        socketServer.offer(response);
    }
}
