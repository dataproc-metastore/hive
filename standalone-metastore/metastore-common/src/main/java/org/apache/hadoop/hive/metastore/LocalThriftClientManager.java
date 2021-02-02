package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalThriftClientManager implements ClientManager{
  static final protected Logger LOG = LoggerFactory.getLogger(LocalThriftClientManager.class);
  private boolean isConnected = false;
  private ThriftHiveMetastore.Iface client;

  @Override
  public ThriftHiveMetastore.Iface getClient(Configuration conf) throws MetaException {
    isConnected = true;
    client = HiveMetaStoreClient.callEmbeddedMetastore(conf);
    return client;
  }

  @Override
  public boolean isConnected() {
    return isConnected;
  }

  @Override
  public void close() {
    try {
      client.shutdown();
    } catch (TException e) {
      LOG.debug("Unable to shutdown metastore client. Will try closing transport directly.", e);
    }
  }

  @Override
  public void open() {
    //no-op for this client type.
  }

  @Override
  public void reconnect() throws MetaException {
    throw new MetaException("Retries for direct MetaStore DB connections "
        + "are not supported by this client");
  }

  @Override
  public TTransport getTTransport() {
    LOG.error("Requested TTransport from local metastore client");
    return null;
  }
}
