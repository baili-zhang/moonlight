package zbl.moonlight.storage.rocks.query.kv;

import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import zbl.moonlight.storage.rocks.query.Query;
import zbl.moonlight.storage.core.Pair;

import java.util.List;

public class KvBatchSetQuery extends Query<List<Pair<byte[], byte[]>>, Void> {


    protected KvBatchSetQuery(List<Pair<byte[], byte[]>> queryData) {
        super(queryData);
    }

    @Override
    public void doQuery(RocksDB db) throws RocksDBException {

    }
}