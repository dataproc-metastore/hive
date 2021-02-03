package org.apache.hadoop.hive.metastore.testutils;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaStore;
import org.apache.hadoop.hive.metastore.MetastoreLauncher;

public class TestMetastoreServerLauncher implements MetastoreLauncher {
  @Override
  public MetastoreLauncher configure(Configuration conf, HiveMetaStore.HiveMetastoreCli cli) {
    return null;
  }

  @Override
  public void run() {

  }
}
