package zbl.moonlight.server.storage.query.kv;

import org.rocksdb.RocksDB;
import zbl.moonlight.server.storage.core.ResultSet;

import java.nio.channels.SelectionKey;

public class KvSetQuery extends KvQueryWithValue {

    protected KvSetQuery(SelectionKey selectionKey, byte[] key, byte[] value) {
        super(selectionKey, key, value);
    }

    @Override
    public void doQuery(RocksDB db, ResultSet resultSet) {

    }
}