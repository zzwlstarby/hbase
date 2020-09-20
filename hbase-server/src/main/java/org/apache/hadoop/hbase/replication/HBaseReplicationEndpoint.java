/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.replication;

import static org.apache.hadoop.hbase.HConstants.DEFAULT_HBASE_RPC_SHORTOPERATION_TIMEOUT;
import static org.apache.hadoop.hbase.HConstants.HBASE_RPC_SHORTOPERATION_TIMEOUT_KEY;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Abortable;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.ChoreService;
import org.apache.hadoop.hbase.client.AsyncClusterConnection;
import org.apache.hadoop.hbase.client.AsyncRegionServerAdmin;
import org.apache.hadoop.hbase.client.AsyncReplicationServerAdmin;
import org.apache.hadoop.hbase.client.ClusterConnectionFactory;
import org.apache.hadoop.hbase.protobuf.ReplicationProtobufUtil;
import org.apache.hadoop.hbase.ScheduledChore;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.security.UserProvider;
import org.apache.hadoop.hbase.util.FutureUtils;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.zookeeper.ZKClusterId;
import org.apache.hadoop.hbase.zookeeper.ZKListener;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZKWatcher;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.AuthFailedException;
import org.apache.zookeeper.KeeperException.ConnectionLossException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.hbase.thirdparty.com.google.common.collect.Maps;
import org.apache.hbase.thirdparty.com.google.protobuf.ServiceException;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProtos.ListReplicationSinkServersRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProtos.ListReplicationSinkServersResponse;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProtos.MasterService;

/**
 * A {@link BaseReplicationEndpoint} for replication endpoints whose
 * target cluster is an HBase cluster.
 */
@InterfaceAudience.Private
public abstract class HBaseReplicationEndpoint extends BaseReplicationEndpoint
  implements Abortable {

  private static final Logger LOG = LoggerFactory.getLogger(HBaseReplicationEndpoint.class);

  public static final String FETCH_SERVERS_USE_ZK_CONF_KEY =
      "hbase.replication.fetch.servers.usezk";

  public static final String FETCH_SERVERS_INTERVAL_CONF_KEY =
      "hbase.replication.fetch.servers.interval";
  public static final int DEFAULT_FETCH_SERVERS_INTERVAL = 10 * 60 * 1000; // 10 mins

  private ZKWatcher zkw = null;

  protected Configuration conf;

  protected AsyncClusterConnection conn;

  /**
   * Default maximum number of times a replication sink can be reported as bad before
   * it will no longer be provided as a sink for replication without the pool of
   * replication sinks being refreshed.
   */
  public static final int DEFAULT_BAD_SINK_THRESHOLD = 3;

  /**
   * Default ratio of the total number of peer cluster region servers to consider
   * replicating to.
   */
  public static final float DEFAULT_REPLICATION_SOURCE_RATIO = 0.5f;

  // Ratio of total number of potential peer region servers to be used
  private float ratio;

  // Maximum number of times a sink can be reported as bad before the pool of
  // replication sinks is refreshed
  private int badSinkThreshold;
  // Count of "bad replication sink" reports per peer sink
  private Map<ServerName, Integer> badReportCounts;

  private List<ServerName> sinkServers = new ArrayList<>(0);

  private AsyncClusterConnection peerConnection;
  private boolean fetchServersUseZk = false;
  private FetchServersChore fetchServersChore;
  private int shortOperationTimeout;

  /*
   * Some implementations of HBaseInterClusterReplicationEndpoint may require instantiate different
   * Connection implementations, or initialize it in a different way, so defining createConnection
   * as protected for possible overridings.
   */
  protected AsyncClusterConnection createConnection(Configuration conf) throws IOException {
    return ClusterConnectionFactory.createAsyncClusterConnection(conf,
      null, User.getCurrent());
  }

  @Override
  public void init(Context context) throws IOException {
    super.init(context);
    this.conf = HBaseConfiguration.create(ctx.getConfiguration());
    // TODO: This connection is replication specific or we should make it particular to
    // replication and make replication specific settings such as compression or codec to use
    // passing Cells.
    this.conn = createConnection(this.conf);
    this.ratio =
      ctx.getConfiguration().getFloat("replication.source.ratio", DEFAULT_REPLICATION_SOURCE_RATIO);
    this.badSinkThreshold =
      ctx.getConfiguration().getInt("replication.bad.sink.threshold", DEFAULT_BAD_SINK_THRESHOLD);
    this.badReportCounts = Maps.newHashMap();
  }

  protected synchronized void disconnect() {
    if (zkw != null) {
      zkw.close();
    }
    if (fetchServersChore != null) {
      ChoreService choreService = ctx.getServer().getChoreService();
      if (null != choreService) {
        choreService.cancelChore(fetchServersChore);
      }
    }
    if (peerConnection != null) {
      try {
        peerConnection.close();
      } catch (IOException e) {
        LOG.warn("Attempt to close peerConnection failed.", e);
      }
    }
  }

  /**
   * A private method used to re-establish a zookeeper session with a peer cluster.
   * @param ke
   */
  private void reconnect(KeeperException ke) {
    if (ke instanceof ConnectionLossException || ke instanceof SessionExpiredException
        || ke instanceof AuthFailedException) {
      String clusterKey = ctx.getPeerConfig().getClusterKey();
      LOG.warn("Lost the ZooKeeper connection for peer " + clusterKey, ke);
      try {
        reloadZkWatcher();
      } catch (IOException io) {
        LOG.warn("Creation of ZookeeperWatcher failed for peer " + clusterKey, io);
      }
    }
  }

  @Override
  public void start() {
    startAsync();
  }

  @Override
  public void stop() {
    stopAsync();
  }

  @Override
  protected synchronized void doStart() {
    this.shortOperationTimeout = ctx.getLocalConfiguration().getInt(
        HBASE_RPC_SHORTOPERATION_TIMEOUT_KEY, DEFAULT_HBASE_RPC_SHORTOPERATION_TIMEOUT);
    try {
      if (ctx.getLocalConfiguration().getBoolean(FETCH_SERVERS_USE_ZK_CONF_KEY, false)) {
        fetchServersUseZk = true;
      } else {
        try {
          if (ReplicationUtils.isPeerClusterSupportReplicationOffload(getPeerConnection())) {
            fetchServersChore = new FetchServersChore(ctx.getServer(), this);
            ctx.getServer().getChoreService().scheduleChore(fetchServersChore);
            fetchServersUseZk = false;
          } else {
            fetchServersUseZk = true;
          }
        } catch (Throwable t) {
          fetchServersUseZk = true;
          LOG.warn("Peer {} try to fetch servers by admin failed. Using zk impl.",
              ctx.getPeerId(), t);
        }
      }
      reloadZkWatcher();
      notifyStarted();
    } catch (IOException e) {
      notifyFailed(e);
    }
  }

  @Override
  protected void doStop() {
    disconnect();
    notifyStopped();
  }

  @Override
  // Synchronize peer cluster connection attempts to avoid races and rate
  // limit connections when multiple replication sources try to connect to
  // the peer cluster. If the peer cluster is down we can get out of control
  // over time.
  public synchronized UUID getPeerUUID() {
    UUID peerUUID = null;
    try {
      peerUUID = ZKClusterId.getUUIDForCluster(zkw);
    } catch (KeeperException ke) {
      reconnect(ke);
    }
    return peerUUID;
  }

  /**
   * Closes the current ZKW (if not null) and creates a new one
   * @throws IOException If anything goes wrong connecting
   */
  private synchronized void reloadZkWatcher() throws IOException {
    if (zkw != null) {
      zkw.close();
    }
    zkw = new ZKWatcher(ctx.getConfiguration(),
        "connection to cluster: " + ctx.getPeerId(), this);
    if (fetchServersUseZk) {
      zkw.registerListener(new PeerRegionServerListener(this));
    }
  }

  @Override
  public void abort(String why, Throwable e) {
    LOG.error("The HBaseReplicationEndpoint corresponding to peer " + ctx.getPeerId()
        + " was aborted for the following reason(s):" + why, e);
  }

  @Override
  public boolean isAborted() {
    // Currently this is never "Aborted", we just log when the abort method is called.
    return false;
  }

  /**
   * Get the connection to peer cluster
   * @return connection to peer cluster
   * @throws IOException If anything goes wrong connecting
   */
  private synchronized AsyncClusterConnection getPeerConnection() throws IOException {
    if (peerConnection == null) {
      Configuration conf = ctx.getConfiguration();
      peerConnection = ClusterConnectionFactory.createAsyncClusterConnection(conf, null,
          UserProvider.instantiate(conf).getCurrent());
    }
    return peerConnection;
  }

  /**
   * Get the list of all the servers that are responsible for replication sink
   * from the specified peer master
   * @return list of server addresses or an empty list if the slave is unavailable
   */
  protected List<ServerName> fetchSlavesAddresses() {
    try {
      AsyncClusterConnection peerConn = getPeerConnection();
      ServerName master = FutureUtils.get(peerConn.getAdmin().getMaster());
      MasterService.BlockingInterface masterStub = MasterService.newBlockingStub(
        peerConn.getRpcClient()
          .createBlockingRpcChannel(master, User.getCurrent(), shortOperationTimeout));
      ListReplicationSinkServersResponse resp = masterStub
        .listReplicationSinkServers(null, ListReplicationSinkServersRequest.newBuilder().build());
      return ProtobufUtil.toServerNameList(resp.getServerNameList());
    } catch (ServiceException | IOException e) {
      LOG.error("Peer {} fetches servers failed", ctx.getPeerId(), e);
    }
    return Collections.emptyList();
  }

  /**
   * Get the list of all the region servers from the specified peer
   *
   * @return list of region server addresses or an empty list if the slave is unavailable
   */
  protected List<ServerName> fetchSlavesAddressesByZK() {
    List<String> children = null;
    try {
      children = ZKUtil.listChildrenAndWatchForNewChildren(zkw, zkw.getZNodePaths().rsZNode);
    } catch (KeeperException ke) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Fetch slaves addresses failed", ke);
      }
      reconnect(ke);
    }
    if (children == null) {
      return Collections.emptyList();
    }
    List<ServerName> addresses = new ArrayList<>(children.size());
    for (String child : children) {
      addresses.add(ServerName.parseServerName(child));
    }
    return addresses;
  }

  protected synchronized void chooseSinks() {
    List<ServerName> slaveAddresses = Collections.emptyList();
    if (fetchServersUseZk) {
      slaveAddresses = fetchSlavesAddressesByZK();
    } else {
      slaveAddresses = fetchSlavesAddresses();
    }
    if (slaveAddresses.isEmpty()) {
      LOG.warn("No sinks available at peer. Will not be able to replicate");
    }
    Collections.shuffle(slaveAddresses, ThreadLocalRandom.current());
    int numSinks = (int) Math.ceil(slaveAddresses.size() * ratio);
    this.sinkServers = slaveAddresses.subList(0, numSinks);
    badReportCounts.clear();
  }

  protected synchronized int getNumSinks() {
    return sinkServers.size();
  }

  /**
   * Get a randomly-chosen replication sink to replicate to.
   * @return a replication sink to replicate to
   */
  protected synchronized SinkPeer getReplicationSink() throws IOException {
    if (sinkServers.isEmpty()) {
      LOG.info("Current list of sinks is out of date or empty, updating");
      chooseSinks();
    }
    if (sinkServers.isEmpty()) {
      throw new IOException("No replication sinks are available");
    }
    ServerName serverName =
      sinkServers.get(ThreadLocalRandom.current().nextInt(sinkServers.size()));
    return createSinkPeer(serverName);
  }

  private SinkPeer createSinkPeer(ServerName serverName) throws IOException {
    if (ReplicationUtils.isPeerClusterSupportReplicationOffload(conn)) {
      return new ReplicationServerSinkPeer(serverName, conn.getReplicationServerAdmin(serverName));
    } else {
      return new RegionServerSinkPeer(serverName, conn.getRegionServerAdmin(serverName));
    }
  }

  /**
   * Report a {@code SinkPeer} as being bad (i.e. an attempt to replicate to it
   * failed). If a single SinkPeer is reported as bad more than
   * replication.bad.sink.threshold times, it will be removed
   * from the pool of potential replication targets.
   *
   * @param sinkPeer The SinkPeer that had a failed replication attempt on it
   */
  protected synchronized void reportBadSink(SinkPeer sinkPeer) {
    ServerName serverName = sinkPeer.getServerName();
    int badReportCount = badReportCounts.compute(serverName, (k, v) -> v == null ? 1 : v + 1);
    if (badReportCount > badSinkThreshold) {
      this.sinkServers.remove(serverName);
      if (sinkServers.isEmpty()) {
        chooseSinks();
      }
    }
  }

  /**
   * Report that a {@code SinkPeer} successfully replicated a chunk of data.
   *
   * @param sinkPeer
   *          The SinkPeer that had a failed replication attempt on it
   */
  protected synchronized void reportSinkSuccess(SinkPeer sinkPeer) {
    badReportCounts.remove(sinkPeer.getServerName());
  }

  @VisibleForTesting
  List<ServerName> getSinkServers() {
    return sinkServers;
  }

  /**
   * Tracks changes to the list of region servers in a peer's cluster.
   */
  public static class PeerRegionServerListener extends ZKListener {

    private final HBaseReplicationEndpoint replicationEndpoint;
    private final String regionServerListNode;

    public PeerRegionServerListener(HBaseReplicationEndpoint endpoint) {
      super(endpoint.zkw);
      this.replicationEndpoint = endpoint;
      this.regionServerListNode = endpoint.zkw.getZNodePaths().rsZNode;
    }

    @Override
    public synchronized void nodeChildrenChanged(String path) {
      if (path.equals(regionServerListNode)) {
        LOG.info("Detected change to peer region servers, fetching updated list");
        replicationEndpoint.chooseSinks();
      }
    }
  }

  /**
   * Wraps a replication region server sink to provide the ability to identify it.
   */
  public static abstract class SinkPeer {
    private ServerName serverName;

    public SinkPeer(ServerName serverName) {
      this.serverName = serverName;
    }

    ServerName getServerName() {
      return serverName;
    }

    public abstract void replicateWALEntry(WAL.Entry[] entries, String replicationClusterId,
      Path sourceBaseNamespaceDir, Path sourceHFileArchiveDir, int timeout) throws IOException;
  }

  public static class RegionServerSinkPeer extends SinkPeer {

    private AsyncRegionServerAdmin regionServer;

    public RegionServerSinkPeer(ServerName serverName,
      AsyncRegionServerAdmin replicationServer) {
      super(serverName);
      this.regionServer = replicationServer;
    }

    public void replicateWALEntry(WAL.Entry[] entries, String replicationClusterId,
      Path sourceBaseNamespaceDir, Path sourceHFileArchiveDir, int timeout) throws IOException {
      ReplicationProtobufUtil.replicateWALEntry(regionServer, entries, replicationClusterId,
        sourceBaseNamespaceDir, sourceHFileArchiveDir, timeout);
    }
  }

  public static class ReplicationServerSinkPeer extends SinkPeer {

    private AsyncReplicationServerAdmin replicationServer;

    public ReplicationServerSinkPeer(ServerName serverName,
      AsyncReplicationServerAdmin replicationServer) {
      super(serverName);
      this.replicationServer = replicationServer;
    }

    public void replicateWALEntry(WAL.Entry[] entries, String replicationClusterId,
      Path sourceBaseNamespaceDir, Path sourceHFileArchiveDir, int timeout) throws IOException {
      ReplicationProtobufUtil.replicateWALEntry(replicationServer, entries, replicationClusterId,
        sourceBaseNamespaceDir, sourceHFileArchiveDir, timeout);
    }
  }

  /**
   * Chore that will fetch the list of servers from peer master.
   */
  public static class FetchServersChore extends ScheduledChore {

    private HBaseReplicationEndpoint endpoint;

    public FetchServersChore(Server server, HBaseReplicationEndpoint endpoint) {
      super("Peer-" + endpoint.ctx.getPeerId() + "-FetchServersChore", server,
        server.getConfiguration()
          .getInt(FETCH_SERVERS_INTERVAL_CONF_KEY, DEFAULT_FETCH_SERVERS_INTERVAL));
      this.endpoint = endpoint;
    }

    @Override
    protected void chore() {
      endpoint.chooseSinks();
    }
  }
}
