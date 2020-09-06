/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.executor;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils;
import com.linkedin.kafka.cruisecontrol.config.KafkaCruiseControlConfig;
import com.linkedin.kafka.cruisecontrol.common.KafkaCruiseControlThreadFactory;
import com.linkedin.kafka.cruisecontrol.common.MetadataClient;
import com.linkedin.kafka.cruisecontrol.config.constants.ExecutorConfig;
import com.linkedin.kafka.cruisecontrol.detector.AnomalyDetector;
import com.linkedin.kafka.cruisecontrol.exception.OngoingExecutionException;
import com.linkedin.kafka.cruisecontrol.executor.strategy.ReplicaMovementStrategy;
import com.linkedin.kafka.cruisecontrol.model.ReplicaPlacementInfo;
import com.linkedin.kafka.cruisecontrol.monitor.LoadMonitor;
import com.linkedin.kafka.cruisecontrol.servlet.UserTaskManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import kafka.zk.KafkaZkClient;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterPartitionReassignmentsResult;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Collection;
import java.util.List;

import static com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils.currentUtcDate;
import static com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils.toDateString;
import static com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils.OPERATION_LOGGER;
import static com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils.TIME_ZONE;
import static com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUtils.DATE_FORMAT;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutionTask.State.*;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutorState.State.*;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutionTask.TaskType.*;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutionTaskTracker.ExecutionTasksSummary;
import static com.linkedin.kafka.cruisecontrol.executor.ExecutorAdminUtils.*;
import static com.linkedin.kafka.cruisecontrol.monitor.MonitorUtils.UNIT_INTERVAL_TO_PERCENTAGE;
import static com.linkedin.kafka.cruisecontrol.monitor.sampling.MetricSampler.SamplingMode.ALL;
import static com.linkedin.kafka.cruisecontrol.monitor.sampling.MetricSampler.SamplingMode.BROKER_METRICS_ONLY;
import static org.apache.kafka.clients.admin.DescribeReplicaLogDirsResult.ReplicaLogDirInfo;

/**
 * Executor for Kafka GoalOptimizer.
 * <p>
 * The executor class is responsible for talking to the Kafka cluster to execute the rebalance proposals.
 *
 * The executor is thread-safe.
 */
public class Executor {
  private static final Logger LOG = LoggerFactory.getLogger(Executor.class);
  private static final Logger OPERATION_LOG = LoggerFactory.getLogger(OPERATION_LOGGER);
  // TODO: Make this configurable.
  public static final long MIN_EXECUTION_PROGRESS_CHECK_INTERVAL_MS = 5000L;
  // A special timestamp to indicate that a broker is a permanent part of recently removed or demoted broker set.
  private static final long PERMANENT_TIMESTAMP = 0L;
  private static final String ZK_EXECUTOR_METRIC_GROUP = "CruiseControlExecutor";
  private static final String ZK_EXECUTOR_METRIC_TYPE = "Executor";
  // TODO: Make this configurable.
  public static final long SLOW_TASK_ALERTING_BACKOFF_TIME_MS = 60000L;
  public static final long EXECUTION_TASK_FUTURE_ERROR_VERIFICATION_TIMEOUT_MS = 10000L;
  // The execution progress is controlled by the ExecutionTaskManager.
  private final ExecutionTaskManager _executionTaskManager;
  private final MetadataClient _metadataClient;
  private final long _defaultExecutionProgressCheckIntervalMs;
  private Long _requestedExecutionProgressCheckIntervalMs;
  private final ExecutorService _proposalExecutor;
  private final KafkaZkClient _kafkaZkClient;
  private final AdminClient _adminClient;
  private final double _leaderMovementTimeoutMs;

  private static final int NO_STOP_EXECUTION = 0;
  private static final int STOP_EXECUTION = 1;
  private static final int FORCE_STOP_EXECUTION = 2;

  // Some state for external service to query
  private final AtomicInteger _stopSignal;
  private final Time _time;
  private volatile boolean _hasOngoingExecution;
  private volatile ExecutorState _executorState;
  private volatile String _uuid;
  private volatile Supplier<String> _reasonSupplier;
  private final ExecutorNotifier _executorNotifier;

  private final AtomicInteger _numExecutionStopped;
  private final AtomicInteger _numExecutionStoppedByUser;
  private final AtomicBoolean _executionStoppedByUser;
  private final AtomicBoolean _ongoingExecutionIsBeingModified;
  private final AtomicInteger _numExecutionStartedInKafkaAssignerMode;
  private final AtomicInteger _numExecutionStartedInNonKafkaAssignerMode;
  private volatile boolean _isKafkaAssignerMode;
  private volatile boolean _skipAutoRefreshingConcurrency;
  // TODO: Execution history is currently kept in memory, but ideally we should move it to a persistent store.
  private final long _demotionHistoryRetentionTimeMs;
  private final long _removalHistoryRetentionTimeMs;
  private final ConcurrentMap<Integer, Long> _latestDemoteStartTimeMsByBrokerId;
  private final ConcurrentMap<Integer, Long> _latestRemoveStartTimeMsByBrokerId;
  private final ScheduledExecutorService _executionHistoryScannerExecutor;
  private UserTaskManager _userTaskManager;
  private final AnomalyDetector _anomalyDetector;
  private final ConcurrencyAdjuster _concurrencyAdjuster;
  private final ScheduledExecutorService _concurrencyAdjusterExecutor;
  private volatile boolean _concurrencyAdjusterEnabled;
  private final KafkaCruiseControlConfig _config;

  /**
   * The executor class that execute the proposals generated by optimizer.
   *
   * @param config The configurations for Cruise Control.
   */
  public Executor(KafkaCruiseControlConfig config,
                  Time time,
                  MetricRegistry dropwizardMetricRegistry,
                  AnomalyDetector anomalyDetector) {
    this(config, time, dropwizardMetricRegistry, null, null, anomalyDetector);
  }

  /**
   * The executor class that execute the proposals generated by optimizer.
   * Package private for unit test.
   *
   * @param config The configurations for Cruise Control.
   */
  Executor(KafkaCruiseControlConfig config,
           Time time,
           MetricRegistry dropwizardMetricRegistry,
           MetadataClient metadataClient,
           ExecutorNotifier executorNotifier,
           AnomalyDetector anomalyDetector) {
    String zkUrl = config.getString(ExecutorConfig.ZOOKEEPER_CONNECT_CONFIG);
    _numExecutionStopped = new AtomicInteger(0);
    _numExecutionStoppedByUser = new AtomicInteger(0);
    _executionStoppedByUser = new AtomicBoolean(false);
    _ongoingExecutionIsBeingModified = new AtomicBoolean(false);
    _numExecutionStartedInKafkaAssignerMode = new AtomicInteger(0);
    _numExecutionStartedInNonKafkaAssignerMode = new AtomicInteger(0);
    _isKafkaAssignerMode = false;
    _skipAutoRefreshingConcurrency = false;
    ExecutionUtils.init(config);
    _config = config;

    _time = time;
    boolean zkSecurityEnabled = config.getBoolean(ExecutorConfig.ZOOKEEPER_SECURITY_ENABLED_CONFIG);
    _kafkaZkClient = KafkaCruiseControlUtils.createKafkaZkClient(zkUrl, ZK_EXECUTOR_METRIC_GROUP, ZK_EXECUTOR_METRIC_TYPE,
                                                                 zkSecurityEnabled);
    _adminClient = KafkaCruiseControlUtils.createAdminClient(KafkaCruiseControlUtils.parseAdminClientConfigs(config));
    _executionTaskManager = new ExecutionTaskManager(_adminClient, dropwizardMetricRegistry, time, config);
    // Register gauge sensors.
    registerGaugeSensors(dropwizardMetricRegistry);
    _metadataClient = metadataClient != null ? metadataClient
                                             : new MetadataClient(config,
                                                                  new Metadata(ExecutionUtils.METADATA_REFRESH_BACKOFF,
                                                                               ExecutionUtils.METADATA_EXPIRY_MS,
                                                                               new LogContext(),
                                                                               new ClusterResourceListeners()),
                                                                  -1L,
                                                                  time);
    _defaultExecutionProgressCheckIntervalMs = config.getLong(ExecutorConfig.EXECUTION_PROGRESS_CHECK_INTERVAL_MS_CONFIG);
    _leaderMovementTimeoutMs = config.getLong(ExecutorConfig.LEADER_MOVEMENT_TIMEOUT_MS_CONFIG);
    _requestedExecutionProgressCheckIntervalMs = null;
    _proposalExecutor =
        Executors.newSingleThreadExecutor(new KafkaCruiseControlThreadFactory("ProposalExecutor", false, LOG));
    _latestDemoteStartTimeMsByBrokerId = new ConcurrentHashMap<>();
    _latestRemoveStartTimeMsByBrokerId = new ConcurrentHashMap<>();
    _executorState = ExecutorState.noTaskInProgress(recentlyDemotedBrokers(), recentlyRemovedBrokers());
    _stopSignal = new AtomicInteger(NO_STOP_EXECUTION);
    _hasOngoingExecution = false;
    _uuid = null;
    _reasonSupplier = null;
    _executorNotifier = executorNotifier != null ? executorNotifier
                                                 : config.getConfiguredInstance(ExecutorConfig.EXECUTOR_NOTIFIER_CLASS_CONFIG,
                                                                                ExecutorNotifier.class);
    // Must be set before execution via #setUserTaskManager(UserTaskManager)
    _userTaskManager = null;
    if (anomalyDetector == null) {
      throw new IllegalStateException("Anomaly detector cannot be null.");
    }
    _anomalyDetector = anomalyDetector;
    _demotionHistoryRetentionTimeMs = config.getLong(ExecutorConfig.DEMOTION_HISTORY_RETENTION_TIME_MS_CONFIG);
    _removalHistoryRetentionTimeMs = config.getLong(ExecutorConfig.REMOVAL_HISTORY_RETENTION_TIME_MS_CONFIG);
    _concurrencyAdjusterEnabled = config.getBoolean(ExecutorConfig.CONCURRENCY_ADJUSTER_ENABLED_CONFIG);
    _concurrencyAdjusterExecutor = Executors.newSingleThreadScheduledExecutor(
        new KafkaCruiseControlThreadFactory(ConcurrencyAdjuster.class.getSimpleName(), true, null));
    long intervalMs = config.getLong(ExecutorConfig.CONCURRENCY_ADJUSTER_INTERVAL_MS_CONFIG);
    _concurrencyAdjuster = new ConcurrencyAdjuster();
    _concurrencyAdjusterExecutor.scheduleAtFixedRate(_concurrencyAdjuster, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    _executionHistoryScannerExecutor = Executors.newSingleThreadScheduledExecutor(
        new KafkaCruiseControlThreadFactory(ExecutionHistoryScanner.class.getSimpleName(), true, null));
    _executionHistoryScannerExecutor.scheduleAtFixedRate(new ExecutionHistoryScanner(),
                                                         ExecutionUtils.EXECUTION_HISTORY_SCANNER_INITIAL_DELAY_SECONDS,
                                                         ExecutionUtils.EXECUTION_HISTORY_SCANNER_PERIOD_SECONDS,
                                                         TimeUnit.SECONDS);
  }

  /**
   * @return The tasks that are {@link ExecutionTask.State#IN_PROGRESS} or {@link ExecutionTask.State#ABORTING} for all task types.
   */
  public Set<ExecutionTask> inExecutionTasks() {
    return _executionTaskManager.inExecutionTasks();
  }

  /**
   * Dynamically set the interval between checking and updating (if needed) the progress of an initiated execution.
   * To prevent setting this value to a very small value by mistake, ensure that the requested interval is greater than
   * the {@link #MIN_EXECUTION_PROGRESS_CHECK_INTERVAL_MS}.
   *
   * @param requestedExecutionProgressCheckIntervalMs The interval between checking and updating the progress of an initiated
   *                                                  execution (if null, use {@link #_defaultExecutionProgressCheckIntervalMs}).
   */
  public synchronized void setRequestedExecutionProgressCheckIntervalMs(Long requestedExecutionProgressCheckIntervalMs) {
    if (requestedExecutionProgressCheckIntervalMs != null
        && requestedExecutionProgressCheckIntervalMs < MIN_EXECUTION_PROGRESS_CHECK_INTERVAL_MS) {
      throw new IllegalArgumentException("Attempt to set execution progress check interval ["
                                         + requestedExecutionProgressCheckIntervalMs
                                         + "ms] to smaller than the minimum execution progress check interval in cluster ["
                                         + MIN_EXECUTION_PROGRESS_CHECK_INTERVAL_MS + "ms].");
    }
    _requestedExecutionProgressCheckIntervalMs = requestedExecutionProgressCheckIntervalMs;
  }

  /**
   * @return The interval between checking and updating (if needed) the progress of an initiated execution.
   */
  public long executionProgressCheckIntervalMs() {
    return _requestedExecutionProgressCheckIntervalMs == null ? _defaultExecutionProgressCheckIntervalMs
                                                              : _requestedExecutionProgressCheckIntervalMs;
  }

  /**
   * Register gauge sensors.
   */
  private void registerGaugeSensors(MetricRegistry dropwizardMetricRegistry) {
    String name = "Executor";
    dropwizardMetricRegistry.register(MetricRegistry.name(name, ExecutionUtils.GAUGE_EXECUTION_STOPPED),
                                      (Gauge<Integer>) this::numExecutionStopped);
    dropwizardMetricRegistry.register(MetricRegistry.name(name, ExecutionUtils.GAUGE_EXECUTION_STOPPED_BY_USER),
                                      (Gauge<Integer>) this::numExecutionStoppedByUser);
    dropwizardMetricRegistry.register(MetricRegistry.name(name, ExecutionUtils.GAUGE_EXECUTION_STARTED_IN_KAFKA_ASSIGNER_MODE),
                                      (Gauge<Integer>) this::numExecutionStartedInKafkaAssignerMode);
    dropwizardMetricRegistry.register(MetricRegistry.name(name, ExecutionUtils.GAUGE_EXECUTION_STARTED_IN_NON_KAFKA_ASSIGNER_MODE),
                                      (Gauge<Integer>) this::numExecutionStartedInNonKafkaAssignerMode);
    dropwizardMetricRegistry.register(MetricRegistry.name(name, ExecutionUtils.GAUGE_EXECUTION_INTER_BROKER_PARTITION_MOVEMENTS_PER_BROKER_CAP),
                                      (Gauge<Integer>) _executionTaskManager::interBrokerPartitionMovementConcurrency);
    dropwizardMetricRegistry.register(MetricRegistry.name(name, ExecutionUtils.GAUGE_EXECUTION_INTRA_BROKER_PARTITION_MOVEMENTS_PER_BROKER_CAP),
                                      (Gauge<Integer>) _executionTaskManager::intraBrokerPartitionMovementConcurrency);
    dropwizardMetricRegistry.register(MetricRegistry.name(name, ExecutionUtils.GAUGE_EXECUTION_LEADERSHIP_MOVEMENTS_GLOBAL_CAP),
                                      (Gauge<Integer>) _executionTaskManager::leadershipMovementConcurrency);
  }

  private void removeExpiredDemotionHistory() {
    LOG.debug("Remove expired demotion history");
    _latestDemoteStartTimeMsByBrokerId.entrySet().removeIf(entry -> {
      long startTime = entry.getValue();
      return startTime != PERMANENT_TIMESTAMP && startTime + _demotionHistoryRetentionTimeMs < _time.milliseconds();
    });
  }

  private void removeExpiredRemovalHistory() {
    LOG.debug("Remove expired broker removal history");
    _latestRemoveStartTimeMsByBrokerId.entrySet().removeIf(entry -> {
      long startTime = entry.getValue();
      return startTime != PERMANENT_TIMESTAMP && startTime + _removalHistoryRetentionTimeMs < _time.milliseconds();
    });
  }

  /**
   * A runnable class to remove expired execution history.
   */
  private class ExecutionHistoryScanner implements Runnable {
    @Override
    public void run() {
      try {
        removeExpiredDemotionHistory();
        removeExpiredRemovalHistory();
      } catch (Throwable t) {
        LOG.warn("Received exception when trying to expire execution history.", t);
      }
    }
  }

  /**
   * A runnable class to auto-adjust the allowed inter-broker partition reassignment concurrency for ongoing executions
   * using selected broker metrics and based on additive-increase/multiplicative-decrease (AIMD) feedback control algorithm.
   * Skips concurrency adjustment for demote operations.
   */
  private class ConcurrencyAdjuster implements Runnable {
    private final int _maxPartitionMovementsPerBroker;
    private LoadMonitor _loadMonitor;

    public ConcurrencyAdjuster() {
      _maxPartitionMovementsPerBroker = _config.getInt(ExecutorConfig.CONCURRENCY_ADJUSTER_MAX_PARTITION_MOVEMENTS_PER_BROKER_CONFIG);
      _loadMonitor = null;
    }

    /**
     * Initialize the inter-broker partition reassignment concurrency adjustment with the load monitor and the initially
     * requested inter-broker partition reassignment concurrency.
     *
     * @param loadMonitor Load monitor.
     * @param requestedInterBrokerPartitionMovementConcurrency The maximum number of concurrent inter-broker partition movements
     *                                                         per broker(if null, use num.concurrent.partition.movements.per.broker).
     */
    public synchronized void initAdjustment(LoadMonitor loadMonitor, Integer requestedInterBrokerPartitionMovementConcurrency) {
      _loadMonitor = loadMonitor;
      setRequestedInterBrokerPartitionMovementConcurrency(requestedInterBrokerPartitionMovementConcurrency);
    }

    private boolean canRefreshConcurrency() {
      return _concurrencyAdjusterEnabled && _executorState.state() == ExecutorState.State.INTER_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS
             && !_skipAutoRefreshingConcurrency && _loadMonitor != null;
    }

    private synchronized void refreshConcurrency() {
      if (canRefreshConcurrency()) {
        Integer recommendedConcurrency = ExecutionUtils.recommendedConcurrency(_loadMonitor.currentBrokerMetricValues(),
                                                                               _executionTaskManager.interBrokerPartitionMovementConcurrency(),
                                                                               _maxPartitionMovementsPerBroker);
        if (recommendedConcurrency != null) {
          setRequestedInterBrokerPartitionMovementConcurrency(recommendedConcurrency);
        }
      }
    }

    @Override
    public void run() {
      try {
        refreshConcurrency();
      } catch (Throwable t) {
        LOG.warn("Received exception when trying to adjust inter-broker replica reassignment concurrency.", t);
      }
    }
  }

  /**
   * Recently demoted brokers are the ones
   * <ul>
   *   <li>for which a broker demotion was started, regardless of how the corresponding process was completed, or</li>
   *   <li>that are explicitly marked so -- e.g. via a pluggable component using {@link #addRecentlyDemotedBrokers(Set)}.</li>
   * </ul>
   *
   * @return IDs of recently demoted brokers -- i.e. demoted within the last {@link #_demotionHistoryRetentionTimeMs}.
   */
  public Set<Integer> recentlyDemotedBrokers() {
    return Collections.unmodifiableSet(_latestDemoteStartTimeMsByBrokerId.keySet());
  }

  /**
   * Recently removed brokers are the ones
   * <ul>
   *   <li>for which a broker removal was started, regardless of how the corresponding process was completed, or</li>
   *   <li>that are explicitly marked so -- e.g. via a pluggable component using {@link #addRecentlyRemovedBrokers(Set)}.</li>
   * </ul>
   *
   * @return IDs of recently removed brokers -- i.e. removed within the last {@link #_removalHistoryRetentionTimeMs}.
   */
  public Set<Integer> recentlyRemovedBrokers() {
    return Collections.unmodifiableSet(_latestRemoveStartTimeMsByBrokerId.keySet());
  }

  /**
   * Drop the given brokers from the recently removed brokers.
   *
   * @param brokersToDrop Brokers to drop from the {@link #_latestRemoveStartTimeMsByBrokerId}.
   * @return {@code true} if any elements were removed from {@link #_latestRemoveStartTimeMsByBrokerId}.
   */
  public boolean dropRecentlyRemovedBrokers(Set<Integer> brokersToDrop) {
    return _latestRemoveStartTimeMsByBrokerId.entrySet().removeIf(entry -> (brokersToDrop.contains(entry.getKey())));
  }

  /**
   * Drop the given brokers from the recently demoted brokers.
   *
   * @param brokersToDrop Brokers to drop from the {@link #_latestDemoteStartTimeMsByBrokerId}.
   * @return {@code true} if any elements were removed from {@link #_latestDemoteStartTimeMsByBrokerId}.
   */
  public boolean dropRecentlyDemotedBrokers(Set<Integer> brokersToDrop) {
    return _latestDemoteStartTimeMsByBrokerId.entrySet().removeIf(entry -> (brokersToDrop.contains(entry.getKey())));
  }

  /**
   * Add the given brokers to the recently removed brokers permanently -- i.e. until they are explicitly dropped by user.
   * If given set has brokers that were already removed recently, make them a permanent part of recently removed brokers.
   *
   * @param brokersToAdd Brokers to add to the {@link #_latestRemoveStartTimeMsByBrokerId}.
   */
  public void addRecentlyRemovedBrokers(Set<Integer> brokersToAdd) {
    brokersToAdd.forEach(brokerId -> _latestRemoveStartTimeMsByBrokerId.put(brokerId, PERMANENT_TIMESTAMP));
  }

  /**
   * Add the given brokers from the recently demoted brokers permanently -- i.e. until they are explicitly dropped by user.
   * If given set has brokers that were already demoted recently, make them a permanent part of recently demoted brokers.
   *
   * @param brokersToAdd Brokers to add to the {@link #_latestDemoteStartTimeMsByBrokerId}.
   */
  public void addRecentlyDemotedBrokers(Set<Integer> brokersToAdd) {
    brokersToAdd.forEach(brokerId -> _latestDemoteStartTimeMsByBrokerId.put(brokerId, PERMANENT_TIMESTAMP));
  }

  /**
   * @return The current executor state.
   */
  public ExecutorState state() {
    return _executorState;
  }

  /**
   * Enable or disable concurrency adjuster for the given concurrency type in the executor.
   *
   * <ul>
   *   <li>TODO: Support for concurrency adjusters of {@link ConcurrencyType#LEADERSHIP leadership} and
   *   {@link ConcurrencyType#INTRA_BROKER_REPLICA intra-broker replica} movements are pending
   *   <a href="https://github.com/linkedin/cruise-control/issues/1298">#1298</a> and
   *   <a href="https://github.com/linkedin/cruise-control/issues/1299">#1299</a>.</li>
   * </ul>
   * @param concurrencyType Type of concurrency for which to enable or disable concurrency adjuster.
   * @param isConcurrencyAdjusterEnabled {@code true} if concurrency adjuster is enabled for the given type, {@code false} otherwise.
   * @return {@code true} if concurrency adjuster was enabled before for the given concurrency type, {@code false} otherwise.
   */
  public boolean setConcurrencyAdjusterFor(ConcurrencyType concurrencyType, boolean isConcurrencyAdjusterEnabled) {
    if (concurrencyType != ConcurrencyType.INTER_BROKER_REPLICA) {
      throw new IllegalArgumentException(String.format("Concurrency adjuster for %s is not yet supported.", concurrencyType));
    }
    boolean oldValue = _concurrencyAdjusterEnabled;
    _concurrencyAdjusterEnabled = isConcurrencyAdjusterEnabled;
    return oldValue;
  }

  /**
   * Initialize proposal execution and start execution.
   *
   * @param proposals Proposals to be executed.
   * @param unthrottledBrokers Brokers that are not throttled in terms of the number of in/out replica movements.
   * @param removedBrokers Removed brokers, null if no brokers has been removed.
   * @param loadMonitor Load monitor.
   * @param requestedInterBrokerPartitionMovementConcurrency The maximum number of concurrent inter-broker partition movements
   *                                                         per broker(if null, use num.concurrent.partition.movements.per.broker).
   * @param requestedIntraBrokerPartitionMovementConcurrency The maximum number of concurrent intra-broker partition movements
   *                                                         (if null, use num.concurrent.intra.broker.partition.movements).
   * @param requestedLeadershipMovementConcurrency The maximum number of concurrent leader movements
   *                                               (if null, use num.concurrent.leader.movements).
   * @param requestedExecutionProgressCheckIntervalMs The interval between checking and updating the progress of an initiated
   *                                                  execution (if null, use execution.progress.check.interval.ms).
   * @param replicaMovementStrategy The strategy used to determine the execution order of generated replica movement tasks.
   * @param replicationThrottle The replication throttle (bytes/second) to apply to both leaders and followers
   *                            when executing a proposal (if null, no throttling is applied).
   * @param isTriggeredByUserRequest Whether the execution is triggered by a user request.
   * @param uuid UUID of the execution.
   * @param isKafkaAssignerMode {@code true} if kafka assigner mode, {@code false} otherwise.
   * @param skipAutoRefreshingConcurrency {@code true} to skip auto refreshing concurrency even if the concurrency adjuster
   *                                                 is enabled, {@code false} otherwise.
   */
  public synchronized void executeProposals(Collection<ExecutionProposal> proposals,
                                            Set<Integer> unthrottledBrokers,
                                            Set<Integer> removedBrokers,
                                            LoadMonitor loadMonitor,
                                            Integer requestedInterBrokerPartitionMovementConcurrency,
                                            Integer requestedIntraBrokerPartitionMovementConcurrency,
                                            Integer requestedLeadershipMovementConcurrency,
                                            Long requestedExecutionProgressCheckIntervalMs,
                                            ReplicaMovementStrategy replicaMovementStrategy,
                                            Long replicationThrottle,
                                            boolean isTriggeredByUserRequest,
                                            String uuid,
                                            boolean isKafkaAssignerMode,
                                            boolean skipAutoRefreshingConcurrency) throws OngoingExecutionException {
    setExecutionMode(isKafkaAssignerMode);
    sanityCheckExecuteProposals(loadMonitor, uuid);
    _skipAutoRefreshingConcurrency = skipAutoRefreshingConcurrency;
    try {
      initProposalExecution(proposals, unthrottledBrokers, requestedInterBrokerPartitionMovementConcurrency,
                            requestedIntraBrokerPartitionMovementConcurrency, requestedLeadershipMovementConcurrency,
                            requestedExecutionProgressCheckIntervalMs, replicaMovementStrategy, isTriggeredByUserRequest, loadMonitor);
      startExecution(loadMonitor, null, removedBrokers, replicationThrottle, isTriggeredByUserRequest);
    } catch (Exception e) {
      processExecuteProposalsFailure();
      throw e;
    }
  }

  private void sanityCheckExecuteProposals(LoadMonitor loadMonitor, String uuid) throws OngoingExecutionException {
    if (_hasOngoingExecution) {
      throw new OngoingExecutionException("Cannot execute new proposals while there is an ongoing execution.");
    }
    if (loadMonitor == null) {
      throw new IllegalArgumentException("Load monitor cannot be null.");
    }
    if (_executorState.state() != GENERATING_PROPOSALS_FOR_EXECUTION) {
      throw new IllegalStateException(String.format("Unexpected executor state %s. Initializing proposal execution requires"
                                                    + " generating proposals for execution.", _executorState.state()));
    }
    if (uuid == null || !uuid.equals(_uuid)) {
      throw new IllegalStateException(String.format("Attempt to initialize proposal execution with a UUID %s that differs from"
                                                    + " the UUID used for generating proposals for execution %s.", uuid, _uuid));
    }
  }

  private synchronized void initProposalExecution(Collection<ExecutionProposal> proposals,
                                                  Collection<Integer> brokersToSkipConcurrencyCheck,
                                                  Integer requestedInterBrokerPartitionMovementConcurrency,
                                                  Integer requestedIntraBrokerPartitionMovementConcurrency,
                                                  Integer requestedLeadershipMovementConcurrency,
                                                  Long requestedExecutionProgressCheckIntervalMs,
                                                  ReplicaMovementStrategy replicaMovementStrategy,
                                                  boolean isTriggeredByUserRequest,
                                                  LoadMonitor loadMonitor) {
    _executorState = ExecutorState.initializeProposalExecution(_uuid, _reasonSupplier.get(), recentlyDemotedBrokers(),
                                                               recentlyRemovedBrokers(), isTriggeredByUserRequest);
    _executionTaskManager.setExecutionModeForTaskTracker(_isKafkaAssignerMode);
    _executionTaskManager.addExecutionProposals(proposals, brokersToSkipConcurrencyCheck, _metadataClient.refreshMetadata().cluster(),
                                                replicaMovementStrategy);
    _concurrencyAdjuster.initAdjustment(loadMonitor, requestedInterBrokerPartitionMovementConcurrency);
    setRequestedIntraBrokerPartitionMovementConcurrency(requestedIntraBrokerPartitionMovementConcurrency);
    setRequestedLeadershipMovementConcurrency(requestedLeadershipMovementConcurrency);
    setRequestedExecutionProgressCheckIntervalMs(requestedExecutionProgressCheckIntervalMs);
  }

  /**
   * Initialize proposal execution and start demotion.
   *
   * @param proposals Proposals to be executed.
   * @param demotedBrokers Demoted brokers.
   * @param loadMonitor Load monitor.
   * @param concurrentSwaps The number of concurrent swap operations per broker.
   * @param requestedLeadershipMovementConcurrency The maximum number of concurrent leader movements
   *                                               (if null, use num.concurrent.leader.movements).
   * @param requestedExecutionProgressCheckIntervalMs The interval between checking and updating the progress of an initiated
   *                                                  execution (if null, use execution.progress.check.interval.ms).
   * @param replicationThrottle The replication throttle (bytes/second) to apply to both leaders and followers
   *                            while executing demotion proposals (if null, no throttling is applied).
   * @param replicaMovementStrategy The strategy used to determine the execution order of generated replica movement tasks.
   * @param isTriggeredByUserRequest Whether the execution is triggered by a user request.
   * @param uuid UUID of the execution.
   */
  public synchronized void executeDemoteProposals(Collection<ExecutionProposal> proposals,
                                                  Collection<Integer> demotedBrokers,
                                                  LoadMonitor loadMonitor,
                                                  Integer concurrentSwaps,
                                                  Integer requestedLeadershipMovementConcurrency,
                                                  Long requestedExecutionProgressCheckIntervalMs,
                                                  ReplicaMovementStrategy replicaMovementStrategy,
                                                  Long replicationThrottle,
                                                  boolean isTriggeredByUserRequest,
                                                  String uuid) throws OngoingExecutionException {
    setExecutionMode(false);
    sanityCheckExecuteProposals(loadMonitor, uuid);
    _skipAutoRefreshingConcurrency = true;
    try {
      initProposalExecution(proposals, demotedBrokers, concurrentSwaps, 0, requestedLeadershipMovementConcurrency,
                            requestedExecutionProgressCheckIntervalMs, replicaMovementStrategy, isTriggeredByUserRequest, loadMonitor);
      startExecution(loadMonitor, demotedBrokers, null, replicationThrottle, isTriggeredByUserRequest);
    } catch (Exception e) {
      processExecuteProposalsFailure();
      throw e;
    }
  }

  /**
   * Dynamically set the inter-broker partition movement concurrency per broker.
   *
   * @param requestedInterBrokerPartitionMovementConcurrency The maximum number of concurrent inter-broker partition movements
   *                                                         per broker.
   */
  public void setRequestedInterBrokerPartitionMovementConcurrency(Integer requestedInterBrokerPartitionMovementConcurrency) {
    _executionTaskManager.setRequestedInterBrokerPartitionMovementConcurrency(requestedInterBrokerPartitionMovementConcurrency);
  }

  /**
   * Dynamically set the intra-broker partition movement concurrency.
   *
   * @param requestedIntraBrokerPartitionMovementConcurrency The maximum number of concurrent intra-broker partition movements.
   */
  public void setRequestedIntraBrokerPartitionMovementConcurrency(Integer requestedIntraBrokerPartitionMovementConcurrency) {
    _executionTaskManager.setRequestedIntraBrokerPartitionMovementConcurrency(requestedIntraBrokerPartitionMovementConcurrency);
  }

  /**
   * Dynamically set the leadership movement concurrency.
   *
   * @param requestedLeadershipMovementConcurrency The maximum number of concurrent leader movements.
   */
  public void setRequestedLeadershipMovementConcurrency(Integer requestedLeadershipMovementConcurrency) {
    _executionTaskManager.setRequestedLeadershipMovementConcurrency(requestedLeadershipMovementConcurrency);
  }

  /**
   * Set the execution mode of the tasks to keep track of the ongoing execution mode via sensors.
   *
   * @param isKafkaAssignerMode True if kafka assigner mode, false otherwise.
   */
  private void setExecutionMode(boolean isKafkaAssignerMode) {
    _isKafkaAssignerMode = isKafkaAssignerMode;
  }

  /**
   * Allow a reference to {@link UserTaskManager} to be set externally and allow executor to retrieve information
   * about the request that generated execution.
   * @param userTaskManager a reference to {@link UserTaskManager} instance.
   */
  public void setUserTaskManager(UserTaskManager userTaskManager) {
    _userTaskManager = userTaskManager;
  }

  private int numExecutionStopped() {
    return _numExecutionStopped.get();
  }

  private int numExecutionStoppedByUser() {
    return _numExecutionStoppedByUser.get();
  }

  private int numExecutionStartedInKafkaAssignerMode() {
    return _numExecutionStartedInKafkaAssignerMode.get();
  }

  private int numExecutionStartedInNonKafkaAssignerMode() {
    return _numExecutionStartedInNonKafkaAssignerMode.get();
  }

  /**
   * Pause the load monitor and kick off the execution.
   *
   * @param loadMonitor Load monitor.
   * @param demotedBrokers Brokers to be demoted, null if no broker has been demoted.
   * @param removedBrokers Brokers to be removed, null if no broker has been removed.
   * @param replicationThrottle The replication throttle (bytes/second) to apply to both leaders and followers
   *                            while moving partitions (if null, no throttling is applied).
   * @param isTriggeredByUserRequest Whether the execution is triggered by a user request.
   */
  private void startExecution(LoadMonitor loadMonitor,
                              Collection<Integer> demotedBrokers,
                              Collection<Integer> removedBrokers,
                              Long replicationThrottle,
                              boolean isTriggeredByUserRequest) throws OngoingExecutionException {
    _executionStoppedByUser.set(false);
    sanityCheckOngoingMovement();
    _hasOngoingExecution = true;
    _anomalyDetector.maybeClearOngoingAnomalyDetectionTimeMs();
    _anomalyDetector.resetHasUnfixableGoals();
    _stopSignal.set(NO_STOP_EXECUTION);
    _executionStoppedByUser.set(false);
    if (_isKafkaAssignerMode) {
      _numExecutionStartedInKafkaAssignerMode.incrementAndGet();
    } else {
      _numExecutionStartedInNonKafkaAssignerMode.incrementAndGet();
    }
    _proposalExecutor.submit(
        new ProposalExecutionRunnable(loadMonitor, demotedBrokers, removedBrokers, replicationThrottle, isTriggeredByUserRequest));
  }

  /**
   * Sanity check whether there are ongoing (1) inter-broker or intra-broker replica movements or (2) leadership movements.
   * This check ensures lack of ongoing movements started by external agents -- i.e. not started by this Executor.
   */
  private void sanityCheckOngoingMovement() throws OngoingExecutionException {
    boolean hasOngoingPartitionReassignments;
    try {
      hasOngoingPartitionReassignments = hasOngoingPartitionReassignments();
    } catch (TimeoutException | InterruptedException | ExecutionException e) {
      // This may indicate transient (e.g. network) issues.
      throw new IllegalStateException("Failed to retrieve if there are already ongoing partition reassignments.", e);
    }
    // Note that in case there is an ongoing partition reassignment, we do not unpause metric sampling.
    if (hasOngoingPartitionReassignments) {
      throw new OngoingExecutionException("There are ongoing inter-broker partition movements.");
    } else {
      boolean hasOngoingIntraBrokerReplicaMovement;
      try {
        hasOngoingIntraBrokerReplicaMovement =
            hasOngoingIntraBrokerReplicaMovement(_metadataClient.cluster().nodes().stream().mapToInt(Node::id).boxed()
                                                                .collect(Collectors.toSet()), _adminClient, _config);
      } catch (TimeoutException | InterruptedException | ExecutionException e) {
        // This may indicate transient (e.g. network) issues.
        throw new IllegalStateException("Failed to retrieve if there are already ongoing intra-broker replica reassignments.", e);
      }
      if (hasOngoingIntraBrokerReplicaMovement) {
        throw new OngoingExecutionException("There are ongoing intra-broker partition movements.");
      } else if (hasOngoingLeaderElection()) {
        throw new OngoingExecutionException("There are ongoing leadership movements.");
      }
    }
  }

  private void processExecuteProposalsFailure() {
    _executionTaskManager.clear();
    _uuid = null;
    _reasonSupplier = null;
    _executorState = ExecutorState.noTaskInProgress(recentlyDemotedBrokers(), recentlyRemovedBrokers());
  }

  /**
   * Notify the executor on starting to generate proposals for execution with the given uuid and reason supplier.
   * The executor must be in {@link ExecutorState.State#NO_TASK_IN_PROGRESS} state for this operation to succeed.
   *
   * @param uuid UUID of the current execution.
   * @param reasonSupplier Reason supplier for the execution.
   * @param isTriggeredByUserRequest Whether the execution is triggered by a user request.
   */
  public synchronized void setGeneratingProposalsForExecution(String uuid, Supplier<String> reasonSupplier, boolean isTriggeredByUserRequest)
      throws OngoingExecutionException {
    ExecutorState.State currentExecutorState = _executorState.state();
    if (currentExecutorState != NO_TASK_IN_PROGRESS) {
      throw new OngoingExecutionException(String.format("Cannot generate proposals while the executor is in %s state.", currentExecutorState));
    }
    if (uuid == null) {
      throw new IllegalArgumentException("UUID of the execution cannot be null.");
    }
    if (reasonSupplier == null) {
      throw new IllegalArgumentException("Reason supplier cannot be null.");
    }

    _uuid = uuid;
    _reasonSupplier = reasonSupplier;
    _executorState = ExecutorState.generatingProposalsForExecution(_uuid, _reasonSupplier.get(), recentlyDemotedBrokers(),
                                                                   recentlyRemovedBrokers(), isTriggeredByUserRequest);
  }

  /**
   * Notify the executor on the failure to generate proposals for execution with the given uuid.
   * The executor may stuck in {@link ExecutorState.State#GENERATING_PROPOSALS_FOR_EXECUTION} state if this call is omitted.
   *
   * This is a no-op if called when
   * <ul>
   *   <li>Not generating proposals for execution: This is because all other execution failures after generating proposals
   *   for execution are already handled by the executor.</li>
   *   <li>Generating proposals with a different UUID: This indicates an error in the caller-side logic, and is not
   *   supposed to happen because at any given time proposals shall be generated for only one request -- for detecting
   *   such presumably benign misuse (if any), this case is logged as a warning.</li>
   * </ul>
   * @param uuid UUID of the failed proposal generation for execution.
   */
  public synchronized void failGeneratingProposalsForExecution(String uuid) {
    if (_executorState.state() == GENERATING_PROPOSALS_FOR_EXECUTION) {
      if (uuid != null && uuid.equals(_uuid)) {
        LOG.info("Failed to generate proposals for execution (UUID: {} reason: {}).", uuid, _reasonSupplier.get());
        _uuid = null;
        _reasonSupplier = null;
        _executorState = ExecutorState.noTaskInProgress(recentlyDemotedBrokers(), recentlyRemovedBrokers());
      } else {
        LOG.warn("UUID mismatch in attempt to report failure to generate proposals (received: {} expected: {})", uuid, _uuid);
      }
    }
  }

  /**
   * Request the executor to stop any ongoing execution.
   *
   * @param forceExecutionStop Whether force execution to stop.
   */
  public synchronized void userTriggeredStopExecution(boolean forceExecutionStop) {
    if (stopExecution(forceExecutionStop)) {
      LOG.info("User requested to stop the ongoing proposal execution" + (forceExecutionStop ? " forcefully." : "."));
      _numExecutionStoppedByUser.incrementAndGet();
      _executionStoppedByUser.set(true);
    }
  }

  /**
   * Request the executor to stop any ongoing execution.
   *
   * @param forceExecutionStop Whether force execution to stop.
   * @return True if the flag to stop the execution is set after the call (i.e. was not set already), false otherwise.
   */
  private synchronized boolean stopExecution(boolean forceExecutionStop) {
    if ((forceExecutionStop && (_stopSignal.compareAndSet(NO_STOP_EXECUTION, FORCE_STOP_EXECUTION) ||
                                _stopSignal.compareAndSet(STOP_EXECUTION, FORCE_STOP_EXECUTION))) ||
        (!forceExecutionStop && _stopSignal.compareAndSet(NO_STOP_EXECUTION, STOP_EXECUTION)))  {
      _numExecutionStopped.incrementAndGet();
      _executionTaskManager.setStopRequested();
      return true;
    }
    return false;
  }

  /**
   * Shutdown the executor.
   */
  public synchronized void shutdown() {
    LOG.info("Shutting down executor.");
    if (_hasOngoingExecution) {
      LOG.warn("Shutdown executor may take long because execution is still in progress.");
    }
    _proposalExecutor.shutdown();

    try {
      _proposalExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      LOG.warn("Interrupted while waiting for anomaly detector to shutdown.");
    }
    _metadataClient.close();
    KafkaCruiseControlUtils.closeKafkaZkClientWithTimeout(_kafkaZkClient);
    KafkaCruiseControlUtils.closeAdminClientWithTimeout(_adminClient);
    _executionHistoryScannerExecutor.shutdownNow();
    _concurrencyAdjusterExecutor.shutdownNow();
    LOG.info("Executor shutdown completed.");
  }

  /**
   * Let executor know the intention regarding modifying the ongoing execution. Only one request at a given time is
   * allowed to modify the ongoing execution.
   *
   * @param modify True to indicate, false to cancel the intention to modify
   * @return True if the intention changes the state known by executor, false otherwise.
   */
  public boolean modifyOngoingExecution(boolean modify) {
    return _ongoingExecutionIsBeingModified.compareAndSet(!modify, modify);
  }

  /**
   * Whether there is an ongoing operation triggered by current Cruise Control deployment.
   *
   * @return True if there is an ongoing execution.
   */
  public boolean hasOngoingExecution() {
    return _hasOngoingExecution;
  }

  /**
   * Check whether there is an ongoing partition reassignment on Kafka cluster.
   *
   * Note that a {@code false} response does not guarantee lack of an ongoing execution because when there is an ongoing
   * execution inside Cruise Control, partition reassignment task batches are sent to Kafka periodically. So, there will
   * be intervals without partition reassignments.
   *
   * @return True if there is an ongoing partition reassignment on Kafka cluster.
   */
  public boolean hasOngoingPartitionReassignments() throws InterruptedException, ExecutionException, TimeoutException {
    return !ExecutionUtils.partitionsBeingReassigned(_adminClient).isEmpty();
  }

  /**
   * Check whether there is an ongoing leadership reassignment.
   * This method directly checks the existence of zNode in /admin/preferred_replica_election.
   *
   * @return True if there is an ongoing leadership reassignment.
   */
  public boolean hasOngoingLeaderElection() {
    return !ExecutorUtils.ongoingLeaderElection(_kafkaZkClient).isEmpty();
  }

  /**
   * This class is thread safe.
   *
   * Note that once the thread for {@link ProposalExecutionRunnable} is submitted for running, the variable
   * _executionTaskManager can only be written within this inner class, but not from the outer Executor class.
   */
  private class ProposalExecutionRunnable implements Runnable {
    private final LoadMonitor _loadMonitor;
    private final Set<Integer> _recentlyDemotedBrokers;
    private final Set<Integer> _recentlyRemovedBrokers;
    private final Long _replicationThrottle;
    private Throwable _executionException;
    private final boolean _isTriggeredByUserRequest;
    private long _lastSlowTaskReportingTimeMs;
    private static final boolean FORCE_PAUSE_SAMPLING = true;

    ProposalExecutionRunnable(LoadMonitor loadMonitor,
                              Collection<Integer> demotedBrokers,
                              Collection<Integer> removedBrokers,
                              Long replicationThrottle,
                              boolean isTriggeredByUserRequest) {
      _loadMonitor = loadMonitor;
      _executionException = null;
      if (isTriggeredByUserRequest && _userTaskManager == null) {
        processExecuteProposalsFailure();
        _hasOngoingExecution = false;
        _stopSignal.set(NO_STOP_EXECUTION);
        _executionStoppedByUser.set(false);
        LOG.error("Failed to initialize proposal execution.");
        throw new IllegalStateException("User task manager cannot be null.");
      }
      if (demotedBrokers != null) {
        // Add/overwrite the latest demotion time of (non-permanent) demoted brokers (if any).
        demotedBrokers.forEach(id -> {
          Long demoteStartTime = _latestDemoteStartTimeMsByBrokerId.get(id);
          if (demoteStartTime == null || demoteStartTime != PERMANENT_TIMESTAMP) {
            _latestDemoteStartTimeMsByBrokerId.put(id, _time.milliseconds());
          }
        });
      }
      if (removedBrokers != null) {
        // Add/overwrite the latest removal time of (non-permanent) removed brokers (if any).
        removedBrokers.forEach(id -> {
          Long removeStartTime = _latestRemoveStartTimeMsByBrokerId.get(id);
          if (removeStartTime == null || removeStartTime != PERMANENT_TIMESTAMP) {
            _latestRemoveStartTimeMsByBrokerId.put(id, _time.milliseconds());
          }
        });
      }
      _recentlyDemotedBrokers = recentlyDemotedBrokers();
      _recentlyRemovedBrokers = recentlyRemovedBrokers();
      _replicationThrottle = replicationThrottle;
      _isTriggeredByUserRequest = isTriggeredByUserRequest;
      _lastSlowTaskReportingTimeMs = -1L;
    }

    public void run() {
      LOG.info("Starting executing balancing proposals.");
      UserTaskManager.UserTaskInfo userTaskInfo = initExecution();
      execute(userTaskInfo);
      LOG.info("Execution finished.");
    }

    private UserTaskManager.UserTaskInfo initExecution() {
      UserTaskManager.UserTaskInfo userTaskInfo = null;
      // If the task is triggered from a user request, mark the task to be in-execution state in user task manager and
      // retrieve the associated user task information.
      if (_isTriggeredByUserRequest) {
        userTaskInfo = _userTaskManager.markTaskExecutionBegan(_uuid);
      }
      String reason = _reasonSupplier.get();
      _executorState = ExecutorState.executionStarting(_uuid, reason, _recentlyDemotedBrokers, _recentlyRemovedBrokers, _isTriggeredByUserRequest);
      OPERATION_LOG.info("Task [{}] execution starts. The reason of execution is {}.", _uuid, reason);
      _ongoingExecutionIsBeingModified.set(false);

      return userTaskInfo;
    }

    /**
     * Unless the metric sampling is already in the desired mode:
     * <ul>
     *   <li>Pause the metric sampling</li>
     *   <li>Set the metric sampling mode to retrieve desired samples</li>
     *   <li>Resume the metric sampling</li>
     * </ul>
     */
    private void adjustSamplingModeBeforeExecution() throws InterruptedException {
      // Pause the partition metric sampling to avoid the loss of accuracy during execution.
      while (_loadMonitor.samplingMode() != BROKER_METRICS_ONLY) {
        try {
          String reasonForPause = String.format("Paused-By-Cruise-Control-Before-Starting-Execution (Date: %s)", currentUtcDate());
          _loadMonitor.pauseMetricSampling(reasonForPause, FORCE_PAUSE_SAMPLING);
          // Set the metric sampling mode to retrieve broker samples only.
          _loadMonitor.setSamplingMode(BROKER_METRICS_ONLY);
          break;
        } catch (IllegalStateException e) {
          Thread.sleep(executionProgressCheckIntervalMs());
          LOG.debug("Waiting for the load monitor to be ready to adjust sampling mode.", e);
        }
      }
      // Resume the metric sampling.
      _loadMonitor.resumeMetricSampling(String.format("Resumed-By-Cruise-Control-Before-Starting-Execution (Date: %s)", currentUtcDate()));
    }

    /**
     * Start the actual execution of the proposals in order:
     * <ol>
     *   <li>Inter-broker move replicas.</li>
     *   <li>Intra-broker move replicas.</li>
     *   <li>Transfer leadership.</li>
     * </ol>
     *
     * @param userTaskInfo The task information if the task is triggered from a user request, {@code null} otherwise.
     */
    private void execute(UserTaskManager.UserTaskInfo userTaskInfo) {
      try {
        // Ensure that sampling mode is adjusted properly to avoid the loss of partition metric accuracy
        // and enable the collection of broker metric samples during an ongoing execution.
        adjustSamplingModeBeforeExecution();

        // 1. Inter-broker move replicas if possible.
        if (_executorState.state() == STARTING_EXECUTION) {
          _executorState = ExecutorState.operationInProgress(INTER_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS,
                                                             _executionTaskManager.getExecutionTasksSummary(
                                                                 Collections.singleton(INTER_BROKER_REPLICA_ACTION)),
                                                             _executionTaskManager.interBrokerPartitionMovementConcurrency(),
                                                             _executionTaskManager.intraBrokerPartitionMovementConcurrency(),
                                                             _executionTaskManager.leadershipMovementConcurrency(),
                                                             _uuid,
                                                             _reasonSupplier.get(),
                                                             _recentlyDemotedBrokers,
                                                             _recentlyRemovedBrokers,
                                                             _isTriggeredByUserRequest);
          interBrokerMoveReplicas();
          updateOngoingExecutionState();
        }

        // 2. Intra-broker move replicas if possible.
        if (_executorState.state() == INTER_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS) {
          _executorState = ExecutorState.operationInProgress(INTRA_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS,
                                                             _executionTaskManager.getExecutionTasksSummary(
                                                                 Collections.singleton(INTRA_BROKER_REPLICA_ACTION)),
                                                             _executionTaskManager.interBrokerPartitionMovementConcurrency(),
                                                             _executionTaskManager.intraBrokerPartitionMovementConcurrency(),
                                                             _executionTaskManager.leadershipMovementConcurrency(),
                                                             _uuid,
                                                             _reasonSupplier.get(),
                                                             _recentlyDemotedBrokers,
                                                             _recentlyRemovedBrokers,
                                                             _isTriggeredByUserRequest);
          intraBrokerMoveReplicas();
          updateOngoingExecutionState();
        }

        // 3. Transfer leadership if possible.
        if (_executorState.state() == INTRA_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS) {
          _executorState = ExecutorState.operationInProgress(LEADER_MOVEMENT_TASK_IN_PROGRESS,
                                                             _executionTaskManager.getExecutionTasksSummary(
                                                                 Collections.singleton(LEADER_ACTION)),
                                                             _executionTaskManager.interBrokerPartitionMovementConcurrency(),
                                                             _executionTaskManager.intraBrokerPartitionMovementConcurrency(),
                                                             _executionTaskManager.leadershipMovementConcurrency(),
                                                             _uuid,
                                                             _reasonSupplier.get(),
                                                             _recentlyDemotedBrokers,
                                                             _recentlyRemovedBrokers,
                                                             _isTriggeredByUserRequest);
          moveLeaderships();
          updateOngoingExecutionState();
        }

        // 4. Delete tasks from Zookeeper if needed.
        if (_executorState.state() == STOPPING_EXECUTION && _stopSignal.get() == FORCE_STOP_EXECUTION) {
          ExecutionUtils.deleteZNodesToStopExecution(_kafkaZkClient);
        }
      } catch (Throwable t) {
        LOG.error("Executor got exception during execution", t);
        _executionException = t;
      } finally {
        notifyFinishedTask(userTaskInfo);
        // Clear completed execution.
        clearCompletedExecution();
      }
    }

    private void notifyFinishedTask(UserTaskManager.UserTaskInfo userTaskInfo) {
      // If the finished task was triggered by a user request, update task status in user task manager; if task is triggered
      // by an anomaly self-healing, update the task status in anomaly detector.
      if (userTaskInfo != null) {
        _userTaskManager.markTaskExecutionFinished(_uuid, _executorState.state() == STOPPING_EXECUTION || _executionException != null);
      } else {
        _anomalyDetector.markSelfHealingFinished(_uuid);
      }

      String prefix = String.format("Task [%s] %s execution is ", _uuid,
                                    userTaskInfo != null ? ("user" + userTaskInfo.requestUrl()) : "self-healing");

      if (_executorState.state() == STOPPING_EXECUTION) {
        notifyExecutionFinished(String.format("%sstopped by %s.", prefix, _executionStoppedByUser.get() ? "user" : "Cruise Control"),
                                true);
      } else if (_executionException != null) {
        notifyExecutionFinished(String.format("%sinterrupted with exception %s.", prefix, _executionException.getMessage()),
                                true);
      } else {
        notifyExecutionFinished(String.format("%sfinished.", prefix), false);
      }
    }

    private void notifyExecutionFinished(String message, boolean isWarning) {
      if (isWarning) {
        _executorNotifier.sendAlert(message);
        OPERATION_LOG.warn(message);
      } else {
        _executorNotifier.sendNotification(message);
        OPERATION_LOG.info(message);
      }
    }

    private void clearCompletedExecution() {
      _executionTaskManager.clear();
      _uuid = null;
      _reasonSupplier = null;
      _executorState = ExecutorState.noTaskInProgress(_recentlyDemotedBrokers, _recentlyRemovedBrokers);
      _hasOngoingExecution = false;
      _stopSignal.set(NO_STOP_EXECUTION);
      _executionStoppedByUser.set(false);
      // Ensure that sampling mode is adjusted properly to continue collecting partition metrics after execution.
      _loadMonitor.setSamplingMode(ALL);
    }

    private void updateOngoingExecutionState() {
      if (_stopSignal.get() == NO_STOP_EXECUTION) {
        switch (_executorState.state()) {
          case LEADER_MOVEMENT_TASK_IN_PROGRESS:
            _executorState = ExecutorState.operationInProgress(LEADER_MOVEMENT_TASK_IN_PROGRESS,
                                                               _executionTaskManager.getExecutionTasksSummary(
                                                                   Collections.singleton(LEADER_ACTION)),
                                                               _executionTaskManager.interBrokerPartitionMovementConcurrency(),
                                                               _executionTaskManager.intraBrokerPartitionMovementConcurrency(),
                                                               _executionTaskManager.leadershipMovementConcurrency(),
                                                               _uuid,
                                                               _reasonSupplier.get(),
                                                               _recentlyDemotedBrokers,
                                                               _recentlyRemovedBrokers,
                                                               _isTriggeredByUserRequest);
            break;
          case INTER_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS:
            _executorState = ExecutorState.operationInProgress(INTER_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS,
                                                               _executionTaskManager.getExecutionTasksSummary(
                                                                   Collections.singleton(INTER_BROKER_REPLICA_ACTION)),
                                                               _executionTaskManager.interBrokerPartitionMovementConcurrency(),
                                                               _executionTaskManager.intraBrokerPartitionMovementConcurrency(),
                                                               _executionTaskManager.leadershipMovementConcurrency(),
                                                               _uuid,
                                                               _reasonSupplier.get(),
                                                               _recentlyDemotedBrokers,
                                                               _recentlyRemovedBrokers,
                                                               _isTriggeredByUserRequest);
            break;
          case INTRA_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS:
            _executorState = ExecutorState.operationInProgress(INTRA_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS,
                                                               _executionTaskManager.getExecutionTasksSummary(
                                                                   Collections.singleton(INTRA_BROKER_REPLICA_ACTION)),
                                                               _executionTaskManager.interBrokerPartitionMovementConcurrency(),
                                                               _executionTaskManager.intraBrokerPartitionMovementConcurrency(),
                                                               _executionTaskManager.leadershipMovementConcurrency(),
                                                               _uuid,
                                                               _reasonSupplier.get(),
                                                               _recentlyDemotedBrokers,
                                                               _recentlyRemovedBrokers,
                                                               _isTriggeredByUserRequest);
            break;
          default:
            throw new IllegalStateException("Unexpected ongoing execution state " + _executorState.state());
        }
      } else {
        Set<ExecutionTask.TaskType> taskTypesToGetFullList = new HashSet<>(ExecutionTask.TaskType.cachedValues());
        _executorState = ExecutorState.operationInProgress(STOPPING_EXECUTION,
                                                           _executionTaskManager.getExecutionTasksSummary(taskTypesToGetFullList),
                                                           _executionTaskManager.interBrokerPartitionMovementConcurrency(),
                                                           _executionTaskManager.intraBrokerPartitionMovementConcurrency(),
                                                           _executionTaskManager.leadershipMovementConcurrency(),
                                                           _uuid,
                                                           _reasonSupplier.get(),
                                                           _recentlyDemotedBrokers,
                                                           _recentlyRemovedBrokers,
                                                           _isTriggeredByUserRequest);
      }
    }

    private void interBrokerMoveReplicas() throws InterruptedException, ExecutionException, TimeoutException {
      ReplicationThrottleHelper throttleHelper = new ReplicationThrottleHelper(_kafkaZkClient, _replicationThrottle);
      int numTotalPartitionMovements = _executionTaskManager.numRemainingInterBrokerPartitionMovements();
      long totalDataToMoveInMB = _executionTaskManager.remainingInterBrokerDataToMoveInMB();
      LOG.info("Starting {} inter-broker partition movements.", numTotalPartitionMovements);

      int partitionsToMove = numTotalPartitionMovements;
      // Exhaust all the pending partition movements.
      while ((partitionsToMove > 0 || !inExecutionTasks().isEmpty()) && _stopSignal.get() == NO_STOP_EXECUTION) {
        // Get tasks to execute.
        List<ExecutionTask> tasksToExecute = _executionTaskManager.getInterBrokerReplicaMovementTasks();
        LOG.info("Executor will execute {} task(s)", tasksToExecute.size());

        AlterPartitionReassignmentsResult result = null;
        if (!tasksToExecute.isEmpty()) {
          throttleHelper.setThrottles(tasksToExecute.stream().map(ExecutionTask::proposal).collect(Collectors.toList()));
          // Execute the tasks.
          _executionTaskManager.markTasksInProgress(tasksToExecute);
          result = ExecutionUtils.submitReplicaReassignmentTasks(_adminClient, tasksToExecute);
        }
        // Wait indefinitely for partition movements to finish.
        List<ExecutionTask> completedTasks = waitForExecutionTaskToFinish(result);
        partitionsToMove = _executionTaskManager.numRemainingInterBrokerPartitionMovements();
        int numFinishedPartitionMovements = _executionTaskManager.numFinishedInterBrokerPartitionMovements();
        long finishedDataMovementInMB = _executionTaskManager.finishedInterBrokerDataMovementInMB();
        LOG.info("{}/{} ({}%) inter-broker partition movements completed. {}/{} ({}%) MB have been moved.",
                 numFinishedPartitionMovements, numTotalPartitionMovements,
                 String.format("%.2f", numFinishedPartitionMovements * UNIT_INTERVAL_TO_PERCENTAGE / numTotalPartitionMovements),
                 finishedDataMovementInMB, totalDataToMoveInMB,
                 totalDataToMoveInMB == 0 ? 100 : String.format("%.2f", finishedDataMovementInMB * UNIT_INTERVAL_TO_PERCENTAGE
                                                                        / totalDataToMoveInMB));
        throttleHelper.clearThrottles(completedTasks, tasksToExecute.stream().filter(t -> t.state() == IN_PROGRESS).collect(Collectors.toList()));
      }
      // After the partition movement finishes, wait for the controller to clean the reassignment zkPath. This also
      // ensures a clean stop when the execution is stopped in the middle.
      Set<ExecutionTask> inExecutionTasks = inExecutionTasks();
      while (!inExecutionTasks.isEmpty()) {
        LOG.info("Waiting for {} tasks moving {} MB to finish.", inExecutionTasks.size(),
                 _executionTaskManager.inExecutionInterBrokerDataToMoveInMB());
        List<ExecutionTask> completedRemainingTasks = waitForExecutionTaskToFinish(null);
        inExecutionTasks = inExecutionTasks();
        throttleHelper.clearThrottles(completedRemainingTasks, new ArrayList<>(inExecutionTasks));
      }
      if (_stopSignal.get() == NO_STOP_EXECUTION) {
        LOG.info("Inter-broker partition movements finished.");
      } else {
        ExecutionTasksSummary executionTasksSummary = _executionTaskManager.getExecutionTasksSummary(Collections.emptySet());
        Map<ExecutionTask.State, Integer> partitionMovementTasksByState = executionTasksSummary.taskStat().get(INTER_BROKER_REPLICA_ACTION);
        LOG.info("Inter-broker partition movements stopped. For inter-broker partition movements {} tasks cancelled, {} tasks in-progress, "
                 + "{} tasks aborting, {} tasks aborted, {} tasks dead, {} tasks completed, {} remaining data to move; for intra-broker "
                 + "partition movement {} tasks cancelled; for leadership movements {} task cancelled.",
                 partitionMovementTasksByState.get(PENDING),
                 partitionMovementTasksByState.get(IN_PROGRESS),
                 partitionMovementTasksByState.get(ABORTING),
                 partitionMovementTasksByState.get(ABORTED),
                 partitionMovementTasksByState.get(DEAD),
                 partitionMovementTasksByState.get(COMPLETED),
                 executionTasksSummary.remainingInterBrokerDataToMoveInMB(),
                 executionTasksSummary.taskStat().get(INTRA_BROKER_REPLICA_ACTION).get(PENDING),
                 executionTasksSummary.taskStat().get(LEADER_ACTION).get(PENDING));
      }
    }

    private void intraBrokerMoveReplicas() throws InterruptedException, ExecutionException, TimeoutException {
      int numTotalPartitionMovements = _executionTaskManager.numRemainingIntraBrokerPartitionMovements();
      long totalDataToMoveInMB = _executionTaskManager.remainingIntraBrokerDataToMoveInMB();
      LOG.info("Starting {} intra-broker partition movements.", numTotalPartitionMovements);

      int partitionsToMove = numTotalPartitionMovements;
      // Exhaust all the pending partition movements.
      while ((partitionsToMove > 0 || !inExecutionTasks().isEmpty()) && _stopSignal.get() == NO_STOP_EXECUTION) {
        // Get tasks to execute.
        List<ExecutionTask> tasksToExecute = _executionTaskManager.getIntraBrokerReplicaMovementTasks();
        LOG.info("Executor will execute {} task(s)", tasksToExecute.size());

        if (!tasksToExecute.isEmpty()) {
          // Execute the tasks.
          _executionTaskManager.markTasksInProgress(tasksToExecute);
          executeIntraBrokerReplicaMovements(tasksToExecute, _adminClient, _executionTaskManager, _config);
        }
        // Wait indefinitely for partition movements to finish.
        waitForExecutionTaskToFinish(null);
        partitionsToMove = _executionTaskManager.numRemainingIntraBrokerPartitionMovements();
        int numFinishedPartitionMovements = _executionTaskManager.numFinishedIntraBrokerPartitionMovements();
        long finishedDataToMoveInMB = _executionTaskManager.finishedIntraBrokerDataToMoveInMB();
        LOG.info("{}/{} ({}%) intra-broker partition movements completed. {}/{} ({}%) MB have been moved.",
                 numFinishedPartitionMovements, numTotalPartitionMovements,
                 String.format("%.2f", numFinishedPartitionMovements * UNIT_INTERVAL_TO_PERCENTAGE / numTotalPartitionMovements),
                 finishedDataToMoveInMB, totalDataToMoveInMB,
                 totalDataToMoveInMB == 0 ? 100 : String.format("%.2f", finishedDataToMoveInMB * UNIT_INTERVAL_TO_PERCENTAGE
                                                                        / totalDataToMoveInMB));
      }
      Set<ExecutionTask> inExecutionTasks = inExecutionTasks();
      while (!inExecutionTasks.isEmpty()) {
        LOG.info("Waiting for {} tasks moving {} MB to finish", inExecutionTasks.size(),
                 _executionTaskManager.inExecutionIntraBrokerDataMovementInMB());
        waitForExecutionTaskToFinish(null);
        inExecutionTasks = inExecutionTasks();
      }
      if (inExecutionTasks().isEmpty()) {
        LOG.info("Intra-broker partition movements finished.");
      } else if (_stopSignal.get() != NO_STOP_EXECUTION) {
        ExecutionTasksSummary executionTasksSummary = _executionTaskManager.getExecutionTasksSummary(Collections.emptySet());
        Map<ExecutionTask.State, Integer> partitionMovementTasksByState = executionTasksSummary.taskStat().get(INTRA_BROKER_REPLICA_ACTION);
        LOG.info("Intra-broker partition movements stopped. For intra-broker partition movements {} tasks cancelled, {} tasks in-progress, "
                 + "{} tasks aborting, {} tasks aborted, {} tasks dead, {} tasks completed, {} remaining data to move; for leadership "
                 + "movements {} task cancelled.",
                 partitionMovementTasksByState.get(PENDING),
                 partitionMovementTasksByState.get(IN_PROGRESS),
                 partitionMovementTasksByState.get(ABORTING),
                 partitionMovementTasksByState.get(ABORTED),
                 partitionMovementTasksByState.get(DEAD),
                 partitionMovementTasksByState.get(COMPLETED),
                 executionTasksSummary.remainingIntraBrokerDataToMoveInMB(),
                 executionTasksSummary.taskStat().get(LEADER_ACTION).get(PENDING));
      }
    }

    private void moveLeaderships() throws InterruptedException, ExecutionException, TimeoutException {
      int numTotalLeadershipMovements = _executionTaskManager.numRemainingLeadershipMovements();
      LOG.info("Starting {} leadership movements.", numTotalLeadershipMovements);
      int numFinishedLeadershipMovements = 0;
      while (_executionTaskManager.numRemainingLeadershipMovements() != 0 && _stopSignal.get() == NO_STOP_EXECUTION) {
        updateOngoingExecutionState();
        numFinishedLeadershipMovements += moveLeadershipInBatch();
        LOG.info("{}/{} ({}%) leadership movements completed.", numFinishedLeadershipMovements,
                 numTotalLeadershipMovements, numFinishedLeadershipMovements * 100 / numTotalLeadershipMovements);
      }
      if (inExecutionTasks().isEmpty()) {
        LOG.info("Leadership movements finished.");
      } else if (_stopSignal.get() != NO_STOP_EXECUTION) {
        Map<ExecutionTask.State, Integer> leadershipMovementTasksByState =
            _executionTaskManager.getExecutionTasksSummary(Collections.emptySet()).taskStat().get(LEADER_ACTION);
        LOG.info("Leadership movements stopped. {} tasks cancelled, {} tasks in-progress, {} tasks aborting, {} tasks aborted, "
                 + "{} tasks dead, {} tasks completed.",
                 leadershipMovementTasksByState.get(PENDING),
                 leadershipMovementTasksByState.get(IN_PROGRESS),
                 leadershipMovementTasksByState.get(ABORTING),
                 leadershipMovementTasksByState.get(ABORTED),
                 leadershipMovementTasksByState.get(DEAD),
                 leadershipMovementTasksByState.get(COMPLETED));
      }
    }

    private int moveLeadershipInBatch() throws InterruptedException, ExecutionException, TimeoutException {
      List<ExecutionTask> leadershipMovementTasks = _executionTaskManager.getLeadershipMovementTasks();
      int numLeadershipToMove = leadershipMovementTasks.size();
      LOG.debug("Executing {} leadership movements in a batch.", numLeadershipToMove);
      // Execute the leadership movements.
      if (!leadershipMovementTasks.isEmpty() && _stopSignal.get() == NO_STOP_EXECUTION) {
        // Run preferred leader election unless there is already an ongoing leadership movement.
        while (hasOngoingLeaderElection()) {
          try {
            LOG.error("Waiting for Kafka Controller to delete /admin/preferred_replica_election zNode. Are other admin "
                      + "tools triggering a PLE?");
            Thread.sleep(executionProgressCheckIntervalMs());
          } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting for Kafka Controller to delete /admin/preferred_replica_election zNode.");
          }
        }
        // Mark leadership movements in progress.
        _executionTaskManager.markTasksInProgress(leadershipMovementTasks);

        ExecutorUtils.executePreferredLeaderElection(_kafkaZkClient, leadershipMovementTasks);
        LOG.trace("Waiting for leadership movement batch to finish.");
        while (!inExecutionTasks().isEmpty() && _stopSignal.get() == NO_STOP_EXECUTION) {
          waitForExecutionTaskToFinish(null);
        }
      }
      return numLeadershipToMove;
    }

    /**
     * Periodically checks the metadata to see if (1) partition or (2) leadership reassignment has finished or not.
     * @param result the result of a request to alter partition reassignments, or {@code null} to skip checking if
     *               inter-broker replica action has already failed due to dead new replica. Ignored for other task types.
     * @return Finished tasks.
     */
    private List<ExecutionTask> waitForExecutionTaskToFinish(AlterPartitionReassignmentsResult result)
        throws InterruptedException, ExecutionException, TimeoutException {
      List<ExecutionTask> finishedTasks = new ArrayList<>();
      Set<Long> stoppedTaskIds = new HashSet<>();
      Set<Long> deletedTaskIds = new HashSet<>();
      Set<Long> deadTaskIds = new HashSet<>();
      do {
        // If there is no finished tasks, we need to check if anything is blocked.
        maybeReexecuteTasks();
        try {
          Thread.sleep(executionProgressCheckIntervalMs());
        } catch (InterruptedException e) {
          // let it go
        }

        Cluster cluster = _metadataClient.refreshMetadata().cluster();
        Map<ExecutionTask, ReplicaLogDirInfo> logDirInfoByTask = getLogdirInfoForExecutionTask(
            _executionTaskManager.inExecutionTasks(Collections.singleton(INTRA_BROKER_REPLICA_ACTION)),
            _adminClient, _config);

        if (LOG.isDebugEnabled()) {
          LOG.debug("Tasks in execution: {}", inExecutionTasks());
        }
        List<ExecutionTask> deadInterBrokerReplicaTasks = new ArrayList<>();
        List<ExecutionTask> gracefullyStoppedInterBrokerReplicaTasks = new ArrayList<>();
        List<ExecutionTask> slowTasksToReport = new ArrayList<>();
        boolean shouldReportSlowTasks = _time.milliseconds() - _lastSlowTaskReportingTimeMs > SLOW_TASK_ALERTING_BACKOFF_TIME_MS;
        for (ExecutionTask task : inExecutionTasks()) {
          TopicPartition tp = task.proposal().topicPartition();
          if (_stopSignal.get() == FORCE_STOP_EXECUTION
              || (_stopSignal.get() == STOP_EXECUTION && task.type() == INTER_BROKER_REPLICA_ACTION)) {
            // If the execution is (1) force-stopped or (2) gracefully-stopped during an ongoing inter-broker replica reassignment,
            // then the executor will not just silently wait for the completion of the current in-progress tasks. For the former
            // case, the executor will mark all in progress tasks as dead, cleanup the relevant reassignment zNodes, and then bounce
            // the controller (see ExecutionUtils#deleteZNodesToStopExecution). For the latter case, the executor will mark in
            // progress tasks as dead, and rollback the ongoing reassignment of gracefully stopped inter-broker replica reassignment.
            LOG.debug("Task {} is marked as dead to {}.", task,
                      _stopSignal.get() == FORCE_STOP_EXECUTION ? "force-stop the execution with a controller bounce"
                                                                : "gracefully-stop the execution with a rollback");
            finishedTasks.add(task);
            stoppedTaskIds.add(task.executionId());
            _executionTaskManager.markTaskDead(task);
            if (_stopSignal.get() == STOP_EXECUTION) {
              gracefullyStoppedInterBrokerReplicaTasks.add(task);
            }
          } else if (cluster.partition(tp) == null) {
            // Handle topic deletion during the execution.
            LOG.debug("Task {} is marked as finished because the topic has been deleted", task);
            finishedTasks.add(task);
            deletedTaskIds.add(task.executionId());
            _executionTaskManager.markTaskAborting(task);
            _executionTaskManager.markTaskDone(task);
          } else if (ExecutionUtils.isTaskDone(cluster, logDirInfoByTask, task)) {
            // Check to see if the task is done.
            finishedTasks.add(task);
            _executionTaskManager.markTaskDone(task);
          } else {
            if (shouldReportSlowTasks) {
              task.maybeReportExecutionTooSlow(_time.milliseconds(), slowTasksToReport);
            }
            if (maybeMarkTaskAsDead(cluster, logDirInfoByTask, task, result)) {
              deadTaskIds.add(task.executionId());
              // Only add the dead tasks to execute if it is an inter-broker replica action.
              if (task.type() == INTER_BROKER_REPLICA_ACTION) {
                deadInterBrokerReplicaTasks.add(task);
              }
              finishedTasks.add(task);
            }
          }
        }
        sendSlowExecutionAlert(slowTasksToReport);
        handleDeadInterBrokerReplicaTasks(deadInterBrokerReplicaTasks, gracefullyStoppedInterBrokerReplicaTasks);
        updateOngoingExecutionState();
      } while (!inExecutionTasks().isEmpty() && finishedTasks.isEmpty());

      LOG.info("Finished tasks: {}.{}{}{}", finishedTasks,
               stoppedTaskIds.isEmpty() ? "" : String.format(". [%sStopped: %s]", _stopSignal.get() == FORCE_STOP_EXECUTION
                                                                                  ? "Force-" : "", stoppedTaskIds),
               deletedTaskIds.isEmpty() ? "" : String.format(". [Deleted: %s]", deletedTaskIds),
               deadTaskIds.isEmpty() ? "" : String.format(". [Dead: %s]", deadTaskIds));

      return finishedTasks;
    }

    /**
     * Attempts to cancel/rollback the ongoing reassignment of dead / gracefully stopped inter-broker replica actions
     * and stops the execution if not already requested so by the user.
     *
     * If all dead tasks are due to stopped inter-broker replica tasks, it waits until the rollback is completed.
     * Otherwise, it will not wait for the actual rollback to complete to avoid being blocked on a potentially stuck
     * reassignment operation due to dead brokers in the cluster. If by the time the next execution is attempted, the
     * rollback is still in progress on Kafka server-side, the executor will detect the ongoing server-side execution
     * and will not start a new execution (see {@link #sanityCheckOngoingMovement}).
     *
     * @param deadInterBrokerReplicaTasks Inter-broker replica tasks that are expected to be marked as dead due to dead
     *                                    destination brokers.
     * @param gracefullyStoppedInterBrokerReplicaTasks Inter-broker replica tasks that are expected to be marked as dead
     *                                                due to being gracefully stopped by user.
     */
    private void handleDeadInterBrokerReplicaTasks(List<ExecutionTask> deadInterBrokerReplicaTasks,
                                                   List<ExecutionTask> gracefullyStoppedInterBrokerReplicaTasks)
        throws InterruptedException, ExecutionException, TimeoutException {
      List<ExecutionTask> tasksToCancel = new ArrayList<>(deadInterBrokerReplicaTasks);
      tasksToCancel.addAll(gracefullyStoppedInterBrokerReplicaTasks);

      if (!tasksToCancel.isEmpty()) {
        // Sanity check to ensure that all tasks are marked as dead.
        tasksToCancel.stream().filter(task -> task.state() != DEAD).forEach(task -> {
          throw new IllegalArgumentException(String.format("Unexpected state for task %s (expected: %s).", task, DEAD));
        });

        // Cancel/rollback reassignment of dead inter-broker replica tasks.
        ExecutionUtils.submitReplicaReassignmentTasks(_adminClient, tasksToCancel);
        if (_stopSignal.get() == NO_STOP_EXECUTION) {
          // If there are dead tasks, Cruise Control stops the execution.
          LOG.info("Stop the execution due to {} dead tasks: {}.", tasksToCancel.size(), tasksToCancel);
          stopExecution(false);
        }

        if (deadInterBrokerReplicaTasks.isEmpty()) {
          // All dead tasks are due to stopped inter-broker replica tasks. Wait until the tasks being cancelled are done.
          Set<TopicPartition> beingCancelled = tasksToCancel.stream().map(task -> task.proposal().topicPartition()).collect(Collectors.toSet());

          while (true) {
            Set<TopicPartition> intersection = new HashSet<>(ExecutionUtils.partitionsBeingReassigned(_adminClient));
            intersection.retainAll(beingCancelled);
            if (intersection.isEmpty()) {
              // All tasks have been rolled back.
              break;
            }
            try {
              LOG.info("Waiting for the rollback of ongoing inter-broker replica reassignments for {}.", intersection);
              Thread.sleep(executionProgressCheckIntervalMs());
            } catch (InterruptedException e) {
              // let it go
            }
          }
        }
      }
    }

    private void sendSlowExecutionAlert(List<ExecutionTask> slowTasksToReport) {
      if (!slowTasksToReport.isEmpty()) {
        StringBuilder sb = new StringBuilder();
        sb.append("Slow tasks are detected:\n");
        for (ExecutionTask task: slowTasksToReport) {
          sb.append(String.format("\tID: %s\tstart_time:%s\tdetail:%s%n", task.executionId(),
                                  toDateString(task.startTimeMs(), DATE_FORMAT, TIME_ZONE), task));
        }
        _executorNotifier.sendAlert(sb.toString());
        _lastSlowTaskReportingTimeMs = _time.milliseconds();
      }
    }

    /**
     * Mark the task as dead if if it satisfies any of the constraints specified for its type:
     *
     * <ul>
     *   <li>{@code LEADER_ACTION}: Target broker/disk hosting the prospective leader is down or leadership transition
     *   took longer than the configured limit.</li>
     *   <li>{@code INTER_BROKER_REPLICA_ACTION}: Destination broker is down.</li>
     *   <li>{@code INTRA_BROKER_REPLICA_ACTION}: Destination disk is down.</li>
     * </ul>
     *
     * @param cluster the kafka cluster
     * @param logdirInfoByTask  disk information for ongoing intra-broker replica movement tasks
     * @param task the task to check
     * @param result the result of a request to alter partition reassignments, or {@code null} to skip checking if
     *               inter-broker replica action has already failed due to dead new replica. Ignored for other task types.
     * @return {@code true} if the task is marked as dead, {@code false} otherwise.
     */
    private boolean maybeMarkTaskAsDead(Cluster cluster,
                                        Map<ExecutionTask, ReplicaLogDirInfo> logdirInfoByTask,
                                        ExecutionTask task,
                                        AlterPartitionReassignmentsResult result) {
      // Only check tasks with IN_PROGRESS or ABORTING state.
      if (task.state() == IN_PROGRESS || task.state() == ABORTING) {
        switch (task.type()) {
          case LEADER_ACTION:
            if (cluster.nodeById(task.proposal().newLeader().brokerId()) == null) {
              _executionTaskManager.markTaskDead(task);
              LOG.warn("Killing execution for task {} because the target leader is down.", task);
              return true;
            } else if (_time.milliseconds() > task.startTimeMs() + _leaderMovementTimeoutMs) {
              _executionTaskManager.markTaskDead(task);
              LOG.warn("Killing execution for task {} because it took longer than {} to finish.", task, _leaderMovementTimeoutMs);
              return true;
            }
            break;

          case INTER_BROKER_REPLICA_ACTION:
            for (ReplicaPlacementInfo broker : task.proposal().newReplicas()) {
              if (cluster.nodeById(broker.brokerId()) == null) {
                _executionTaskManager.markTaskDead(task);
                LOG.warn("Killing execution for task {} because the new replica {} is down.", task, broker);
                return true;
              }
              // Check this later to avoid unnecessary wait on future.
              boolean requestFailedDueToDeadBroker =
                  (result != null && verifyFutureError(result.values().get(task.proposal().topicPartition()),
                                                       Errors.INVALID_REPLICA_ASSIGNMENT.exception().getClass()));
              if (requestFailedDueToDeadBroker) {
                _executionTaskManager.markTaskDead(task);
                LOG.warn("Killing execution for task {} because the new replica {} was down.", task, broker);
                return true;
              }
            }
            break;

          case INTRA_BROKER_REPLICA_ACTION:
            if (!logdirInfoByTask.containsKey(task)) {
              _executionTaskManager.markTaskDead(task);
              LOG.warn("Killing execution for task {} because the destination disk is down.", task);
              return true;
            }
            break;

          default:
            throw new IllegalStateException("Unknown task type " + task.type());
        }
      }
      return false;
    }

    private boolean verifyFutureError(Future<?> future, Class<? extends Throwable> exceptionClass) {
      if (future == null) {
        return false;
      }
      try {
        future.get(EXECUTION_TASK_FUTURE_ERROR_VERIFICATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (ExecutionException ee) {
        return exceptionClass == ee.getCause().getClass();
      } catch (TimeoutException | InterruptedException e) {
        // This may indicate transient (e.g. network) issues.
        LOG.warn("Failed to verify future error {}.", exceptionClass, e);
      }
      return false;
    }

    /**
     * TODO: Verify whether this function is needed on Kafka 2.4+.
     */
    private boolean maybeReexecuteInterBrokerReplicaActions() throws InterruptedException, ExecutionException, TimeoutException {
      List<ExecutionTask> interBrokerReplicaActionsToReexecute =
          new ArrayList<>(_executionTaskManager.inExecutionTasks(Collections.singleton(INTER_BROKER_REPLICA_ACTION)));
      boolean shouldReexecute = false;
      try {
        shouldReexecute = !ExecutionUtils.isSubset(ExecutionUtils.partitionsBeingReassigned(_adminClient), interBrokerReplicaActionsToReexecute);
      } catch (TimeoutException | InterruptedException | ExecutionException e) {
        // This may indicate transient (e.g. network) issues.
        LOG.warn("Failed to retrieve partitions being reassigned. Skipping reexecution check for inter-broker replica actions.", e);
      }
      if (shouldReexecute) {
        LOG.info("Reexecuting tasks {}", interBrokerReplicaActionsToReexecute);
        ExecutionUtils.submitReplicaReassignmentTasks(_adminClient, interBrokerReplicaActionsToReexecute);
      }
      return !interBrokerReplicaActionsToReexecute.isEmpty();
    }

    private boolean maybeReexecuteIntraBrokerReplicaActions() {
      List<ExecutionTask> intraBrokerReplicaActionsToReexecute =
          new ArrayList<>(_executionTaskManager.inExecutionTasks(Collections.singleton(INTRA_BROKER_REPLICA_ACTION)));
      getLogdirInfoForExecutionTask(intraBrokerReplicaActionsToReexecute, _adminClient, _config).forEach((k, v) -> {
        String targetLogdir = k.proposal().replicasToMoveBetweenDisksByBroker().get(k.brokerId()).logdir();
        // If task is completed or in-progress, do not reexecute the task.
        if (targetLogdir.equals(v.getCurrentReplicaLogDir()) || targetLogdir.equals(v.getFutureReplicaLogDir())) {
          intraBrokerReplicaActionsToReexecute.remove(k);
        }
      });
      if (!intraBrokerReplicaActionsToReexecute.isEmpty()) {
        LOG.info("Reexecuting tasks {}", intraBrokerReplicaActionsToReexecute);
        executeIntraBrokerReplicaMovements(intraBrokerReplicaActionsToReexecute, _adminClient, _executionTaskManager, _config);
      }
      return !intraBrokerReplicaActionsToReexecute.isEmpty();
    }

    /**
     * Due to the race condition between the controller and Cruise Control, some of the submitted tasks may be
     * deleted by controller without being executed. We will resubmit those tasks in that case.
     */
    private void maybeReexecuteTasks() throws InterruptedException, ExecutionException, TimeoutException {
      boolean hasOngoingInterBrokerReplicaActions = maybeReexecuteInterBrokerReplicaActions();
      boolean hasOngoingIntraBrokerReplicaActions = maybeReexecuteIntraBrokerReplicaActions();

      // Only reexecute leader actions if there is no replica actions running.
      if (!hasOngoingInterBrokerReplicaActions && !hasOngoingIntraBrokerReplicaActions && !hasOngoingLeaderElection()) {
        List<ExecutionTask> leaderActionsToReexecute = new ArrayList<>(_executionTaskManager.inExecutionTasks(Collections.singleton(LEADER_ACTION)));
        if (!leaderActionsToReexecute.isEmpty()) {
          LOG.info("Reexecuting tasks {}", leaderActionsToReexecute);
          ExecutorUtils.executePreferredLeaderElection(_kafkaZkClient, leaderActionsToReexecute);
        }
      }
    }
  }
}