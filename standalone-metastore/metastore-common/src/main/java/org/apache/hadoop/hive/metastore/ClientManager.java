package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.thrift.transport.TTransport;

public interface ClientManager {
  ThriftHiveMetastore.Iface getClient(Configuration conf) throws MetaException;

  boolean isConnected();

  void close();

  void open() throws MetaException;

  void reconnect() throws MetaException;

  TTransport getTTransport();
}
