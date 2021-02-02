package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.thrift.transport.TTransport;

public class TestMetastoreClientManager implements ClientManager {
    public static final String EXCEPTION_MESSAGE = "Test dummy implementation";

    @Override
    public ThriftHiveMetastore.Iface getClient(Configuration conf) throws MetaException {
      throw new MetaException(EXCEPTION_MESSAGE);
    }

    @Override
    public boolean isConnected() {
      return false;
    }

    @Override
    public void close() {

    }

    @Override
    public void open() throws MetaException {

    }

    @Override
    public void reconnect() throws MetaException {

    }

    @Override
    public TTransport getTTransport() {
      return null;
    }
  }