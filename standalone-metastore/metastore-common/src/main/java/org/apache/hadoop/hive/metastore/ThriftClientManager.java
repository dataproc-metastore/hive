package org.apache.hadoop.hive.metastore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.hooks.URIResolverHook;
import org.apache.hadoop.hive.metastore.security.HadoopThriftAuthBridge;
import org.apache.hadoop.hive.metastore.utils.JavaUtils;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.utils.SecurityUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThriftClientManager implements ClientManager{
  static final protected Logger LOG = LoggerFactory.getLogger(ClientManager.class);
  private boolean isConnected;
  private ThriftHiveMetastore.Iface client;
  private TTransport transport;
  private int retries = 5;
  private long retryDelaySeconds = 0;
  private URI metastoreUris[];
  private static final AtomicInteger connCount = new AtomicInteger(0);
  private String tokenStrForm;
  private Configuration conf;
  private URIResolverHook uriResolverHook;

  @Override
  public ThriftHiveMetastore.Iface getClient(Configuration conf) throws MetaException {
    this.conf = conf;

    uriResolverHook = loadUriResolverHook();
    // get the number retries
    retries = MetastoreConf.getIntVar(conf, MetastoreConf.ConfVars.THRIFT_CONNECTION_RETRIES);
    retryDelaySeconds = MetastoreConf.getTimeVar(conf,
        MetastoreConf.ConfVars.CLIENT_CONNECT_RETRY_DELAY, TimeUnit.SECONDS);

    // user wants file store based configuration
    if (MetastoreConf.getVar(conf, MetastoreConf.ConfVars.THRIFT_URIS) != null) {
      resolveUris();
    } else {
      LOG.error("NOT getting uris from conf");
      throw new MetaException("MetaStoreURIs not found in conf file");
    }

    //If HADOOP_PROXY_USER is set in env or property,
    //then need to create metastore client that proxies as that user.
    String HADOOP_PROXY_USER = "HADOOP_PROXY_USER";
    String proxyUser = System.getenv(HADOOP_PROXY_USER);
    if (proxyUser == null) {
      proxyUser = System.getProperty(HADOOP_PROXY_USER);
    }
    //if HADOOP_PROXY_USER is set, create DelegationToken using real user
    if (proxyUser != null) {
      LOG.info(HADOOP_PROXY_USER + " is set. Using delegation "
          + "token for HiveMetaStore connection.");
      try {
        UserGroupInformation.getLoginUser().getRealUser().doAs(
            new PrivilegedExceptionAction<Void>() {
              @Override
              public Void run() throws Exception {
                open();
                return null;
              }
            });
        String delegationTokenPropString = "DelegationTokenForHiveMetaStoreServer";
        String delegationTokenStr = getDelegationToken(proxyUser, proxyUser);
        SecurityUtils.setTokenStr(UserGroupInformation.getCurrentUser(), delegationTokenStr,
            delegationTokenPropString);
        MetastoreConf.setVar(this.conf, MetastoreConf.ConfVars.TOKEN_SIGNATURE, delegationTokenPropString);
        close();
      } catch (Exception e) {
        LOG.error("Error while setting delegation token for " + proxyUser, e);
        if (e instanceof MetaException) {
          throw (MetaException) e;
        } else {
          throw new MetaException(e.getMessage());
        }
      }
    }
    open();

    return client;
  }

  private String getDelegationToken(String owner, String renewerKerberosPrincipalName) throws TException {
    return client.get_delegation_token(owner, renewerKerberosPrincipalName);
  }

  private void resolveUris() throws MetaException {
    String thriftUris = MetastoreConf.getVar(conf, MetastoreConf.ConfVars.THRIFT_URIS);
    String serviceDiscoveryMode = MetastoreConf.getVar(conf, MetastoreConf.ConfVars.THRIFT_SERVICE_DISCOVERY_MODE);
    List<String> metastoreUrisString = null;

    // The metastore URIs can come from THRIFT_URIS directly or need to be fetched from the
    // Zookeeper
    try {
      if (serviceDiscoveryMode == null || serviceDiscoveryMode.trim().isEmpty()) {
        metastoreUrisString = Arrays.asList(thriftUris.split(","));
      } else if (serviceDiscoveryMode.equalsIgnoreCase("zookeeper")) {
        metastoreUrisString = new ArrayList<String>();
        // Add scheme to the bare URI we get.
        for (String s : MetastoreConf.getZKConfig(conf).getServerUris()) {
          metastoreUrisString.add("thrift://" + s);
        }
      } else {
        throw new IllegalArgumentException("Invalid metastore dynamic service discovery mode " +
            serviceDiscoveryMode);
      }
    } catch (Exception e) {
      MetaStoreUtils.logAndThrowMetaException(e);
    }

    if (metastoreUrisString.isEmpty() && "zookeeper".equalsIgnoreCase(serviceDiscoveryMode)) {
      throw new MetaException("No metastore service discovered in ZooKeeper. "
          + "Please ensure that at least one metastore server is online");
    }

    LOG.info("Resolved metastore uris: {}", metastoreUrisString);

    List<URI> metastoreURIArray = new ArrayList<URI>();
    try {
      for (String s : metastoreUrisString) {
        URI tmpUri = new URI(s);
        if (tmpUri.getScheme() == null) {
          throw new IllegalArgumentException("URI: " + s
              + " does not have a scheme");
        }
        if (uriResolverHook != null) {
          metastoreURIArray.addAll(uriResolverHook.resolveURI(tmpUri));
        } else {
          metastoreURIArray.add(new URI(
              tmpUri.getScheme(),
              tmpUri.getUserInfo(),
              HadoopThriftAuthBridge.getBridge().getCanonicalHostName(tmpUri.getHost()),
              tmpUri.getPort(),
              tmpUri.getPath(),
              tmpUri.getQuery(),
              tmpUri.getFragment()
          ));
        }
      }
      metastoreUris = new URI[metastoreURIArray.size()];
      for (int j = 0; j < metastoreURIArray.size(); j++) {
        metastoreUris[j] = metastoreURIArray.get(j);
      }

      if (MetastoreConf.getVar(conf, MetastoreConf.ConfVars.THRIFT_URI_SELECTION).equalsIgnoreCase("RANDOM")) {
        List<URI> uriList = Arrays.asList(metastoreUris);
        Collections.shuffle(uriList);
        metastoreUris = uriList.toArray(new URI[uriList.size()]);
      }
    } catch (IllegalArgumentException e) {
      throw (e);
    } catch (Exception e) {
      MetaStoreUtils.logAndThrowMetaException(e);
    }
  }

  @Override
  public boolean isConnected() {
    return isConnected;
  }

  @Override
  public void close() {
    try {
      if (null != client) {
        client.shutdown();
        if ((transport == null) || !transport.isOpen()) {
          final int newCount = connCount.decrementAndGet();
          LOG.info("Closed a connection to metastore, current connections: {}",
              newCount);
        }
      }
    } catch (TException e) {
      LOG.debug("Unable to shutdown metastore client. Will try closing transport directly.", e);
    }
    // Transport would have got closed via client.shutdown(), so we dont need this, but
    // just in case, we make this call.
    if ((transport != null) && transport.isOpen()) {
      transport.close();
      final int newCount = connCount.decrementAndGet();
      LOG.info("Closed a connection to metastore, current connections: {}",
          newCount);
      if (LOG.isTraceEnabled()) {
        LOG.trace("METASTORE CONNECTION TRACE - close [{}]",
            System.identityHashCode(this), new Exception());
      }
    }
  }

  @Override
  public void open() throws MetaException {
    isConnected = false;
    TTransportException tte = null;
    MetaException recentME = null;
    boolean useSSL = MetastoreConf.getBoolVar(conf, MetastoreConf.ConfVars.USE_SSL);
    boolean useSasl = MetastoreConf.getBoolVar(conf, MetastoreConf.ConfVars.USE_THRIFT_SASL);
    String clientAuthMode = MetastoreConf.getVar(conf, MetastoreConf.ConfVars.METASTORE_CLIENT_AUTH_MODE);
    boolean usePasswordAuth = false;
    boolean useFramedTransport = MetastoreConf.getBoolVar(conf, MetastoreConf.ConfVars.USE_THRIFT_FRAMED_TRANSPORT);
    boolean useCompactProtocol = MetastoreConf.getBoolVar(conf, MetastoreConf.ConfVars.USE_THRIFT_COMPACT_PROTOCOL);
    int clientSocketTimeout = (int) MetastoreConf.getTimeVar(conf,
        MetastoreConf.ConfVars.CLIENT_SOCKET_TIMEOUT, TimeUnit.MILLISECONDS);

    if (clientAuthMode != null) {
      usePasswordAuth = "PLAIN".equalsIgnoreCase(clientAuthMode);
    }

    for (int attempt = 0; !isConnected && attempt < retries; ++attempt) {
      for (URI store : metastoreUris) {
        LOG.info("Trying to connect to metastore with URI ({})", store);

        try {
          if (useSSL) {
            try {
              String trustStorePath = MetastoreConf.getVar(conf, MetastoreConf.ConfVars.SSL_TRUSTSTORE_PATH).trim();
              if (trustStorePath.isEmpty()) {
                throw new IllegalArgumentException(MetastoreConf.ConfVars.SSL_TRUSTSTORE_PATH
                    + " Not configured for SSL connection");
              }
              String trustStorePassword =
                  MetastoreConf.getPassword(conf, MetastoreConf.ConfVars.SSL_TRUSTSTORE_PASSWORD);
              String trustStoreType =
                  MetastoreConf.getVar(conf, MetastoreConf.ConfVars.SSL_TRUSTSTORE_TYPE).trim();
              String trustStoreAlgorithm =
                  MetastoreConf.getVar(conf, MetastoreConf.ConfVars.SSL_TRUSTMANAGERFACTORY_ALGORITHM).trim();

              // Create an SSL socket and connect
              transport = SecurityUtils.getSSLSocket(store.getHost(), store.getPort(), clientSocketTimeout,
                  trustStorePath, trustStorePassword, trustStoreType, trustStoreAlgorithm);
              final int newCount = connCount.incrementAndGet();
              LOG.debug(
                  "Opened an SSL connection to metastore, current connections: {}",
                  newCount);
              if (LOG.isTraceEnabled()) {
                LOG.trace("METASTORE SSL CONNECTION TRACE - open [{}]",
                    System.identityHashCode(this), new Exception());
              }
            } catch (IOException e) {
              throw new IllegalArgumentException(e);
            } catch (TTransportException e) {
              tte = e;
              throw new MetaException(e.toString());
            }
          } else {
            transport = new TSocket(store.getHost(), store.getPort(), clientSocketTimeout);
          }

          if (usePasswordAuth) {
            // we are using PLAIN Sasl connection with user/password
            LOG.debug("HMSC::open(): Creating plain authentication thrift connection.");
            String userName = MetastoreConf.getVar(conf, MetastoreConf.ConfVars.METASTORE_CLIENT_PLAIN_USERNAME);

            if (null == userName || userName.isEmpty()) {
              throw new MetaException("No user specified for plain transport.");
            }

            // The password is not directly provided. It should be obtained from a keystore pointed
            // by configuration "hadoop.security.credential.provider.path".
            try {
              String passwd = null;
              char[] pwdCharArray = conf.getPassword(userName);
              if (null != pwdCharArray) {
                passwd = new String(pwdCharArray);
              }
              if (null == passwd) {
                throw new MetaException("No password found for user " + userName);
              }
              // Overlay the SASL transport on top of the base socket transport (SSL or non-SSL)
              transport = MetaStorePlainSaslHelper.getPlainTransport(userName, passwd, transport);
            } catch (IOException sasle) {
              // IOException covers SaslException
              LOG.error("Could not create client transport", sasle);
              throw new MetaException(sasle.toString());
            }
          } else if (useSasl) {
            // Wrap thrift connection with SASL for secure connection.
            try {
              HadoopThriftAuthBridge.Client authBridge =
                  HadoopThriftAuthBridge.getBridge().createClient();

              // check if we should use delegation tokens to authenticate
              // the call below gets hold of the tokens if they are set up by hadoop
              // this should happen on the map/reduce tasks if the client added the
              // tokens into hadoop's credential store in the front end during job
              // submission.
              String tokenSig = MetastoreConf.getVar(conf, MetastoreConf.ConfVars.TOKEN_SIGNATURE);
              // tokenSig could be null
              tokenStrForm = SecurityUtils.getTokenStrForm(tokenSig);

              if (tokenStrForm != null) {
                LOG.debug("HMSC::open(): Found delegation token. Creating DIGEST-based thrift connection.");
                // authenticate using delegation tokens via the "DIGEST" mechanism
                transport = authBridge.createClientTransport(null, store.getHost(),
                    "DIGEST", tokenStrForm, transport,
                    MetaStoreUtils.getMetaStoreSaslProperties(conf, useSSL));
              } else {
                LOG.debug("HMSC::open(): Could not find delegation token. Creating KERBEROS-based thrift connection.");
                String principalConfig =
                    MetastoreConf.getVar(conf, MetastoreConf.ConfVars.KERBEROS_PRINCIPAL);
                transport = authBridge.createClientTransport(
                    principalConfig, store.getHost(), "KERBEROS", null,
                    transport, MetaStoreUtils.getMetaStoreSaslProperties(conf, useSSL));
              }
            } catch (IOException ioe) {
              LOG.error("Failed to create client transport", ioe);
              throw new MetaException(ioe.toString());
            }
          } else {
            if (useFramedTransport) {
              transport = new TFramedTransport(transport);
            }
          }

          final TProtocol protocol;
          if (useCompactProtocol) {
            protocol = new TCompactProtocol(transport);
          } else {
            protocol = new TBinaryProtocol(transport);
          }
          client = new ThriftHiveMetastore.Client(protocol);
          try {
            if (!transport.isOpen()) {
              transport.open();
              final int newCount = connCount.incrementAndGet();
              LOG.info("Opened a connection to metastore, URI ({}) "
                  + "current connections: {}", store, newCount);
              if (LOG.isTraceEnabled()) {
                LOG.trace("METASTORE CONNECTION TRACE - open [{}]",
                    System.identityHashCode(this), new Exception());
              }
            }
            isConnected = true;
          } catch (TTransportException e) {
            tte = e;
            LOG.warn("Failed to connect to the MetaStore Server URI ({})",
                store);
            LOG.debug("Failed to connect to the MetaStore Server URI ({})",
                store, e);
          }

          if (isConnected && !useSasl && !usePasswordAuth &&
              MetastoreConf.getBoolVar(conf, MetastoreConf.ConfVars.EXECUTE_SET_UGI)) {
            // Call set_ugi, only in unsecure mode.
            try {
              UserGroupInformation ugi = SecurityUtils.getUGI();
              client.set_ugi(ugi.getUserName(), Arrays.asList(ugi.getGroupNames()));
            } catch (LoginException e) {
              LOG.warn("Failed to do login. set_ugi() is not successful, " +
                  "Continuing without it.", e);
            } catch (IOException e) {
              LOG.warn("Failed to find ugi of client set_ugi() is not successful, " +
                  "Continuing without it.", e);
            } catch (TException e) {
              LOG.warn("set_ugi() not successful, Likely cause: new client talking to old server. "
                  + "Continuing without it.", e);
            }
          }
        } catch (MetaException e) {
          recentME = e;
          LOG.error("Failed to connect to metastore with URI (" + store
              + ") in attempt " + attempt, e);
        }
        if (isConnected) {
          break;
        }
      }
      // Wait before launching the next round of connection retries.
      if (!isConnected && retryDelaySeconds > 0) {
        try {
          LOG.info("Waiting " + retryDelaySeconds + " seconds before next connection attempt.");
          Thread.sleep(retryDelaySeconds * 1000);
        } catch (InterruptedException ignore) {}
      }
    }

    if (!isConnected) {
      // Either tte or recentME should be set but protect from a bug which causes both of them to
      // be null. When MetaException wraps TTransportException, tte will be set so stringify that
      // directly.
      String exceptionString = "Unknown exception";
      if (tte != null) {
        exceptionString = StringUtils.stringifyException(tte);
      } else if (recentME != null) {
        exceptionString = StringUtils.stringifyException(recentME);
      }
      throw new MetaException("Could not connect to meta store using any of the URIs provided." +
          " Most recent failure: " + exceptionString);
    }
  }

  @Override
  public void reconnect() throws MetaException {
    close();
    if (uriResolverHook != null) {
      //for dynamic uris, re-lookup if there are new metastore locations
      resolveUris();
    }

    if (MetastoreConf.getVar(conf, MetastoreConf.ConfVars.THRIFT_URI_SELECTION).equalsIgnoreCase("RANDOM")) {
      // Swap the first element of the metastoreUris[] with a random element from the rest
      // of the array. Rationale being that this method will generally be called when the default
      // connection has died and the default connection is likely to be the first array element.
      promoteRandomMetaStoreURI();
    }
    open();
  }

  @Override
  public TTransport getTTransport() {
    return transport;
  }

  /**
   * Swaps the first element of the metastoreUris array with a random element from the
   * remainder of the array.
   */
  private void promoteRandomMetaStoreURI() {
    if (metastoreUris.length <= 1) {
      return;
    }
    Random rng = new Random();
    int index = rng.nextInt(metastoreUris.length - 1) + 1;
    URI tmp = metastoreUris[0];
    metastoreUris[0] = metastoreUris[index];
    metastoreUris[index] = tmp;
  }

  //multiple clients may initialize the hook at the same time
  synchronized private URIResolverHook loadUriResolverHook() throws IllegalStateException {

    String uriResolverClassName =
        MetastoreConf.getAsString(conf, MetastoreConf.ConfVars.URI_RESOLVER);
    if (uriResolverClassName.equals("")) {
      return null;
    } else {
      LOG.info("Loading uri resolver : " + uriResolverClassName);
      try {
        Class<?> uriResolverClass = Class.forName(uriResolverClassName, true,
            JavaUtils.getClassLoader());
        return (URIResolverHook) ReflectionUtils.newInstance(uriResolverClass, null);
      } catch (Exception e) {
        LOG.error("Exception loading uri resolver hook", e);
        return null;
      }
    }
  }
}
