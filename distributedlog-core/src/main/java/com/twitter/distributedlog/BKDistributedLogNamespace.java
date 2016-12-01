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
package com.twitter.distributedlog;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.twitter.distributedlog.DistributedLogManagerFactory.ClientSharingOption;
import com.twitter.distributedlog.acl.AccessControlManager;
import com.twitter.distributedlog.acl.DefaultAccessControlManager;
import com.twitter.distributedlog.acl.ZKAccessControlManager;
import com.twitter.distributedlog.bk.LedgerAllocator;
import com.twitter.distributedlog.bk.LedgerAllocatorUtils;
import com.twitter.distributedlog.callback.NamespaceListener;
import com.twitter.distributedlog.config.DynamicDistributedLogConfiguration;
import com.twitter.distributedlog.exceptions.AlreadyClosedException;
import com.twitter.distributedlog.exceptions.InvalidStreamNameException;
import com.twitter.distributedlog.exceptions.LogNotFoundException;
import com.twitter.distributedlog.feature.CoreFeatureKeys;
import com.twitter.distributedlog.impl.ZKLogMetadataStore;
import com.twitter.distributedlog.impl.federated.FederatedZKLogMetadataStore;
import com.twitter.distributedlog.impl.metadata.ZKLogStreamMetadataStore;
import com.twitter.distributedlog.logsegment.LogSegmentMetadataCache;
import com.twitter.distributedlog.metadata.BKDLConfig;
import com.twitter.distributedlog.metadata.LogMetadataStore;
import com.twitter.distributedlog.metadata.LogStreamMetadataStore;
import com.twitter.distributedlog.namespace.DistributedLogNamespace;
import com.twitter.distributedlog.stats.ReadAheadExceptionsLogger;
import com.twitter.distributedlog.util.ConfUtils;
import com.twitter.distributedlog.util.DLUtils;
import com.twitter.distributedlog.util.FutureUtils;
import com.twitter.distributedlog.util.MonitoredScheduledThreadPoolExecutor;
import com.twitter.distributedlog.util.OrderedScheduler;
import com.twitter.distributedlog.util.PermitLimiter;
import com.twitter.distributedlog.util.SchedulerUtils;
import com.twitter.distributedlog.util.SimplePermitLimiter;
import com.twitter.distributedlog.util.Utils;
import org.apache.bookkeeper.feature.FeatureProvider;
import org.apache.bookkeeper.feature.Feature;
import org.apache.bookkeeper.feature.SettableFeatureProvider;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.zookeeper.BoundExponentialBackoffRetryPolicy;
import org.apache.bookkeeper.zookeeper.RetryPolicy;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.common.PathUtils;
import org.apache.zookeeper.data.Stat;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.util.HashedWheelTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.twitter.distributedlog.impl.BKDLUtils.*;

/**
 * BKDistributedLogNamespace is the default implementation of {@link DistributedLogNamespace}. It uses
 * zookeeper for metadata storage and bookkeeper for data storage.
 * <h3>Metrics</h3>
 *
 * <h4>ZooKeeper Client</h4>
 * See {@link ZooKeeperClient} for detail sub-stats.
 * <ul>
 * <li> `scope`/dlzk_factory_writer_shared/* : stats about the zookeeper client shared by all DL writers.
 * <li> `scope`/dlzk_factory_reader_shared/* : stats about the zookeeper client shared by all DL readers.
 * <li> `scope`/bkzk_factory_writer_shared/* : stats about the zookeeper client used by bookkeeper client
 * shared by all DL writers.
 * <li> `scope`/bkzk_factory_reader_shared/* : stats about the zookeeper client used by bookkeeper client
 * shared by all DL readers.
 * </ul>
 *
 * <h4>BookKeeper Client</h4>
 * BookKeeper client stats are exposed directly to current scope. See {@link BookKeeperClient} for detail stats.
 *
 * <h4>Utils</h4>
 * <ul>
 * <li> `scope`/factory/thread_pool/* : stats about the ordered scheduler used by this namespace.
 * See {@link OrderedScheduler}.
 * <li> `scope`/factory/readahead_thread_pool/* : stats about the readahead thread pool executor
 * used by this namespace. See {@link MonitoredScheduledThreadPoolExecutor}.
 * <li> `scope`/writeLimiter/* : stats about the global write limiter used by this namespace.
 * See {@link PermitLimiter}.
 * </ul>
 *
 * <h4>ReadAhead Exceptions</h4>
 * Stats about exceptions that encountered in ReadAhead are exposed under <code>`scope`/exceptions</code>.
 * See {@link ReadAheadExceptionsLogger}.
 *
 * <h4>DistributedLogManager</h4>
 *
 * All the core stats about reader and writer are exposed under current scope via {@link BKDistributedLogManager}.
 */
public class BKDistributedLogNamespace implements DistributedLogNamespace {
    static final Logger LOG = LoggerFactory.getLogger(BKDistributedLogNamespace.class);

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private DistributedLogConfiguration _conf = null;
        private URI _uri = null;
        private StatsLogger _statsLogger = NullStatsLogger.INSTANCE;
        private StatsLogger _perLogStatsLogger = NullStatsLogger.INSTANCE;
        private FeatureProvider _featureProvider = new SettableFeatureProvider("", 0);
        private String _clientId = DistributedLogConstants.UNKNOWN_CLIENT_ID;
        private int _regionId = DistributedLogConstants.LOCAL_REGION_ID;

        private Builder() {}

        public Builder conf(DistributedLogConfiguration conf) {
            this._conf = conf;
            return this;
        }

        public Builder uri(URI uri) {
            this._uri = uri;
            return this;
        }

        public Builder statsLogger(StatsLogger statsLogger) {
            this._statsLogger = statsLogger;
            return this;
        }

        public Builder perLogStatsLogger(StatsLogger perLogStatsLogger) {
            this._perLogStatsLogger = perLogStatsLogger;
            return this;
        }

        public Builder featureProvider(FeatureProvider featureProvider) {
            this._featureProvider = featureProvider;
            return this;
        }

        public Builder clientId(String clientId) {
            this._clientId = clientId;
            return this;
        }

        public Builder regionId(int regionId) {
            this._regionId = regionId;
            return this;
        }

        @SuppressWarnings("deprecation")
        public BKDistributedLogNamespace build()
                throws IOException, NullPointerException, IllegalArgumentException {
            Preconditions.checkNotNull(_conf, "No DistributedLog Configuration");
            Preconditions.checkNotNull(_uri, "No DistributedLog URI");
            Preconditions.checkNotNull(_featureProvider, "No Feature Provider");
            Preconditions.checkNotNull(_statsLogger, "No Stats Logger");
            Preconditions.checkNotNull(_featureProvider, "No Feature Provider");
            Preconditions.checkNotNull(_clientId, "No Client ID");
            // validate conf and uri
            validateConfAndURI(_conf, _uri);

            // Build the namespace zookeeper client
            ZooKeeperClientBuilder nsZkcBuilder = createDLZKClientBuilder(
                    String.format("dlzk:%s:factory_writer_shared", _uri),
                    _conf,
                    DLUtils.getZKServersFromDLUri(_uri),
                    _statsLogger.scope("dlzk_factory_writer_shared"));
            ZooKeeperClient nsZkc = nsZkcBuilder.build();

            // Resolve namespace binding
            BKDLConfig bkdlConfig = BKDLConfig.resolveDLConfig(nsZkc, _uri);

            // Backward Compatible to enable per log stats by configuration settings
            StatsLogger perLogStatsLogger = _perLogStatsLogger;
            if (perLogStatsLogger == NullStatsLogger.INSTANCE &&
                    _conf.getEnablePerStreamStat()) {
                perLogStatsLogger = _statsLogger.scope("stream");
            }

            return new BKDistributedLogNamespace(
                    _conf,
                    _uri,
                    _featureProvider,
                    _statsLogger,
                    perLogStatsLogger,
                    _clientId,
                    _regionId,
                    nsZkcBuilder,
                    nsZkc,
                    bkdlConfig);
        }
    }

    static interface ZooKeeperClientHandler<T> {
        T handle(ZooKeeperClient zkc) throws IOException;
    }

    /**
     * Run given <i>handler</i> by providing an available new zookeeper client
     *
     * @param handler
     *          Handler to process with provided zookeeper client.
     * @param conf
     *          Distributedlog Configuration.
     * @param namespace
     *          Distributedlog Namespace.
     */
    private static <T> T withZooKeeperClient(ZooKeeperClientHandler<T> handler,
                                             DistributedLogConfiguration conf,
                                             URI namespace) throws IOException {
        ZooKeeperClient zkc = ZooKeeperClientBuilder.newBuilder()
                .name(String.format("dlzk:%s:factory_static", namespace))
                .sessionTimeoutMs(conf.getZKSessionTimeoutMilliseconds())
                .uri(namespace)
                .retryThreadCount(conf.getZKClientNumberRetryThreads())
                .requestRateLimit(conf.getZKRequestRateLimit())
                .zkAclId(conf.getZkAclId())
                .build();
        try {
            return handler.handle(zkc);
        } finally {
            zkc.close();
        }
    }

    private static String getHostIpLockClientId() {
        try {
            return InetAddress.getLocalHost().toString();
        } catch(Exception ex) {
            return DistributedLogConstants.UNKNOWN_CLIENT_ID;
        }
    }

    private final String clientId;
    private final int regionId;
    private final DistributedLogConfiguration conf;
    private final URI namespace;
    private final BKDLConfig bkdlConfig;
    private final OrderedScheduler scheduler;
    private final OrderedScheduler readAheadExecutor;
    private final ClientSocketChannelFactory channelFactory;
    private final HashedWheelTimer requestTimer;
    // zookeeper clients
    // NOTE: The actual zookeeper client is initialized lazily when it is referenced by
    //       {@link com.twitter.distributedlog.ZooKeeperClient#get()}. So it is safe to
    //       keep builders and their client wrappers here, as they will be used when
    //       instantiating readers or writers.
    private final ZooKeeperClientBuilder sharedWriterZKCBuilderForDL;
    private final ZooKeeperClient sharedWriterZKCForDL;
    private final ZooKeeperClientBuilder sharedReaderZKCBuilderForDL;
    private final ZooKeeperClient sharedReaderZKCForDL;
    private ZooKeeperClientBuilder sharedWriterZKCBuilderForBK = null;
    private ZooKeeperClient sharedWriterZKCForBK = null;
    private ZooKeeperClientBuilder sharedReaderZKCBuilderForBK = null;
    private ZooKeeperClient sharedReaderZKCForBK = null;
    // NOTE: The actual bookkeeper client is initialized lazily when it is referenced by
    //       {@link com.twitter.distributedlog.BookKeeperClient#get()}. So it is safe to
    //       keep builders and their client wrappers here, as they will be used when
    //       instantiating readers or writers.
    private final BookKeeperClientBuilder sharedWriterBKCBuilder;
    private final BookKeeperClient writerBKC;
    private final BookKeeperClientBuilder sharedReaderBKCBuilder;
    private final BookKeeperClient readerBKC;
    // ledger allocator
    private final LedgerAllocator allocator;
    // access control manager
    private AccessControlManager accessControlManager;
    // log metadata store
    private final LogMetadataStore metadataStore;
    // log segment metadata store
    private final LogSegmentMetadataCache logSegmentMetadataCache;
    private final LogStreamMetadataStore writerStreamMetadataStore;
    private final LogStreamMetadataStore readerStreamMetadataStore;

    // feature provider
    private final FeatureProvider featureProvider;

    // Stats Loggers
    private final StatsLogger statsLogger;
    private final StatsLogger perLogStatsLogger;
    private final ReadAheadExceptionsLogger readAheadExceptionsLogger;

    protected AtomicBoolean closed = new AtomicBoolean(false);

    private final PermitLimiter writeLimiter;

    private BKDistributedLogNamespace(
            DistributedLogConfiguration conf,
            URI uri,
            FeatureProvider featureProvider,
            StatsLogger statsLogger,
            StatsLogger perLogStatsLogger,
            String clientId,
            int regionId,
            ZooKeeperClientBuilder nsZkcBuilder,
            ZooKeeperClient nsZkc,
            BKDLConfig bkdlConfig)
            throws IOException, IllegalArgumentException {
        this.conf = conf;
        this.namespace = uri;
        this.featureProvider = featureProvider;
        this.statsLogger = statsLogger;
        this.perLogStatsLogger = perLogStatsLogger;
        this.regionId = regionId;
        this.bkdlConfig = bkdlConfig;
        if (clientId.equals(DistributedLogConstants.UNKNOWN_CLIENT_ID)) {
            this.clientId = getHostIpLockClientId();
        } else {
            this.clientId = clientId;
        }

        // Build resources
        StatsLogger schedulerStatsLogger = statsLogger.scope("factory").scope("thread_pool");
        this.scheduler = OrderedScheduler.newBuilder()
                .name("DLM-" + uri.getPath())
                .corePoolSize(conf.getNumWorkerThreads())
                .statsLogger(schedulerStatsLogger)
                .perExecutorStatsLogger(schedulerStatsLogger)
                .traceTaskExecution(conf.getEnableTaskExecutionStats())
                .traceTaskExecutionWarnTimeUs(conf.getTaskExecutionWarnTimeMicros())
                .build();
        if (conf.getNumReadAheadWorkerThreads() > 0) {
            this.readAheadExecutor = OrderedScheduler.newBuilder()
                    .name("DLM-" + uri.getPath() + "-readahead-executor")
                    .corePoolSize(conf.getNumReadAheadWorkerThreads())
                    .statsLogger(statsLogger.scope("factory").scope("readahead_thread_pool"))
                    .traceTaskExecution(conf.getTraceReadAheadDeliveryLatency())
                    .traceTaskExecutionWarnTimeUs(conf.getTaskExecutionWarnTimeMicros())
                    .build();
            LOG.info("Created dedicated readahead executor : threads = {}", conf.getNumReadAheadWorkerThreads());
        } else {
            this.readAheadExecutor = this.scheduler;
            LOG.info("Used shared executor for readahead.");
        }

        this.channelFactory = new NioClientSocketChannelFactory(
            Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("DL-netty-boss-%d").build()),
            Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("DL-netty-worker-%d").build()),
            conf.getBKClientNumberIOThreads());
        this.requestTimer = new HashedWheelTimer(
            new ThreadFactoryBuilder().setNameFormat("DLFactoryTimer-%d").build(),
            conf.getTimeoutTimerTickDurationMs(), TimeUnit.MILLISECONDS,
            conf.getTimeoutTimerNumTicks());

        // Build zookeeper client for writers
        this.sharedWriterZKCBuilderForDL = nsZkcBuilder;
        this.sharedWriterZKCForDL = nsZkc;

        // Build zookeeper client for readers
        if (bkdlConfig.getDlZkServersForWriter().equals(bkdlConfig.getDlZkServersForReader())) {
            this.sharedReaderZKCBuilderForDL = this.sharedWriterZKCBuilderForDL;
        } else {
            this.sharedReaderZKCBuilderForDL = createDLZKClientBuilder(
                    String.format("dlzk:%s:factory_reader_shared", namespace),
                    conf,
                    bkdlConfig.getDlZkServersForReader(),
                    statsLogger.scope("dlzk_factory_reader_shared"));
        }
        this.sharedReaderZKCForDL = this.sharedReaderZKCBuilderForDL.build();

        // Build bookkeeper client for writers
        this.sharedWriterBKCBuilder = createBKCBuilder(
                String.format("bk:%s:factory_writer_shared", namespace),
                conf,
                bkdlConfig.getBkZkServersForWriter(),
                bkdlConfig.getBkLedgersPath(),
                Optional.of(featureProvider.scope("bkc")));
        this.writerBKC = this.sharedWriterBKCBuilder.build();

        // Build bookkeeper client for readers
        if (bkdlConfig.getBkZkServersForWriter().equals(bkdlConfig.getBkZkServersForReader())) {
            this.sharedReaderBKCBuilder = this.sharedWriterBKCBuilder;
        } else {
            this.sharedReaderBKCBuilder = createBKCBuilder(
                String.format("bk:%s:factory_reader_shared", namespace),
                conf,
                bkdlConfig.getBkZkServersForReader(),
                bkdlConfig.getBkLedgersPath(),
                Optional.<FeatureProvider>absent());
        }
        this.readerBKC = this.sharedReaderBKCBuilder.build();

        if (conf.getGlobalOutstandingWriteLimit() < 0) {
            this.writeLimiter = PermitLimiter.NULL_PERMIT_LIMITER;
        } else {
            Feature disableWriteLimitFeature = featureProvider.getFeature(
                CoreFeatureKeys.DISABLE_WRITE_LIMIT.name().toLowerCase());
            this.writeLimiter = new SimplePermitLimiter(
                conf.getOutstandingWriteLimitDarkmode(),
                conf.getGlobalOutstandingWriteLimit(),
                statsLogger.scope("writeLimiter"),
                true /* singleton */,
                disableWriteLimitFeature);
        }

        // propagate bkdlConfig to configuration
        BKDLConfig.propagateConfiguration(bkdlConfig, conf);

        // Build the allocator
        if (conf.getEnableLedgerAllocatorPool()) {
            String allocatorPoolPath = validateAndGetFullLedgerAllocatorPoolPath(conf, uri);
            allocator = LedgerAllocatorUtils.createLedgerAllocatorPool(allocatorPoolPath, conf.getLedgerAllocatorPoolCoreSize(),
                    conf, sharedWriterZKCForDL, writerBKC, scheduler);
            if (null != allocator) {
                allocator.start();
            }
            LOG.info("Created ledger allocator pool under {} with size {}.", allocatorPoolPath, conf.getLedgerAllocatorPoolCoreSize());
        } else {
            allocator = null;
        }

        // Stats Loggers
        this.readAheadExceptionsLogger = new ReadAheadExceptionsLogger(statsLogger);

        // log metadata store
        if (bkdlConfig.isFederatedNamespace() || conf.isFederatedNamespaceEnabled()) {
            this.metadataStore = new FederatedZKLogMetadataStore(conf, namespace, sharedReaderZKCForDL, scheduler);
        } else {
            this.metadataStore = new ZKLogMetadataStore(conf, namespace, sharedReaderZKCForDL, scheduler);
        }

        // create log stream metadata store
        this.writerStreamMetadataStore =
                new ZKLogStreamMetadataStore(
                        clientId,
                        conf,
                        sharedWriterZKCForDL,
                        scheduler,
                        statsLogger);
        this.readerStreamMetadataStore =
                new ZKLogStreamMetadataStore(
                        clientId,
                        conf,
                        sharedReaderZKCForDL,
                        scheduler,
                        statsLogger);
        // create a log segment metadata cache
        this.logSegmentMetadataCache = new LogSegmentMetadataCache(conf, Ticker.systemTicker());

        LOG.info("Constructed BK DistributedLogNamespace : clientId = {}, regionId = {}, federated = {}.",
                new Object[] { clientId, regionId, bkdlConfig.isFederatedNamespace() });
    }

    //
    // Namespace Methods
    //

    @Override
    public void createLog(String logName)
            throws InvalidStreamNameException, IOException {
        checkState();
        validateName(logName);
        URI uri = FutureUtils.result(metadataStore.createLog(logName));
        FutureUtils.result(writerStreamMetadataStore.getLog(uri, logName, true, true));
    }

    @Override
    public void deleteLog(String logName)
            throws InvalidStreamNameException, LogNotFoundException, IOException {
        checkState();
        validateName(logName);
        Optional<URI> uri = FutureUtils.result(metadataStore.getLogLocation(logName));
        if (!uri.isPresent()) {
            throw new LogNotFoundException("Log " + logName + " isn't found.");
        }
        DistributedLogManager dlm = createDistributedLogManager(
                uri.get(),
                logName,
                ClientSharingOption.SharedClients,
                Optional.<DistributedLogConfiguration>absent(),
                Optional.<DynamicDistributedLogConfiguration>absent(),
                Optional.<StatsLogger>absent());
        dlm.delete();
    }

    @Override
    public DistributedLogManager openLog(String logName)
            throws InvalidStreamNameException, IOException {
        return openLog(logName,
                Optional.<DistributedLogConfiguration>absent(),
                Optional.<DynamicDistributedLogConfiguration>absent(),
                Optional.<StatsLogger>absent());
    }

    @Override
    public DistributedLogManager openLog(String logName,
                                         Optional<DistributedLogConfiguration> logConf,
                                         Optional<DynamicDistributedLogConfiguration> dynamicLogConf,
                                         Optional<StatsLogger> perStreamStatsLogger)
            throws InvalidStreamNameException, IOException {
        checkState();
        validateName(logName);
        Optional<URI> uri = FutureUtils.result(metadataStore.getLogLocation(logName));
        if (!uri.isPresent()) {
            throw new LogNotFoundException("Log " + logName + " isn't found.");
        }
        return createDistributedLogManager(
                uri.get(),
                logName,
                ClientSharingOption.SharedClients,
                logConf,
                dynamicLogConf,
                perStreamStatsLogger);
    }

    @Override
    public boolean logExists(String logName)
        throws IOException, IllegalArgumentException {
        checkState();
        Optional<URI> uri = FutureUtils.result(metadataStore.getLogLocation(logName));
        if (uri.isPresent()) {
            try {
                FutureUtils.result(writerStreamMetadataStore.logExists(uri.get(), logName));
                return true;
            } catch (LogNotFoundException lnfe) {
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public Iterator<String> getLogs() throws IOException {
        checkState();
        return FutureUtils.result(metadataStore.getLogs());
    }

    @Override
    public void registerNamespaceListener(NamespaceListener listener) {
        metadataStore.registerNamespaceListener(listener);
    }

    @Override
    public synchronized AccessControlManager createAccessControlManager() throws IOException {
        checkState();
        if (null == accessControlManager) {
            String aclRootPath = bkdlConfig.getACLRootPath();
            // Build the access control manager
            if (aclRootPath == null) {
                accessControlManager = DefaultAccessControlManager.INSTANCE;
                LOG.info("Created default access control manager for {}", namespace);
            } else {
                if (!isReservedStreamName(aclRootPath)) {
                    throw new IOException("Invalid Access Control List Root Path : " + aclRootPath);
                }
                String zkRootPath = namespace.getPath() + "/" + aclRootPath;
                LOG.info("Creating zk based access control manager @ {} for {}",
                        zkRootPath, namespace);
                accessControlManager = new ZKAccessControlManager(conf, sharedReaderZKCForDL,
                        zkRootPath, scheduler);
                LOG.info("Created zk based access control manager @ {} for {}",
                        zkRootPath, namespace);
            }
        }
        return accessControlManager;
    }

    //
    // Legacy methods
    //

    static String validateAndGetFullLedgerAllocatorPoolPath(DistributedLogConfiguration conf, URI uri) throws IOException {
        String poolPath = conf.getLedgerAllocatorPoolPath();
        LOG.info("PoolPath is {}", poolPath);
        if (null == poolPath || !poolPath.startsWith(".") || poolPath.endsWith("/")) {
            LOG.error("Invalid ledger allocator pool path specified when enabling ledger allocator pool : {}", poolPath);
            throw new IOException("Invalid ledger allocator pool path specified : " + poolPath);
        }
        String poolName = conf.getLedgerAllocatorPoolName();
        if (null == poolName) {
            LOG.error("No ledger allocator pool name specified when enabling ledger allocator pool.");
            throw new IOException("No ledger allocator name specified when enabling ledger allocator pool.");
        }
        String rootPath = uri.getPath() + "/" + poolPath + "/" + poolName;
        try {
            PathUtils.validatePath(rootPath);
        } catch (IllegalArgumentException iae) {
            LOG.error("Invalid ledger allocator pool path specified when enabling ledger allocator pool : {}", poolPath);
            throw new IOException("Invalid ledger allocator pool path specified : " + poolPath);
        }
        return rootPath;
    }

    private static ZooKeeperClientBuilder createDLZKClientBuilder(String zkcName,
                                                                  DistributedLogConfiguration conf,
                                                                  String zkServers,
                                                                  StatsLogger statsLogger) {
        RetryPolicy retryPolicy = null;
        if (conf.getZKNumRetries() > 0) {
            retryPolicy = new BoundExponentialBackoffRetryPolicy(
                conf.getZKRetryBackoffStartMillis(),
                conf.getZKRetryBackoffMaxMillis(), conf.getZKNumRetries());
        }
        ZooKeeperClientBuilder builder = ZooKeeperClientBuilder.newBuilder()
            .name(zkcName)
            .sessionTimeoutMs(conf.getZKSessionTimeoutMilliseconds())
            .retryThreadCount(conf.getZKClientNumberRetryThreads())
            .requestRateLimit(conf.getZKRequestRateLimit())
            .zkServers(zkServers)
            .retryPolicy(retryPolicy)
            .statsLogger(statsLogger)
            .zkAclId(conf.getZkAclId());
        LOG.info("Created shared zooKeeper client builder {}: zkServers = {}, numRetries = {}, sessionTimeout = {}, retryBackoff = {},"
                + " maxRetryBackoff = {}, zkAclId = {}.", new Object[] { zkcName, zkServers, conf.getZKNumRetries(),
                conf.getZKSessionTimeoutMilliseconds(), conf.getZKRetryBackoffStartMillis(),
                conf.getZKRetryBackoffMaxMillis(), conf.getZkAclId() });
        return builder;
    }

    private static ZooKeeperClientBuilder createBKZKClientBuilder(String zkcName,
                                                                  DistributedLogConfiguration conf,
                                                                  String zkServers,
                                                                  StatsLogger statsLogger) {
        RetryPolicy retryPolicy = new BoundExponentialBackoffRetryPolicy(
                    conf.getBKClientZKRetryBackoffStartMillis(),
                    conf.getBKClientZKRetryBackoffMaxMillis(),
                    conf.getBKClientZKNumRetries());
        ZooKeeperClientBuilder builder = ZooKeeperClientBuilder.newBuilder()
                .name(zkcName)
                .sessionTimeoutMs(conf.getBKClientZKSessionTimeoutMilliSeconds())
                .retryThreadCount(conf.getZKClientNumberRetryThreads())
                .requestRateLimit(conf.getBKClientZKRequestRateLimit())
                .zkServers(zkServers)
                .retryPolicy(retryPolicy)
                .statsLogger(statsLogger)
                .zkAclId(conf.getZkAclId());
        LOG.info("Created shared zooKeeper client builder {}: zkServers = {}, numRetries = {}, sessionTimeout = {}, retryBackoff = {},"
                + " maxRetryBackoff = {}, zkAclId = {}.", new Object[] { zkcName, zkServers, conf.getBKClientZKNumRetries(),
                conf.getBKClientZKSessionTimeoutMilliSeconds(), conf.getBKClientZKRetryBackoffStartMillis(),
                conf.getBKClientZKRetryBackoffMaxMillis(), conf.getZkAclId() });
        return builder;
    }

    private BookKeeperClientBuilder createBKCBuilder(String bkcName,
                                                     DistributedLogConfiguration conf,
                                                     String zkServers,
                                                     String ledgersPath,
                                                     Optional<FeatureProvider> featureProviderOptional) {
        BookKeeperClientBuilder builder = BookKeeperClientBuilder.newBuilder()
                .name(bkcName)
                .dlConfig(conf)
                .zkServers(zkServers)
                .ledgersPath(ledgersPath)
                .channelFactory(channelFactory)
                .requestTimer(requestTimer)
                .featureProvider(featureProviderOptional)
                .statsLogger(statsLogger);
        LOG.info("Created shared client builder {} : zkServers = {}, ledgersPath = {}, numIOThreads = {}",
                new Object[] { bkcName, zkServers, ledgersPath, conf.getBKClientNumberIOThreads() });
        return builder;
    }

    @VisibleForTesting
    public ZooKeeperClient getSharedWriterZKCForDL() {
        return sharedWriterZKCForDL;
    }

    @VisibleForTesting
    public BookKeeperClient getReaderBKC() {
        return readerBKC;
    }

    @VisibleForTesting
    public LogStreamMetadataStore getWriterStreamMetadataStore() {
        return writerStreamMetadataStore;
    }

    @VisibleForTesting
    public LedgerAllocator getLedgerAllocator() {
        return allocator;
    }

    /**
     * Run given <i>handler</i> by providing an available zookeeper client.
     *
     * @param handler
     *          Handler to process with provided zookeeper client.
     * @return result processed by handler.
     * @throws IOException
     */
    private <T> T withZooKeeperClient(ZooKeeperClientHandler<T> handler) throws IOException {
        checkState();
        return handler.handle(sharedWriterZKCForDL);
    }

    /**
     * Create a DistributedLogManager for <i>nameOfLogStream</i>, with default shared clients.
     *
     * @param nameOfLogStream
     *          name of log stream
     * @return distributedlog manager
     * @throws com.twitter.distributedlog.exceptions.InvalidStreamNameException if stream name is invalid
     * @throws IOException
     */
    public DistributedLogManager createDistributedLogManagerWithSharedClients(String nameOfLogStream)
        throws InvalidStreamNameException, IOException {
        return createDistributedLogManager(nameOfLogStream, ClientSharingOption.SharedClients);
    }

    /**
     * Create a DistributedLogManager for <i>nameOfLogStream</i>, with specified client sharing options.
     *
     * @param nameOfLogStream
     *          name of log stream.
     * @param clientSharingOption
     *          specifies if the ZK/BK clients are shared
     * @return distributedlog manager instance.
     * @throws com.twitter.distributedlog.exceptions.InvalidStreamNameException if stream name is invalid
     * @throws IOException
     */
    public DistributedLogManager createDistributedLogManager(
            String nameOfLogStream,
            ClientSharingOption clientSharingOption)
        throws InvalidStreamNameException, IOException {
        Optional<DistributedLogConfiguration> logConfiguration = Optional.absent();
        Optional<DynamicDistributedLogConfiguration> dynamicLogConfiguration = Optional.absent();
        return createDistributedLogManager(
                nameOfLogStream,
                clientSharingOption,
                logConfiguration,
                dynamicLogConfiguration);
    }

    /**
     * Create a DistributedLogManager for <i>nameOfLogStream</i>, with specified client sharing options.
     * Override whitelisted stream-level configuration settings with settings found in
     * <i>logConfiguration</i>.
     *
     *
     * @param nameOfLogStream
     *          name of log stream.
     * @param clientSharingOption
     *          specifies if the ZK/BK clients are shared
     * @param logConfiguration
     *          stream configuration overrides.
     * @param dynamicLogConfiguration
     *          dynamic stream configuration overrides.
     * @return distributedlog manager instance.
     * @throws com.twitter.distributedlog.exceptions.InvalidStreamNameException if stream name is invalid
     * @throws IOException
     */
    public DistributedLogManager createDistributedLogManager(
            String nameOfLogStream,
            ClientSharingOption clientSharingOption,
            Optional<DistributedLogConfiguration> logConfiguration,
            Optional<DynamicDistributedLogConfiguration> dynamicLogConfiguration)
        throws InvalidStreamNameException, IOException {
        if (bkdlConfig.isFederatedNamespace()) {
            throw new UnsupportedOperationException("Use DistributedLogNamespace methods for federated namespace");
        }
        return createDistributedLogManager(
                namespace,
                nameOfLogStream,
                clientSharingOption,
                logConfiguration,
                dynamicLogConfiguration,
                Optional.<StatsLogger>absent()
        );
    }

    /**
     * Open the log in location <i>uri</i>.
     *
     * @param uri
     *          location to store the log
     * @param nameOfLogStream
     *          name of the log
     * @param clientSharingOption
     *          client sharing option
     * @param logConfiguration
     *          optional stream configuration
     * @param dynamicLogConfiguration
     *          dynamic stream configuration overrides.
     * @return distributedlog manager instance.
     * @throws InvalidStreamNameException if the stream name is invalid
     * @throws IOException
     */
    protected DistributedLogManager createDistributedLogManager(
            URI uri,
            String nameOfLogStream,
            ClientSharingOption clientSharingOption,
            Optional<DistributedLogConfiguration> logConfiguration,
            Optional<DynamicDistributedLogConfiguration> dynamicLogConfiguration,
            Optional<StatsLogger> perStreamStatsLogger)
        throws InvalidStreamNameException, IOException {
        // Make sure the name is well formed
        checkState();
        validateName(nameOfLogStream);

        DistributedLogConfiguration mergedConfiguration = new DistributedLogConfiguration();
        mergedConfiguration.addConfiguration(conf);
        mergedConfiguration.loadStreamConf(logConfiguration);
        // If dynamic config was not provided, default to a static view of the global configuration.
        DynamicDistributedLogConfiguration dynConf = null;
        if (dynamicLogConfiguration.isPresent()) {
            dynConf = dynamicLogConfiguration.get();
        } else {
            dynConf = ConfUtils.getConstDynConf(mergedConfiguration);
        }

        ZooKeeperClientBuilder writerZKCBuilderForDL = null;
        ZooKeeperClientBuilder readerZKCBuilderForDL = null;
        ZooKeeperClient writerZKCForBK = null;
        ZooKeeperClient readerZKCForBK = null;
        BookKeeperClientBuilder writerBKCBuilder = null;
        BookKeeperClientBuilder readerBKCBuilder = null;

        switch(clientSharingOption) {
            case SharedClients:
                writerZKCBuilderForDL = sharedWriterZKCBuilderForDL;
                readerZKCBuilderForDL = sharedReaderZKCBuilderForDL;
                writerBKCBuilder = sharedWriterBKCBuilder;
                readerBKCBuilder = sharedReaderBKCBuilder;
                break;
            case SharedZKClientPerStreamBKClient:
                writerZKCBuilderForDL = sharedWriterZKCBuilderForDL;
                readerZKCBuilderForDL = sharedReaderZKCBuilderForDL;
                synchronized (this) {
                    if (null == this.sharedWriterZKCForBK) {
                        this.sharedWriterZKCBuilderForBK = createBKZKClientBuilder(
                            String.format("bkzk:%s:factory_writer_shared", uri),
                            mergedConfiguration,
                            bkdlConfig.getBkZkServersForWriter(),
                            statsLogger.scope("bkzk_factory_writer_shared"));
                        this.sharedWriterZKCForBK = this.sharedWriterZKCBuilderForBK.build();
                    }
                    if (null == this.sharedReaderZKCForBK) {
                        if (bkdlConfig.getBkZkServersForWriter().equals(bkdlConfig.getBkZkServersForReader())) {
                            this.sharedReaderZKCBuilderForBK = this.sharedWriterZKCBuilderForBK;
                        } else {
                            this.sharedReaderZKCBuilderForBK = createBKZKClientBuilder(
                                String.format("bkzk:%s:factory_reader_shared", uri),
                                mergedConfiguration,
                                bkdlConfig.getBkZkServersForReader(),
                                statsLogger.scope("bkzk_factory_reader_shared"));
                        }
                        this.sharedReaderZKCForBK = this.sharedReaderZKCBuilderForBK.build();
                    }
                    writerZKCForBK = this.sharedWriterZKCForBK;
                    readerZKCForBK = this.sharedReaderZKCForBK;
                }
                break;
        }

        LedgerAllocator dlmLedgerAlloctor = null;
        if (ClientSharingOption.SharedClients == clientSharingOption) {
            dlmLedgerAlloctor = this.allocator;
        }
        // if there's a specified perStreamStatsLogger, user it, otherwise use the default one.
        StatsLogger perLogStatsLogger = perStreamStatsLogger.or(this.perLogStatsLogger);

        return new BKDistributedLogManager(
                nameOfLogStream,                    /* Log Name */
                mergedConfiguration,                /* Configuration */
                dynConf,                            /* Dynamic Configuration */
                uri,                                /* Namespace */
                writerZKCBuilderForDL,              /* ZKC Builder for DL Writer */
                readerZKCBuilderForDL,              /* ZKC Builder for DL Reader */
                writerZKCForBK,                     /* ZKC for BookKeeper for DL Writers */
                readerZKCForBK,                     /* ZKC for BookKeeper for DL Readers */
                writerBKCBuilder,                   /* BookKeeper Builder for DL Writers */
                readerBKCBuilder,                   /* BookKeeper Builder for DL Readers */
                writerStreamMetadataStore,         /* Log Segment Metadata Store for DL Writers */
                readerStreamMetadataStore,         /* Log Segment Metadata Store for DL Readers */
                logSegmentMetadataCache,            /* Log Segment Metadata Cache */
                scheduler,                          /* DL scheduler */
                readAheadExecutor,                  /* Read Aheader Executor */
                channelFactory,                     /* Netty Channel Factory */
                requestTimer,                       /* Request Timer */
                readAheadExceptionsLogger,          /* ReadAhead Exceptions Logger */
                clientId,                           /* Client Id */
                regionId,                           /* Region Id */
                dlmLedgerAlloctor,                  /* Ledger Allocator */
                writeLimiter,                       /* Write Limiter */
                featureProvider.scope("dl"),        /* Feature Provider */
                statsLogger,                        /* Stats Logger */
                perLogStatsLogger                   /* Per Log Stats Logger */
        );
    }

    public MetadataAccessor createMetadataAccessor(String nameOfMetadataNode)
            throws InvalidStreamNameException, IOException {
        if (bkdlConfig.isFederatedNamespace()) {
            throw new UnsupportedOperationException("Use DistributedLogNamespace methods for federated namespace");
        }
        checkState();
        validateName(nameOfMetadataNode);
        return new ZKMetadataAccessor(nameOfMetadataNode, conf, namespace,
                sharedWriterZKCBuilderForDL, sharedReaderZKCBuilderForDL, statsLogger);
    }

    public Collection<String> enumerateAllLogsInNamespace()
        throws IOException, IllegalArgumentException {
        if (bkdlConfig.isFederatedNamespace()) {
            throw new UnsupportedOperationException("Use DistributedLogNamespace methods for federated namespace");
        }
        return Sets.newHashSet(getLogs());
    }

    public Map<String, byte[]> enumerateLogsWithMetadataInNamespace()
        throws IOException, IllegalArgumentException {
        if (bkdlConfig.isFederatedNamespace()) {
            throw new UnsupportedOperationException("Use DistributedLogNamespace methods for federated namespace");
        }
        return withZooKeeperClient(new ZooKeeperClientHandler<Map<String, byte[]>>() {
            @Override
            public Map<String, byte[]> handle(ZooKeeperClient zkc) throws IOException {
                return enumerateLogsWithMetadataInternal(zkc, conf, namespace);
            }
        });
    }

    private static void validateInput(DistributedLogConfiguration conf, URI uri, String nameOfStream)
        throws IllegalArgumentException, InvalidStreamNameException {
        validateConfAndURI(conf, uri);
        validateName(nameOfStream);
    }

    public static Map<String, byte[]> enumerateLogsWithMetadataInNamespace(final DistributedLogConfiguration conf, final URI uri)
        throws IOException, IllegalArgumentException {
        return withZooKeeperClient(new ZooKeeperClientHandler<Map<String, byte[]>>() {
            @Override
            public Map<String, byte[]> handle(ZooKeeperClient zkc) throws IOException {
                return enumerateLogsWithMetadataInternal(zkc, conf, uri);
            }
        }, conf, uri);
    }

    private static Map<String, byte[]> enumerateLogsWithMetadataInternal(ZooKeeperClient zkc,
                                                                         DistributedLogConfiguration conf, URI uri)
        throws IOException, IllegalArgumentException {
        validateConfAndURI(conf, uri);
        String namespaceRootPath = uri.getPath();
        HashMap<String, byte[]> result = new HashMap<String, byte[]>();
        try {
            ZooKeeper zk = Utils.sync(zkc, namespaceRootPath);
            Stat currentStat = zk.exists(namespaceRootPath, false);
            if (currentStat == null) {
                return result;
            }
            List<String> children = zk.getChildren(namespaceRootPath, false);
            for(String child: children) {
                if (isReservedStreamName(child)) {
                    continue;
                }
                String zkPath = String.format("%s/%s", namespaceRootPath, child);
                currentStat = zk.exists(zkPath, false);
                if (currentStat == null) {
                    result.put(child, new byte[0]);
                } else {
                    result.put(child, zk.getData(zkPath, false, currentStat));
                }
            }
        } catch (InterruptedException ie) {
            LOG.error("Interrupted while deleting " + namespaceRootPath, ie);
            throw new IOException("Interrupted while reading " + namespaceRootPath, ie);
        } catch (KeeperException ke) {
            LOG.error("Error reading" + namespaceRootPath + "entry in zookeeper", ke);
            throw new IOException("Error reading" + namespaceRootPath + "entry in zookeeper", ke);
        }
        return result;
    }

    private void checkState() throws IOException {
        if (closed.get()) {
            LOG.error("BKDistributedLogNamespace {} is already closed", namespace);
            throw new AlreadyClosedException("Namespace " + namespace + " is already closed");
        }
    }

    /**
     * Close the distributed log manager factory, freeing any resources it may hold.
     */
    @Override
    public void close() {
        ZooKeeperClient writerZKC;
        ZooKeeperClient readerZKC;
        AccessControlManager acm;
        if (closed.compareAndSet(false, true)) {
            writerZKC = sharedWriterZKCForBK;
            readerZKC = sharedReaderZKCForBK;
            acm = accessControlManager;
        } else {
            return;
        }
        if (null != acm) {
            acm.close();
            LOG.info("Access Control Manager Stopped.");
        }

        // Close the allocator
        if (null != allocator) {
            Utils.closeQuietly(allocator);
            LOG.info("Ledger Allocator stopped.");
        }

        this.writeLimiter.close();

        // Shutdown log segment metadata stores
        Utils.close(writerStreamMetadataStore);
        Utils.close(readerStreamMetadataStore);

        // Shutdown the schedulers
        SchedulerUtils.shutdownScheduler(scheduler, conf.getSchedulerShutdownTimeoutMs(),
            TimeUnit.MILLISECONDS);
        LOG.info("Executor Service Stopped.");
        if (scheduler != readAheadExecutor) {
            SchedulerUtils.shutdownScheduler(readAheadExecutor, conf.getSchedulerShutdownTimeoutMs(),
                TimeUnit.MILLISECONDS);
            LOG.info("ReadAhead Executor Service Stopped.");
        }

        writerBKC.close();
        readerBKC.close();
        sharedWriterZKCForDL.close();
        sharedReaderZKCForDL.close();

        // Close shared zookeeper clients for bk
        if (null != writerZKC) {
            writerZKC.close();
        }
        if (null != readerZKC) {
            readerZKC.close();
        }
        channelFactory.releaseExternalResources();
        LOG.info("Release external resources used by channel factory.");
        requestTimer.stop();
        LOG.info("Stopped request timer");
    }
}
