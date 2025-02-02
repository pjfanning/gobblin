/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service.modules.orchestration;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.annotation.Alpha;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.instrumented.Instrumented;
import org.apache.gobblin.metrics.ContextAwareGauge;
import org.apache.gobblin.metrics.MetricContext;
import org.apache.gobblin.metrics.ServiceMetricNames;
import org.apache.gobblin.metrics.event.EventSubmitter;
import org.apache.gobblin.metrics.event.TimingEvent;
import org.apache.gobblin.runtime.api.DagActionStore;
import org.apache.gobblin.runtime.api.FlowSpec;
import org.apache.gobblin.runtime.api.JobSpec;
import org.apache.gobblin.runtime.api.Spec;
import org.apache.gobblin.runtime.api.SpecProducer;
import org.apache.gobblin.runtime.api.TopologySpec;
import org.apache.gobblin.runtime.spec_catalog.FlowCatalog;
import org.apache.gobblin.service.ExecutionStatus;
import org.apache.gobblin.service.FlowId;
import org.apache.gobblin.service.ServiceConfigKeys;
import org.apache.gobblin.service.modules.flowgraph.Dag;
import org.apache.gobblin.service.modules.flowgraph.Dag.DagNode;
import org.apache.gobblin.service.modules.spec.JobExecutionPlan;
import org.apache.gobblin.service.monitoring.FlowStatusGenerator;
import org.apache.gobblin.service.monitoring.JobStatus;
import org.apache.gobblin.service.monitoring.JobStatusRetriever;
import org.apache.gobblin.service.monitoring.KillFlowEvent;
import org.apache.gobblin.service.monitoring.ResumeFlowEvent;
import org.apache.gobblin.util.ConfigUtils;
import org.apache.gobblin.util.reflection.GobblinConstructorUtils;

import static org.apache.gobblin.service.ExecutionStatus.*;


/**
 * This class implements a manager to manage the life cycle of a {@link Dag}. A {@link Dag} is submitted to the
 * {@link DagManager} by the {@link Orchestrator#orchestrate} method. On receiving a {@link Dag}, the
 * {@link DagManager} first persists the {@link Dag} to the {@link DagStateStore}, and then submits it to the specific
 * {@link DagManagerThread}'s {@link BlockingQueue} based on the flowExecutionId of the Flow.
 * This guarantees that each {@link Dag} received by the {@link DagManager} can be recovered in case of a leadership
 * change or service restart.
 *
 * The implementation of the {@link DagManager} is multi-threaded. Each {@link DagManagerThread} polls the
 * {@link BlockingQueue} for new Dag submissions at fixed intervals. It deques any newly submitted Dags and coordinates
 * the execution of individual jobs in the Dag. The coordination logic involves polling the {@link JobStatus}es of running
 * jobs. Upon completion of a job, it will either schedule the next job in the Dag (on SUCCESS) or mark the Dag as failed
 * (on FAILURE). Upon completion of a Dag execution, it will perform the required clean up actions.
 *
 * For deleteSpec/cancellation requests for a flow URI, {@link DagManager} finds out the flowExecutionId using
 * {@link JobStatusRetriever}, and forwards the request to the {@link DagManagerThread} which handled the addSpec request
 * for this flow. We need separate {@link BlockingQueue}s for each {@link DagManagerThread} because
 * cancellation needs the information which is stored only in the same {@link DagManagerThread}.
 *
 * The {@link DagManager} is active only in the leader mode. To ensure, each {@link Dag} managed by a {@link DagManager} is
 * checkpointed to a persistent location. On start up or leadership change,
 * the {@link DagManager} loads all the checkpointed {@link Dag}s and adds them to the {@link  BlockingQueue}.
 */
@Alpha
@Slf4j
@Singleton
public class DagManager extends AbstractIdleService {
  public static final String DEFAULT_FLOW_FAILURE_OPTION = FailureOption.FINISH_ALL_POSSIBLE.name();

  public static final String DAG_MANAGER_PREFIX = "gobblin.service.dagManager.";

  private static final Integer DEFAULT_JOB_STATUS_POLLING_INTERVAL = 10;
  public static final Integer DEFAULT_NUM_THREADS = 3;
  private static final Integer TERMINATION_TIMEOUT = 30;
  public static final String NUM_THREADS_KEY = DAG_MANAGER_PREFIX + "numThreads";
  public static final String JOB_STATUS_POLLING_INTERVAL_KEY = DAG_MANAGER_PREFIX + "pollingInterval";
  private static final String DAG_STATESTORE_CLASS_KEY = DAG_MANAGER_PREFIX + "dagStateStoreClass";
  private static final String FAILED_DAG_STATESTORE_PREFIX = "failedDagStateStore";
  private static final String FAILED_DAG_RETENTION_TIME_UNIT = FAILED_DAG_STATESTORE_PREFIX + ".retention.timeUnit";
  private static final String DEFAULT_FAILED_DAG_RETENTION_TIME_UNIT = "DAYS";
  private static final String FAILED_DAG_RETENTION_TIME = FAILED_DAG_STATESTORE_PREFIX + ".retention.time";
  private static final long DEFAULT_FAILED_DAG_RETENTION_TIME = 7L;
  // Re-emit the final flow status if not detected within 5 minutes
  private static final long DAG_FLOW_STATUS_TOLERANCE_TIME_MILLIS = TimeUnit.MINUTES.toMillis(5);
  public static final String FAILED_DAG_POLLING_INTERVAL = FAILED_DAG_STATESTORE_PREFIX + ".retention.pollingIntervalMinutes";
  public static final Integer DEFAULT_FAILED_DAG_POLLING_INTERVAL = 60;
  public static final String DAG_MANAGER_HEARTBEAT = ServiceMetricNames.GOBBLIN_SERVICE_PREFIX_WITH_DELIMITER + "dagManager.heartbeat-%s";
  // Default job start SLA time if configured, measured in minutes. Default is 10 minutes
  private static final String JOB_START_SLA_TIME = DAG_MANAGER_PREFIX + ConfigurationKeys.GOBBLIN_JOB_START_SLA_TIME;
  private static final String JOB_START_SLA_UNITS = DAG_MANAGER_PREFIX + ConfigurationKeys.GOBBLIN_JOB_START_SLA_TIME_UNIT;
  private static final int MAX_HOUSEKEEPING_THREAD_DELAY = 180;
  private static final int INITIAL_HOUSEKEEPING_THREAD_DELAY = 2;
  /**
   * Action to be performed on a {@link Dag}, in case of a job failure. Currently, we allow 2 modes:
   * <ul>
   *   <li> FINISH_RUNNING, which allows currently running jobs to finish.</li>
   *   <li> FINISH_ALL_POSSIBLE, which allows every possible job in the Dag to finish, as long as all the dependencies
   *   of the job are successful.</li>
   * </ul>
   */
  public enum FailureOption {
    FINISH_RUNNING("FINISH_RUNNING"),
    CANCEL("CANCEL"),
    FINISH_ALL_POSSIBLE("FINISH_ALL_POSSIBLE");

    private final String failureOption;

    FailureOption(final String failureOption) {
      this.failureOption = failureOption;
    }

    @Override
    public String toString() {
      return this.failureOption;
    }
  }

  @Getter
  @EqualsAndHashCode
  public static class DagId {
    String flowGroup;
    String flowName;
    String flowExecutionId;
    public DagId(String flowGroup, String flowName, String flowExecutionId) {
      this.flowGroup = flowGroup;
      this.flowName = flowName;
      this.flowExecutionId = flowExecutionId;
    }

    @Override
    public String toString() {
      return Joiner.on("_").join(flowGroup, flowName, flowExecutionId);
    }

    DagActionStore.DagAction toDagAction(DagActionStore.FlowActionType actionType) {
      return new DagActionStore.DagAction(flowGroup, flowName, flowExecutionId, actionType);
    }
  }

  private final BlockingQueue<Dag<JobExecutionPlan>>[] runQueue;
  private final BlockingQueue<DagId>[] cancelQueue;
  private final BlockingQueue<DagId>[] resumeQueue;
  DagManagerThread[] dagManagerThreads;

  private final ScheduledExecutorService scheduledExecutorPool;
  private DagStateStore dagStateStore;
  private Map<URI, TopologySpec> topologySpecMap;
  @Getter
  private ScheduledExecutorService houseKeepingThreadPool;

  @Getter
  private final Integer numThreads;
  private final Integer pollingInterval;
  private final Integer retentionPollingInterval;
  protected final Long defaultJobStartSlaTimeMillis;
  @Getter
  private final JobStatusRetriever jobStatusRetriever;
  private final FlowStatusGenerator flowStatusGenerator;
  private final FlowCatalog flowCatalog;
  private final Config config;
  private final EventSubmitter eventSubmitter;
  private final long failedDagRetentionTime;
  private final DagManagerMetrics dagManagerMetrics;

  @Getter
  @Inject(optional=true)
  @VisibleForTesting
  protected Optional<DagActionStore> dagActionStore;

  private volatile boolean isActive = false;

  @Inject
  public DagManager(Config config, JobStatusRetriever jobStatusRetriever, FlowStatusGenerator flowStatusGenerator,
      FlowCatalog flowCatalog) {
    this.config = config;
    this.numThreads = ConfigUtils.getInt(config, NUM_THREADS_KEY, DEFAULT_NUM_THREADS);
    this.runQueue = (BlockingQueue<Dag<JobExecutionPlan>>[]) initializeDagQueue(this.numThreads);
    this.cancelQueue = (BlockingQueue<DagId>[]) initializeDagQueue(this.numThreads);
    this.resumeQueue = (BlockingQueue<DagId>[]) initializeDagQueue(this.numThreads);
    this.scheduledExecutorPool = Executors.newScheduledThreadPool(numThreads);
    this.pollingInterval = ConfigUtils.getInt(config, JOB_STATUS_POLLING_INTERVAL_KEY, DEFAULT_JOB_STATUS_POLLING_INTERVAL);
    this.retentionPollingInterval = ConfigUtils.getInt(config, FAILED_DAG_POLLING_INTERVAL, DEFAULT_FAILED_DAG_POLLING_INTERVAL);
    MetricContext metricContext = Instrumented.getMetricContext(ConfigUtils.configToState(ConfigFactory.empty()), getClass());
    this.eventSubmitter = new EventSubmitter.Builder(metricContext, "org.apache.gobblin.service").build();
    this.dagManagerMetrics = new DagManagerMetrics();
    TimeUnit jobStartTimeUnit = TimeUnit.valueOf(ConfigUtils.getString(config, JOB_START_SLA_UNITS, ConfigurationKeys.FALLBACK_GOBBLIN_JOB_START_SLA_TIME_UNIT));
    this.defaultJobStartSlaTimeMillis = jobStartTimeUnit.toMillis(ConfigUtils.getLong(config, JOB_START_SLA_TIME, ConfigurationKeys.FALLBACK_GOBBLIN_JOB_START_SLA_TIME));
    this.jobStatusRetriever = jobStatusRetriever;
    this.flowStatusGenerator = flowStatusGenerator;
    this.flowCatalog = flowCatalog;
    TimeUnit timeUnit = TimeUnit.valueOf(ConfigUtils.getString(config, FAILED_DAG_RETENTION_TIME_UNIT, DEFAULT_FAILED_DAG_RETENTION_TIME_UNIT));
    this.failedDagRetentionTime = timeUnit.toMillis(ConfigUtils.getLong(config, FAILED_DAG_RETENTION_TIME, DEFAULT_FAILED_DAG_RETENTION_TIME));
  }

  DagStateStore createDagStateStore(Config config, Map<URI, TopologySpec> topologySpecMap) {
    try {
      Class<?> dagStateStoreClass = Class.forName(ConfigUtils.getString(config, DAG_STATESTORE_CLASS_KEY, FSDagStateStore.class.getName()));
      return (DagStateStore) GobblinConstructorUtils.invokeLongestConstructor(dagStateStoreClass, config, topologySpecMap);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  // Initializes and returns an array of Queue of size numThreads
  static LinkedBlockingDeque<?>[] initializeDagQueue(int numThreads) {
    LinkedBlockingDeque<?>[] queue = new LinkedBlockingDeque[numThreads];

    for (int i=0; i< numThreads; i++) {
      queue[i] = new LinkedBlockingDeque<>();
    }
    return queue;
  }

  /** Do Nothing on service startup. Scheduling of {@link DagManagerThread}s and loading of any {@link Dag}s is done
   * during leadership change.
   */
  @Override
  protected void startUp() {
    //Do nothing.
  }

  /**
   * Method to submit a {@link Dag} to the {@link DagManager} and delete adhoc flowSpecs from the FlowCatalog after
   * persisting it in the other addDag method called. The DagManager's failure recovery method ensures the flow will be
   * executed in the event of downtime.
   * @param flowSpec
   * @param dag
   * @param persist
   * @param setStatus
   * @throws IOException
   */
  public synchronized void addDagAndRemoveAdhocFlowSpec(FlowSpec flowSpec, Dag<JobExecutionPlan> dag, boolean persist, boolean setStatus)
      throws IOException {
    addDag(dag, persist, setStatus);
    // Only the active dagManager should delete the flowSpec
    if (isActive) {
      deleteSpecFromCatalogIfAdhoc(flowSpec);
    }
  }

  /**
   * Method to submit a {@link Dag} to the {@link DagManager}. The {@link DagManager} optionally persists the
   * submitted dag to the {@link DagStateStore} and then adds the dag to a {@link BlockingQueue} to be picked up
   * by one of the {@link DagManagerThread}s.
   * @param dag {@link Dag} to be added
   * @param persist whether to persist the dag to the {@link DagStateStore}
   * @param setStatus if true, set all jobs in the dag to pending
   * Note this should only be called from the {@link Orchestrator} or {@link org.apache.gobblin.service.monitoring.DagActionStoreChangeMonitor}
   */
  public synchronized void addDag(Dag<JobExecutionPlan> dag, boolean persist, boolean setStatus) throws IOException {
    // TODO: Used to track missing dag issue, remove later as needed
    log.info("Add dag (persist: {}, setStatus: {}): {}", persist, setStatus, dag);
    if (!isActive) {
      log.warn("Skipping add dag because this instance of DagManager is not active for dag: {}", dag);
      return;
    }

    DagId dagId = DagManagerUtils.generateDagId(dag);
    if (persist) {
      // Persist the dag
      this.dagStateStore.writeCheckpoint(dag);
      // After persisting the dag, its status will be tracked by active dagManagers so the action should be deleted
      // to avoid duplicate executions upon leadership change
      if (this.dagActionStore.isPresent()) {
        this.dagActionStore.get().deleteDagAction(dagId.toDagAction(DagActionStore.FlowActionType.LAUNCH));
      }
    }
    int queueId = DagManagerUtils.getDagQueueId(dag, this.numThreads);
    // Add the dag to the specific queue determined by flowExecutionId
    // Flow cancellation request has to be forwarded to the same DagManagerThread where the
    // flow create request was forwarded. This is because Azkaban Exec Id is stored in the DagNode of the
    // specific DagManagerThread queue
    if (!this.runQueue[queueId].offer(dag)) {
      throw new IOException("Could not add dag" + dagId + "to queue");
    }
    if (setStatus) {
      DagManagerUtils.submitPendingExecStatus(dag, this.eventSubmitter);
    }
  }

  /**
   * Method to submit a {@link URI} for cancellation requests to the {@link DagManager}.
   * The {@link DagManager} adds the dag to the {@link BlockingQueue} to be picked up by one of the {@link DagManagerThread}s.
   */
  synchronized public void stopDag(URI uri) throws IOException {
    String flowGroup = FlowSpec.Utils.getFlowGroup(uri);
    String flowName = FlowSpec.Utils.getFlowName(uri);

    List<Long> flowExecutionIds = this.jobStatusRetriever.getLatestExecutionIdsForFlow(flowName, flowGroup, 10);
    log.info("Found {} flows to cancel.", flowExecutionIds.size());

    for (long flowExecutionId : flowExecutionIds) {
      killFlow(flowGroup, flowName, flowExecutionId);
    }
  }

  /**
   * Add the specified flow to {@link DagManager#cancelQueue}
   */
  private void killFlow(String flowGroup, String flowName, long flowExecutionId) throws IOException {
    int queueId =  DagManagerUtils.getDagQueueId(flowExecutionId, this.numThreads);
    DagId dagId = DagManagerUtils.generateDagId(flowGroup, flowName, flowExecutionId);
    if (!this.cancelQueue[queueId].offer(dagId)) {
      throw new IOException("Could not add dag " + dagId + " to cancellation queue.");
    }
  }

  @Subscribe
  public void handleKillFlowEvent(KillFlowEvent killFlowEvent) {
    handleKillFlowRequest(killFlowEvent.getFlowGroup(), killFlowEvent.getFlowName(), killFlowEvent.getFlowExecutionId());
  }

  // Method used to handle kill flow requests received from subscriber-event model or from direct invocation
  public void handleKillFlowRequest(String flowGroup, String flowName, long flowExecutionId) {
    if (isActive) {
      log.info("Received kill request for flow ({}, {}, {})", flowGroup, flowName, flowExecutionId);
      try {
        killFlow(flowGroup, flowName, flowExecutionId);
      } catch (IOException e) {
        log.warn("Failed to kill flow", e);
      }
    }
  }

  // Method used to handle resume flow requests received from subscriber-event model or from direct invocation
  public void handleResumeFlowRequest(String flowGroup, String flowName, long flowExecutionId) {
    if (isActive) {
      log.info("Received resume request for flow ({}, {}, {})", flowGroup, flowName, flowExecutionId);
      DagId dagId = DagManagerUtils.generateDagId(flowGroup, flowName, flowExecutionId);
      int queueId = DagManagerUtils.getDagQueueId(flowExecutionId, this.numThreads);
      if (!this.resumeQueue[queueId].offer(dagId)) {
        log.warn("Could not add dag " + dagId + " to resume queue");
      }
    }
  }

  @Subscribe
  public void handleResumeFlowEvent(ResumeFlowEvent resumeFlowEvent) {
    handleResumeFlowRequest(resumeFlowEvent.getFlowGroup(), resumeFlowEvent.getFlowName(), resumeFlowEvent.getFlowExecutionId());
  }

  public synchronized void setTopologySpecMap(Map<URI, TopologySpec> topologySpecMap) {
    this.topologySpecMap = topologySpecMap;
  }

  /**
   * When a {@link DagManager} becomes active, it loads the serialized representations of the currently running {@link Dag}s
   * from the checkpoint directory, deserializes the {@link Dag}s and adds them to a queue to be consumed by
   * the {@link DagManagerThread}s.
   * @param active a boolean to indicate if the {@link DagManager} is the leader.
   */
  public synchronized void setActive(boolean active) {
    if (this.isActive == active) {
      log.info("DagManager already {}, skipping further actions.", (!active) ? "inactive" : "active");
      return;
    }
    this.isActive = active;
    try {
      if (this.isActive) {
        log.info("Activating DagManager.");
        log.info("Scheduling {} DagManager threads", numThreads);
        //Initializing state store for persisting Dags.
        this.dagStateStore = createDagStateStore(config, topologySpecMap);
        DagStateStore failedDagStateStore =
            createDagStateStore(ConfigUtils.getConfigOrEmpty(config, FAILED_DAG_STATESTORE_PREFIX).withFallback(config),
                topologySpecMap);
        Set<String> failedDagIds = Collections.synchronizedSet(failedDagStateStore.getDagIds());

        this.dagManagerMetrics.activate();

        UserQuotaManager quotaManager = GobblinConstructorUtils.invokeConstructor(UserQuotaManager.class,
            ConfigUtils.getString(config, ServiceConfigKeys.QUOTA_MANAGER_CLASS, ServiceConfigKeys.DEFAULT_QUOTA_MANAGER), config);
        quotaManager.init(dagStateStore.getDags());

        //On startup, the service creates DagManagerThreads that are scheduled at a fixed rate.
        this.dagManagerThreads = new DagManagerThread[numThreads];
        for (int i = 0; i < numThreads; i++) {
          DagManagerThread dagManagerThread = new DagManagerThread(jobStatusRetriever, dagStateStore, failedDagStateStore, dagActionStore,
              runQueue[i], cancelQueue[i], resumeQueue[i], failedDagIds, this.dagManagerMetrics,
              this.defaultJobStartSlaTimeMillis, quotaManager, i);
          this.dagManagerThreads[i] = dagManagerThread;
          this.scheduledExecutorPool.scheduleAtFixedRate(dagManagerThread, 0, this.pollingInterval, TimeUnit.SECONDS);
        }
        FailedDagRetentionThread failedDagRetentionThread = new FailedDagRetentionThread(failedDagStateStore, failedDagIds, failedDagRetentionTime);
        this.scheduledExecutorPool.scheduleAtFixedRate(failedDagRetentionThread, 0, retentionPollingInterval, TimeUnit.MINUTES);
        loadDagFromDagStateStore();
        this.houseKeepingThreadPool = Executors.newSingleThreadScheduledExecutor();
        for (int delay = INITIAL_HOUSEKEEPING_THREAD_DELAY; delay < MAX_HOUSEKEEPING_THREAD_DELAY; delay *= 2) {
          this.houseKeepingThreadPool.schedule(() -> {
            try {
              loadDagFromDagStateStore();
            } catch (Exception e ) {
              log.error("failed to sync dag state store due to ", e);
            }}, delay, TimeUnit.MINUTES);
        }
      } else { //Mark the DagManager inactive.
        log.info("Inactivating the DagManager. Shutting down all DagManager threads");
        this.scheduledExecutorPool.shutdown();
        this.dagManagerMetrics.cleanup();
        this.houseKeepingThreadPool.shutdown();
        try {
          this.scheduledExecutorPool.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          log.error("Exception encountered when shutting down DagManager threads.", e);
        }
      }
    } catch (IOException e) {
      log.error("Exception encountered when activating the new DagManager", e);
      throw new RuntimeException(e);
    }
  }

  private void loadDagFromDagStateStore() throws IOException {
    List<Dag<JobExecutionPlan>> dags = dagStateStore.getDags();
    log.info("Loading " + dags.size() + " dags from dag state store");
    for (Dag<JobExecutionPlan> dag : dags) {
      if (this.isActive) {
        addDag(dag, false, false);
      }
    }
  }

  /*
   Deletes spec from flowCatalog if it is an adhoc flow (not containing a job schedule)
  */
  private void deleteSpecFromCatalogIfAdhoc(FlowSpec flowSpec) {
    if (!flowSpec.isScheduled()) {
      this.flowCatalog.remove(flowSpec.getUri(), new Properties(), false);
    }
  }

  /**
   * Each {@link DagManagerThread} performs 2 actions when scheduled:
   * <ol>
   *   <li> Dequeues any newly submitted {@link Dag}s from the Dag queue. All the {@link JobExecutionPlan}s which
   *   are part of the dequed {@link Dag} will be managed this thread. </li>
   *   <li> Polls the job status store for the current job statuses of all the running jobs it manages.</li>
   * </ol>
   */
  public static class DagManagerThread implements Runnable {
    private final Map<DagNode<JobExecutionPlan>, Dag<JobExecutionPlan>> jobToDag = new HashMap<>();
    private final Map<String, Dag<JobExecutionPlan>> dags = new HashMap<>();
    private final Set<String> failedDagIds;
    private final Map<String, Dag<JobExecutionPlan>> resumingDags = new HashMap<>();
    // dagToJobs holds a map of dagId to running jobs of that dag
    final Map<String, LinkedList<DagNode<JobExecutionPlan>>> dagToJobs = new HashMap<>();
    final Map<String, Long> dagToSLA = new HashMap<>();
    private final MetricContext metricContext;
    private final Set<String> dagIdstoClean = new HashSet<>();
    private final EventSubmitter eventSubmitter;
    private final Timer jobStatusPolledTimer;
    private final AtomicLong orchestrationDelay = new AtomicLong(0);
    private final DagManagerMetrics dagManagerMetrics;
    private final UserQuotaManager quotaManager;
    private final JobStatusRetriever jobStatusRetriever;
    private final DagStateStore dagStateStore;
    private final DagStateStore failedDagStateStore;
    private final BlockingQueue<Dag<JobExecutionPlan>> queue;
    private final BlockingQueue<DagId> cancelQueue;
    private final BlockingQueue<DagId> resumeQueue;
    private final Long defaultJobStartSlaTimeMillis;
    private final Optional<DagActionStore> dagActionStore;
    private final Meter dagManagerThreadHeartbeat;
    /**
     * Constructor.
     */
    DagManagerThread(JobStatusRetriever jobStatusRetriever, DagStateStore dagStateStore, DagStateStore failedDagStateStore,
        Optional<DagActionStore> dagActionStore, BlockingQueue<Dag<JobExecutionPlan>> queue, BlockingQueue<DagId> cancelQueue,
        BlockingQueue<DagId> resumeQueue, Set<String> failedDagIds, DagManagerMetrics dagManagerMetrics,
        Long defaultJobStartSla, UserQuotaManager quotaManager, int dagMangerThreadId) {
      this.jobStatusRetriever = jobStatusRetriever;
      this.dagStateStore = dagStateStore;
      this.failedDagStateStore = failedDagStateStore;
      this.failedDagIds = failedDagIds;
      this.queue = queue;
      this.cancelQueue = cancelQueue;
      this.resumeQueue = resumeQueue;
      this.dagManagerMetrics = dagManagerMetrics;
      this.defaultJobStartSlaTimeMillis = defaultJobStartSla;
      this.quotaManager = quotaManager;
      this.dagActionStore = dagActionStore;
      this.metricContext = Instrumented.getMetricContext(ConfigUtils.configToState(ConfigFactory.empty()), getClass());
      this.eventSubmitter = new EventSubmitter.Builder(this.metricContext, "org.apache.gobblin.service").build();
      this.jobStatusPolledTimer = this.metricContext.timer(ServiceMetricNames.JOB_STATUS_POLLED_TIMER);
      ContextAwareGauge<Long> orchestrationDelayMetric = metricContext.newContextAwareGauge(ServiceMetricNames.FLOW_ORCHESTRATION_DELAY,
          orchestrationDelay::get);
      this.metricContext.register(orchestrationDelayMetric);
      this.dagManagerThreadHeartbeat = this.metricContext.contextAwareMeter(String.format(DAG_MANAGER_HEARTBEAT, dagMangerThreadId));
    }

    /**
     * Main body of the {@link DagManagerThread}. Deque the next item from the queue and poll job statuses of currently
     * running jobs.
     * Because this thread runs in a regular interval, we should avoid doing repetitive work inside it.
     */
    @Override
    public void run() {
      try {
        DagId nextDagToCancel = cancelQueue.poll();
        //Poll the cancelQueue for a new Dag to cancel.
        if (nextDagToCancel != null) {
          cancelDag(nextDagToCancel);
        }

        while (!queue.isEmpty()) {
          Dag<JobExecutionPlan> dag = queue.poll();
          //Poll the queue for a new Dag to execute.
          if (dag != null) {
            if (dag.isEmpty()) {
              log.warn("Empty dag; ignoring the dag");
            }
            //Initialize dag.
            initialize(dag);
          } else {
            log.warn("Null dag despite non-empty queue; ignoring the dag");
          }
        }

        while (!resumeQueue.isEmpty()) {
          DagId dagId = resumeQueue.poll();
          beginResumingDag(dagId);
        }

        finishResumingDags();

        log.debug("Polling job statuses..");
        //Poll and update the job statuses of running jobs.
        pollAndAdvanceDag();
        log.debug("Poll done.");
        //Clean up any finished dags
        log.debug("Cleaning up finished dags..");
        cleanUp();
        log.debug("Clean up done");
        Instrumented.markMeter(dagManagerThreadHeartbeat);
      } catch (Exception e) {
        log.error(String.format("Exception encountered in %s", getClass().getName()), e);
      }
    }

    private void removeDagActionFromStore(DagId dagId, DagActionStore.FlowActionType flowActionType) throws IOException {
      if (this.dagActionStore.isPresent()) {
        this.dagActionStore.get().deleteDagAction(
            new DagActionStore.DagAction(dagId.flowGroup, dagId.flowName, dagId.flowExecutionId, flowActionType));
      }
    }

    /**
     * Begin resuming a dag by setting the status of both the dag and the failed/cancelled dag nodes to {@link ExecutionStatus#PENDING_RESUME},
     * and also sending events so that this status will be reflected in the job status state store.
     */
    private void beginResumingDag(DagId dagIdToResume) throws IOException {
      String dagId= dagIdToResume.toString();
      if (!this.failedDagIds.contains(dagId)) {
        log.warn("No dag found with dagId " + dagId + ", so cannot resume flow");
        removeDagActionFromStore(dagIdToResume, DagActionStore.FlowActionType.RESUME);
        return;
      }
      Dag<JobExecutionPlan> dag = this.failedDagStateStore.getDag(dagId);
      if (dag == null) {
        log.error("Dag " + dagId + " was found in memory but not found in failed dag state store");
        removeDagActionFromStore(dagIdToResume, DagActionStore.FlowActionType.RESUME);
        return;
      }

      long flowResumeTime = System.currentTimeMillis();

      // Set the flow and it's failed or cancelled nodes to PENDING_RESUME so that the flow will be resumed from the point before it failed
      DagManagerUtils.emitFlowEvent(this.eventSubmitter, dag, TimingEvent.FlowTimings.FLOW_PENDING_RESUME);
      for (DagNode<JobExecutionPlan> node : dag.getNodes()) {
        ExecutionStatus executionStatus = node.getValue().getExecutionStatus();
        if (executionStatus.equals(FAILED) || executionStatus.equals(CANCELLED)) {
          node.getValue().setExecutionStatus(PENDING_RESUME);
          // reset currentAttempts because we do not want to count previous execution's attempts in deciding whether to retry a job
          node.getValue().setCurrentAttempts(0);
          DagManagerUtils.incrementJobGeneration(node);
          Map<String, String> jobMetadata = TimingEventUtils.getJobMetadata(Maps.newHashMap(), node.getValue());
          this.eventSubmitter.getTimingEvent(TimingEvent.LauncherTimings.JOB_PENDING_RESUME).stop(jobMetadata);
        }

        // Set flowStartTime so that flow SLA will be based on current time instead of original flow
        node.getValue().setFlowStartTime(flowResumeTime);
      }

      this.resumingDags.put(dagId, dag);
    }

    /**
     * Finish resuming dags by first verifying the status is correct (flow should be {@link ExecutionStatus#PENDING_RESUME}
     * and jobs should not be {@link ExecutionStatus#FAILED} or {@link ExecutionStatus#CANCELLED}) and then calling
     * {@link #initialize}. This is separated from {@link #beginResumingDag} because it could take some time for the
     * job status state store to reflect the updated status.
     */
    private void finishResumingDags() throws IOException {
      for (Map.Entry<String, Dag<JobExecutionPlan>> dag : this.resumingDags.entrySet()) {
        java.util.Optional<JobStatus> flowStatus = DagManagerUtils.pollFlowStatus(dag.getValue(), this.jobStatusRetriever, this.jobStatusPolledTimer);
        if (!flowStatus.filter(fs -> fs.getEventName().equals(PENDING_RESUME.name())).isPresent()) {
          continue;
        }

        boolean dagReady = true;
        for (DagNode<JobExecutionPlan> node : dag.getValue().getNodes()) {
          java.util.Optional<JobStatus> jobStatus = DagManagerUtils.pollJobStatus(node, this.jobStatusRetriever, this.jobStatusPolledTimer);
          if (jobStatus.filter(js -> {
            String jobName = js.getEventName();
            return jobName.equals(FAILED.name()) || jobName.equals(CANCELLED.name());
          }).isPresent()) {
            dagReady = false;
            break;
          }
        }

        if (dagReady) {
          this.dagStateStore.writeCheckpoint(dag.getValue());
          this.failedDagStateStore.cleanUp(dag.getValue());
          removeDagActionFromStore(DagManagerUtils.generateDagId(dag.getValue()), DagActionStore.FlowActionType.RESUME);
          this.failedDagIds.remove(dag.getKey());
          this.resumingDags.remove(dag.getKey());
          initialize(dag.getValue());
        }
      }
    }

    /**
     * Cancels the dag and sends a cancellation tracking event.
     * @param dagId dag node to cancel
     * @throws ExecutionException executionException
     * @throws InterruptedException interruptedException
     */
    private void cancelDag(DagId dagId) throws ExecutionException, InterruptedException, IOException {
      String dagToCancel = dagId.toString();
      log.info("Cancel flow with DagId {}", dagToCancel);
      if (this.dagToJobs.containsKey(dagToCancel)) {
        List<DagNode<JobExecutionPlan>> dagNodesToCancel = this.dagToJobs.get(dagToCancel);
        log.info("Found {} DagNodes to cancel (DagId {}).", dagNodesToCancel.size(), dagToCancel);
        for (DagNode<JobExecutionPlan> dagNodeToCancel : dagNodesToCancel) {
          cancelDagNode(dagNodeToCancel);
        }

        this.dags.get(dagToCancel).setFlowEvent(TimingEvent.FlowTimings.FLOW_CANCELLED);
        this.dags.get(dagToCancel).setMessage("Flow killed by request");
      } else {
        log.warn("Did not find Dag with id {}, it might be already cancelled/finished.", dagToCancel);
      }
      // Called after a KILL request is received
      removeDagActionFromStore(dagId, DagActionStore.FlowActionType.KILL);
    }

    private void cancelDagNode(DagNode<JobExecutionPlan> dagNodeToCancel) throws ExecutionException, InterruptedException {
      Properties props = new Properties();
      if (dagNodeToCancel.getValue().getJobFuture().isPresent()) {
        Future future = dagNodeToCancel.getValue().getJobFuture().get();
        String serializedFuture = DagManagerUtils.getSpecProducer(dagNodeToCancel).serializeAddSpecResponse(future);
        props.put(ConfigurationKeys.SPEC_PRODUCER_SERIALIZED_FUTURE, serializedFuture);
        sendCancellationEvent(dagNodeToCancel.getValue());
      } else {
        log.warn("No Job future when canceling DAG node (hence, not sending cancellation event) - {}", dagNodeToCancel.getValue().getJobSpec().getUri());
      }
      if (dagNodeToCancel.getValue().getJobSpec().getConfig().hasPath(ConfigurationKeys.FLOW_EXECUTION_ID_KEY)) {
        props.setProperty(ConfigurationKeys.FLOW_EXECUTION_ID_KEY,
            dagNodeToCancel.getValue().getJobSpec().getConfig().getString(ConfigurationKeys.FLOW_EXECUTION_ID_KEY));
      }
      DagManagerUtils.getSpecProducer(dagNodeToCancel).cancelJob(dagNodeToCancel.getValue().getJobSpec().getUri(), props);
    }

    private void sendCancellationEvent(JobExecutionPlan jobExecutionPlan) {
      Map<String, String> jobMetadata = TimingEventUtils.getJobMetadata(Maps.newHashMap(), jobExecutionPlan);
      this.eventSubmitter.getTimingEvent(TimingEvent.LauncherTimings.JOB_CANCEL).stop(jobMetadata);
      jobExecutionPlan.setExecutionStatus(CANCELLED);
    }

    /**
     * This method determines the next set of jobs to execute from the dag and submits them for execution.
     * This method updates internal data structures tracking currently running Dags and jobs.
     */
    private void initialize(Dag<JobExecutionPlan> dag)
        throws IOException {
      //Add Dag to the map of running dags
      String dagId = DagManagerUtils.generateDagId(dag).toString();
      log.info("Initializing Dag {}", DagManagerUtils.getFullyQualifiedDagName(dag));
      if (this.dags.containsKey(dagId)) {
        log.warn("Already tracking a dag with dagId {}, skipping.", dagId);
        return;
      }

      this.dags.put(dagId, dag);
      log.debug("Dag {} - determining if any jobs are already running.", DagManagerUtils.getFullyQualifiedDagName(dag));

      //A flag to indicate if the flow is already running.
      boolean isDagRunning = false;
      //Are there any jobs already in the running state? This check is for Dags already running
      //before a leadership change occurs.
      for (DagNode<JobExecutionPlan> dagNode : dag.getNodes()) {
        if (DagManagerUtils.getExecutionStatus(dagNode) == RUNNING) {
          addJobState(dagId, dagNode);
          //Update the running jobs counter.
          dagManagerMetrics.incrementRunningJobMetrics(dagNode);
          isDagRunning = true;
        }
      }

      FlowId flowId = DagManagerUtils.getFlowId(dag);
      this.dagManagerMetrics.registerFlowMetric(flowId, dag);

      log.debug("Dag {} submitting jobs ready for execution.", DagManagerUtils.getFullyQualifiedDagName(dag));
      //Determine the next set of jobs to run and submit them for execution
      Map<String, Set<DagNode<JobExecutionPlan>>> nextSubmitted = submitNext(dagId);
      for (DagNode<JobExecutionPlan> dagNode: nextSubmitted.get(dagId)) {
        addJobState(dagId, dagNode);
      }

      // Set flow status to running
      DagManagerUtils.emitFlowEvent(this.eventSubmitter, dag, TimingEvent.FlowTimings.FLOW_RUNNING);
      dagManagerMetrics.conditionallyMarkFlowAsState(flowId, FlowState.RUNNING);

      // Report the orchestration delay the first time the Dag is initialized. Orchestration delay is defined as
      // the time difference between the instant when a flow first transitions to the running state and the instant
      // when the flow is submitted to Gobblin service.
      if (!isDagRunning) {
        this.orchestrationDelay.set(System.currentTimeMillis() - DagManagerUtils.getFlowExecId(dag));
      }

      log.info("Dag {} Initialization complete.", DagManagerUtils.getFullyQualifiedDagName(dag));
    }

    /**
     * Proceed the execution of each dag node based on job status.
     */
    private void pollAndAdvanceDag() {
      Map<String, Set<DagNode<JobExecutionPlan>>> nextSubmitted = Maps.newHashMap();
      List<DagNode<JobExecutionPlan>> nodesToCleanUp = Lists.newArrayList();

      for (DagNode<JobExecutionPlan> node : this.jobToDag.keySet()) {
        try {
          boolean slaKilled = slaKillIfNeeded(node);

          java.util.Optional<JobStatus> jobStatus = DagManagerUtils.pollJobStatus(node, this.jobStatusRetriever, this.jobStatusPolledTimer);

          boolean killOrphanFlow = killJobIfOrphaned(node, jobStatus);

          ExecutionStatus status = getJobExecutionStatus(slaKilled, killOrphanFlow, jobStatus);

          JobExecutionPlan jobExecutionPlan = DagManagerUtils.getJobExecutionPlan(node);

          switch (status) {
            case COMPLETE:
              jobExecutionPlan.setExecutionStatus(COMPLETE);
              nextSubmitted.putAll(onJobFinish(node));
              nodesToCleanUp.add(node);
              break;
            case FAILED:
              jobExecutionPlan.setExecutionStatus(FAILED);
              nextSubmitted.putAll(onJobFinish(node));
              nodesToCleanUp.add(node);
              break;
            case CANCELLED:
              jobExecutionPlan.setExecutionStatus(CANCELLED);
              nextSubmitted.putAll(onJobFinish(node));
              nodesToCleanUp.add(node);
              break;
            case PENDING:
              jobExecutionPlan.setExecutionStatus(PENDING);
              break;
            case PENDING_RETRY:
              jobExecutionPlan.setExecutionStatus(PENDING_RETRY);
              break;
            default:
              jobExecutionPlan.setExecutionStatus(RUNNING);
              break;
          }

          if (jobStatus.filter(JobStatus::isShouldRetry).isPresent()) {
            log.info("Retrying job: {}, current attempts: {}, max attempts: {}", DagManagerUtils.getFullyQualifiedJobName(node),
                jobStatus.get().getCurrentAttempts(), jobStatus.get().getMaxAttempts());
            this.jobToDag.get(node).setFlowEvent(null);
            submitJob(node);
          }
        } catch (InterruptedException | IOException | ExecutionException e) {
          // Error occurred while processing dag, continue processing other dags assigned to this thread
          log.error(String.format("Exception caught in DagManager while processing dag %s due to ",
              DagManagerUtils.getFullyQualifiedDagName(node)), e);
        }
      }

      for (Map.Entry<String, Set<DagNode<JobExecutionPlan>>> entry: nextSubmitted.entrySet()) {
        String dagId = entry.getKey();
        Set<DagNode<JobExecutionPlan>> dagNodes = entry.getValue();
        for (DagNode<JobExecutionPlan> dagNode: dagNodes) {
          addJobState(dagId, dagNode);
        }
      }

      for (DagNode<JobExecutionPlan> dagNode: nodesToCleanUp) {
        String dagId = DagManagerUtils.generateDagId(dagNode).toString();
        deleteJobState(dagId, dagNode);
      }
    }

    /**
     * Cancel the job if the job has been "orphaned". A job is orphaned if has been in ORCHESTRATED
     * {@link ExecutionStatus} for some specific amount of time.
     * @param node {@link DagNode} representing the job
     * @param jobStatus current {@link JobStatus} of the job
     * @return true if the total time that the job remains in the ORCHESTRATED state exceeds
     * {@value ConfigurationKeys#GOBBLIN_JOB_START_SLA_TIME}.
     */
    private boolean killJobIfOrphaned(DagNode<JobExecutionPlan> node, java.util.Optional<JobStatus> jobStatus)
        throws ExecutionException, InterruptedException {
      if (!jobStatus.isPresent()) {
        return false;
      }
      ExecutionStatus executionStatus = valueOf(jobStatus.get().getEventName());
      long timeOutForJobStart = DagManagerUtils.getJobStartSla(node, this.defaultJobStartSlaTimeMillis);
      long jobOrchestratedTime = jobStatus.get().getOrchestratedTime();
      if (executionStatus == ORCHESTRATED && System.currentTimeMillis() - jobOrchestratedTime > timeOutForJobStart) {
        log.info("Job {} of flow {} exceeded the job start SLA of {} ms. Killing the job now...",
            DagManagerUtils.getJobName(node),
            DagManagerUtils.getFullyQualifiedDagName(node),
            timeOutForJobStart);
        dagManagerMetrics.incrementCountsStartSlaExceeded(node);
        cancelDagNode(node);

        String dagId = DagManagerUtils.generateDagId(node).toString();
        this.dags.get(dagId).setFlowEvent(TimingEvent.FlowTimings.FLOW_START_DEADLINE_EXCEEDED);
        this.dags.get(dagId).setMessage("Flow killed because no update received for " + timeOutForJobStart + " ms after orchestration");
        return true;
      } else {
        return false;
      }
    }

    private ExecutionStatus getJobExecutionStatus(boolean slaKilled, boolean killOrphanFlow, java.util.Optional<JobStatus> jobStatus) {
      if (slaKilled || killOrphanFlow) {
        return CANCELLED;
      } else {
        return jobStatus.map(status -> valueOf(status.getEventName())).orElse(PENDING);
      }
    }

    /**
     * Check if the SLA is configured for the flow this job belongs to.
     * If it is, this method will try to cancel the job when SLA is reached.
     *
     * @param node dag node of the job
     * @return true if the job is killed because it reached sla
     * @throws ExecutionException exception
     * @throws InterruptedException exception
     */
    private boolean slaKillIfNeeded(DagNode<JobExecutionPlan> node) throws ExecutionException, InterruptedException {
      long flowStartTime = DagManagerUtils.getFlowStartTime(node);
      long currentTime = System.currentTimeMillis();
      String dagId = DagManagerUtils.generateDagId(node).toString();

      long flowSla;
      if (dagToSLA.containsKey(dagId)) {
        flowSla = dagToSLA.get(dagId);
      } else {
        try {
          flowSla = DagManagerUtils.getFlowSLA(node);
        } catch (ConfigException e) {
          log.warn("Flow SLA for flowGroup: {}, flowName: {} is given in invalid format, using default SLA of {}",
              node.getValue().getJobSpec().getConfig().getString(ConfigurationKeys.FLOW_GROUP_KEY),
              node.getValue().getJobSpec().getConfig().getString(ConfigurationKeys.FLOW_NAME_KEY),
              DagManagerUtils.DEFAULT_FLOW_SLA_MILLIS);
          flowSla = DagManagerUtils.DEFAULT_FLOW_SLA_MILLIS;
        }
        dagToSLA.put(dagId, flowSla);
      }

      if (currentTime > flowStartTime + flowSla) {
        log.info("Flow {} exceeded the SLA of {} ms. Killing the job {} now...",
            node.getValue().getJobSpec().getConfig().getString(ConfigurationKeys.FLOW_NAME_KEY), flowSla,
            node.getValue().getJobSpec().getConfig().getString(ConfigurationKeys.JOB_NAME_KEY));
        dagManagerMetrics.incrementExecutorSlaExceeded(node);
        cancelDagNode(node);

        this.dags.get(dagId).setFlowEvent(TimingEvent.FlowTimings.FLOW_RUN_DEADLINE_EXCEEDED);
        this.dags.get(dagId).setMessage("Flow killed due to exceeding SLA of " + flowSla + " ms");

        return true;
      }
      return false;
    }



    /**
     * Submit next set of Dag nodes in the Dag identified by the provided dagId
     * @param dagId The dagId that should be processed.
     * @return
     * @throws IOException
     */
    synchronized Map<String, Set<DagNode<JobExecutionPlan>>> submitNext(String dagId) throws IOException {
      Dag<JobExecutionPlan> dag = this.dags.get(dagId);
      Set<DagNode<JobExecutionPlan>> nextNodes = DagManagerUtils.getNext(dag);
      List<String> nextJobNames = new ArrayList<>();

      //Submit jobs from the dag ready for execution.
      for (DagNode<JobExecutionPlan> dagNode : nextNodes) {
        submitJob(dagNode);
        nextJobNames.add(DagManagerUtils.getJobName(dagNode));
      }
      log.info("Submitting next nodes for dagId {}, where next jobs to be submitted are {}", dagId, nextJobNames);
      //Checkpoint the dag state
      this.dagStateStore.writeCheckpoint(dag);

      Map<String, Set<DagNode<JobExecutionPlan>>> dagIdToNextJobs = Maps.newHashMap();
      dagIdToNextJobs.put(dagId, nextNodes);
      return dagIdToNextJobs;
    }

    /**
     * Submits a {@link JobSpec} to a {@link org.apache.gobblin.runtime.api.SpecExecutor}.
     */
    private void submitJob(DagNode<JobExecutionPlan> dagNode) {
      DagManagerUtils.incrementJobAttempt(dagNode);
      JobExecutionPlan jobExecutionPlan = DagManagerUtils.getJobExecutionPlan(dagNode);
      jobExecutionPlan.setExecutionStatus(RUNNING);
      JobSpec jobSpec = DagManagerUtils.getJobSpec(dagNode);
      Map<String, String> jobMetadata = TimingEventUtils.getJobMetadata(Maps.newHashMap(), jobExecutionPlan);

      String specExecutorUri = DagManagerUtils.getSpecExecutorUri(dagNode);

      // Run this spec on selected executor
      SpecProducer<Spec> producer;
      try {
        quotaManager.checkQuota(Collections.singleton(dagNode));

        producer = DagManagerUtils.getSpecProducer(dagNode);
        TimingEvent jobOrchestrationTimer = new TimingEvent(this.eventSubmitter, TimingEvent.LauncherTimings.JOB_ORCHESTRATED);

        // Increment job count before submitting the job onto the spec producer, in case that throws an exception.
        // By this point the quota is allocated, so it's imperative to increment as missing would introduce the potential to decrement below zero upon quota release.
        // Quota release is guaranteed, despite failure, because exception handling within would mark the job FAILED.
        // When the ensuing kafka message spurs DagManager processing, the quota is released and the counts decremented
        // Ensure that we do not double increment for flows that are retried
        if (dagNode.getValue().getCurrentAttempts() == 1) {
          dagManagerMetrics.incrementRunningJobMetrics(dagNode);
        }
        // Submit the job to the SpecProducer, which in turn performs the actual job submission to the SpecExecutor instance.
        // The SpecProducer implementations submit the job to the underlying executor and return when the submission is complete,
        // either successfully or unsuccessfully. To catch any exceptions in the job submission, the DagManagerThread
        // blocks (by calling Future#get()) until the submission is completed.
        Future<?> addSpecFuture = producer.addSpec(jobSpec);
        dagNode.getValue().setJobFuture(Optional.of(addSpecFuture));
        //Persist the dag
        this.dagStateStore.writeCheckpoint(this.dags.get(DagManagerUtils.generateDagId(dagNode).toString()));

        addSpecFuture.get();

        jobMetadata.put(TimingEvent.METADATA_MESSAGE, producer.getExecutionLink(addSpecFuture, specExecutorUri));
        // Add serialized job properties as part of the orchestrated job event metadata
        jobMetadata.put(JobExecutionPlan.JOB_PROPS_KEY, dagNode.getValue().toString());
        jobOrchestrationTimer.stop(jobMetadata);
        log.info("Orchestrated job: {} on Executor: {}", DagManagerUtils.getFullyQualifiedJobName(dagNode), specExecutorUri);
        this.dagManagerMetrics.incrementJobsSentToExecutor(dagNode);
      } catch (Exception e) {
        TimingEvent jobFailedTimer = this.eventSubmitter.getTimingEvent(TimingEvent.LauncherTimings.JOB_FAILED);
        String message = "Cannot submit job " + DagManagerUtils.getFullyQualifiedJobName(dagNode) + " on executor " + specExecutorUri;
        log.error(message, e);
        jobMetadata.put(TimingEvent.METADATA_MESSAGE, message + " due to " + e.getMessage());
        if (jobFailedTimer != null) {
          jobFailedTimer.stop(jobMetadata);
        }
      }
    }

    /**
     * Method that defines the actions to be performed when a job finishes either successfully or with failure.
     * This method updates the state of the dag and performs clean up actions as necessary.
     */
    private Map<String, Set<DagNode<JobExecutionPlan>>> onJobFinish(DagNode<JobExecutionPlan> dagNode)
        throws IOException {
      Dag<JobExecutionPlan> dag = this.jobToDag.get(dagNode);
      String dagId = DagManagerUtils.generateDagId(dag).toString();
      String jobName = DagManagerUtils.getFullyQualifiedJobName(dagNode);
      ExecutionStatus jobStatus = DagManagerUtils.getExecutionStatus(dagNode);
      log.info("Job {} of Dag {} has finished with status {}", jobName, dagId, jobStatus.name());
      // Only decrement counters and quota for jobs that actually ran on the executor, not from a GaaS side failure/skip event
      if (quotaManager.releaseQuota(dagNode)) {
        dagManagerMetrics.decrementRunningJobMetrics(dagNode);
      }

      switch (jobStatus) {
        case FAILED:
          dag.setMessage("Flow failed because job " + jobName + " failed");
          dag.setFlowEvent(TimingEvent.FlowTimings.FLOW_FAILED);
          dagManagerMetrics.incrementExecutorFailed(dagNode);
          return Maps.newHashMap();
        case CANCELLED:
          dag.setFlowEvent(TimingEvent.FlowTimings.FLOW_CANCELLED);
          return Maps.newHashMap();
        case COMPLETE:
          dagManagerMetrics.incrementExecutorSuccess(dagNode);
          return submitNext(dagId);
        default:
          log.warn("It should not reach here. Job status is unexpected.");
          return Maps.newHashMap();
      }
    }

    private void deleteJobState(String dagId, DagNode<JobExecutionPlan> dagNode) {
      this.jobToDag.remove(dagNode);
      this.dagToJobs.get(dagId).remove(dagNode);
      this.dagToSLA.remove(dagId);
    }

    private void addJobState(String dagId, DagNode<JobExecutionPlan> dagNode) {
      Dag<JobExecutionPlan> dag = this.dags.get(dagId);
      this.jobToDag.put(dagNode, dag);
      if (this.dagToJobs.containsKey(dagId)) {
        this.dagToJobs.get(dagId).add(dagNode);
      } else {
        LinkedList<DagNode<JobExecutionPlan>> dagNodeList = Lists.newLinkedList();
        dagNodeList.add(dagNode);
        this.dagToJobs.put(dagId, dagNodeList);
      }
    }

    /**
     * Perform clean up. Remove a dag from the dagstore if the dag is complete and update internal state.
     */
    private void cleanUp() {
      // Approximate the time when the flow events are emitted to account for delay when the flow event is received by the job monitor
      long cleanUpProcessingTime = System.currentTimeMillis();

      // Remove dags that are finished and emit their appropriate metrics
      for (Map.Entry<String, Dag<JobExecutionPlan>> dagIdKeyPair : this.dags.entrySet()) {
        String dagId = dagIdKeyPair.getKey();
        // On service restart, we repopulate the dags that are waiting to be cleaned up
        if (dagIdstoClean.contains(dagId)) {
          continue;
        }
        Dag<JobExecutionPlan> dag = dagIdKeyPair.getValue();
        if ((TimingEvent.FlowTimings.FLOW_FAILED.equals(dag.getFlowEvent()) || TimingEvent.FlowTimings.FLOW_CANCELLED.equals(dag.getFlowEvent())) &&
            DagManagerUtils.getFailureOption(dag) == FailureOption.FINISH_RUNNING) {
          //Skip monitoring of any other jobs of the failed dag.
          LinkedList<DagNode<JobExecutionPlan>> dagNodeList = this.dagToJobs.get(dagId);
          while (!dagNodeList.isEmpty()) {
            DagNode<JobExecutionPlan> dagNode = dagNodeList.poll();
            deleteJobState(dagId, dagNode);
          }
        }
        if (!DagManagerUtils.hasRunningJobs(dagId, this.dagToJobs)) {
          // Collect all the dagIds that are finished
          this.dagIdstoClean.add(dagId);
          if (dag.getFlowEvent() == null) {
            // If the dag flow event is not set, then it is successful
            dag.setFlowEvent(TimingEvent.FlowTimings.FLOW_SUCCEEDED);
          } else {
            addFailedDag(dagId, dag);
          }
          // send an event before cleaning up dag
          DagManagerUtils.emitFlowEvent(this.eventSubmitter, this.dags.get(dagId), dag.getFlowEvent());
          dag.setEventEmittedTimeMillis(cleanUpProcessingTime);
        }
      }

      // Only clean up dags after the job status monitor processed the flow event
      for (Iterator<String> dagIdIterator = this.dagIdstoClean.iterator(); dagIdIterator.hasNext();) {
        String dagId = dagIdIterator.next();
        Dag<JobExecutionPlan> dag = this.dags.get(dagId);
        java.util.Optional<JobStatus> flowStatus = DagManagerUtils.pollFlowStatus(dag, this.jobStatusRetriever, this.jobStatusPolledTimer);
        if (flowStatus.filter(fs -> FlowStatusGenerator.FINISHED_STATUSES.contains(fs.getEventName())).isPresent()) {
          FlowId flowId = DagManagerUtils.getFlowId(dag);
          switch(dag.getFlowEvent()) {
            case TimingEvent.FlowTimings.FLOW_SUCCEEDED:
              this.dagManagerMetrics.emitFlowSuccessMetrics(flowId);
              this.dagManagerMetrics.conditionallyMarkFlowAsState(flowId, FlowState.SUCCESSFUL);
              break;
            case TimingEvent.FlowTimings.FLOW_FAILED:
              this.dagManagerMetrics.emitFlowFailedMetrics(flowId);
              this.dagManagerMetrics.conditionallyMarkFlowAsState(flowId, FlowState.FAILED);
              break;
            case TimingEvent.FlowTimings.FLOW_CANCELLED:
              this.dagManagerMetrics.emitFlowSlaExceededMetrics(flowId);
              this.dagManagerMetrics.conditionallyMarkFlowAsState(flowId, FlowState.FAILED);
              break;
            default:
              log.warn("Unexpected flow event {} for dag {}", dag.getFlowEvent(), dagId);
          }
          log.info("Dag {} has finished with status {}; Cleaning up dag from the state store.", dagId, dag.getFlowEvent());
          cleanUpDag(dagId);
          dagIdIterator.remove();
        } else if (cleanUpProcessingTime > dag.getEventEmittedTimeMillis() + DAG_FLOW_STATUS_TOLERANCE_TIME_MILLIS) {
          // Re-emit the flow event if the flow status has not been processed within the DagFlowStatusTolerance time
          DagManagerUtils.emitFlowEvent(this.eventSubmitter, dag, dag.getFlowEvent());
        } else {
          log.info("Waiting for flow event {} to be emitted before cleaning up dag {}", dag.getFlowEvent(), dagId);
        }
      }
    }

    /**
     * Add a dag to failed dag state store
     */
    private synchronized void addFailedDag(String dagId, Dag<JobExecutionPlan> dag) {
      try {
        log.info("Adding dag " + dagId + " to failed dag state store");
        this.failedDagStateStore.writeCheckpoint(this.dags.get(dagId));
      } catch (IOException e) {
        log.error("Failed to add dag " + dagId + " to failed dag state store", e);
      }
      this.failedDagIds.add(dagId);
    }

    /**
     * Note that removal of a {@link Dag} entry in {@link #dags} needs to be happen after {@link #cleanUp()}
     * since the real {@link Dag} object is required for {@link #cleanUp()},
     * and cleaning of all relevant states need to be atomic
     * @param dagId
     */
    private synchronized void cleanUpDag(String dagId) {
      log.info("Cleaning up dagId {}", dagId);
      // clears flow event after cancelled job to allow resume event status to be set
      this.dags.get(dagId).setFlowEvent(null);
      try {
        this.dagStateStore.cleanUp(dags.get(dagId));
      } catch (IOException ioe) {
        log.error(String.format("Failed to clean %s from backStore due to:", dagId), ioe);
      }
      this.dags.remove(dagId);
      this.dagToJobs.remove(dagId);
    }
  }

  public enum FlowState {
    FAILED(-1),
    RUNNING(0),
    SUCCESSFUL(1);

    public int value;

    FlowState(int value) {
      this.value = value;
    }
  }

  /**
   * Thread that runs retention on failed dags based on their original start time (which is the flow execution ID).
   */
  public static class FailedDagRetentionThread implements Runnable {
    private final DagStateStore failedDagStateStore;
    private final Set<String> failedDagIds;
    private final long failedDagRetentionTime;

    FailedDagRetentionThread(DagStateStore failedDagStateStore, Set<String> failedDagIds, long failedDagRetentionTime) {
      this.failedDagStateStore = failedDagStateStore;
      this.failedDagIds = failedDagIds;
      this.failedDagRetentionTime = failedDagRetentionTime;
    }

    @Override
    public void run() {
      try {
        log.info("Cleaning failed dag state store");
        long startTime = System.currentTimeMillis();
        int numCleaned = 0;

        Set<String> failedDagIdsCopy = new HashSet<>(this.failedDagIds);
        for (String dagId : failedDagIdsCopy) {
          if (this.failedDagRetentionTime > 0L && startTime > DagManagerUtils.getFlowExecId(dagId) + this.failedDagRetentionTime) {
            this.failedDagStateStore.cleanUp(dagId);
            this.failedDagIds.remove(dagId);
            numCleaned++;
          }
        }

        log.info("Cleaned " + numCleaned + " dags from the failed dag state store");
      } catch (Exception e) {
        log.error("Failed to run retention on failed dag state store", e);
      }
    }
  }

  /** Stop the service. */
  @Override
  protected void shutDown()
      throws Exception {
    this.scheduledExecutorPool.shutdown();
    this.scheduledExecutorPool.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS);
  }
}
