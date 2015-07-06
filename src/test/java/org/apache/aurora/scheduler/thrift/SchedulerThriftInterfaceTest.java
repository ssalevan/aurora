/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.thrift;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.twitter.common.testing.easymock.EasyMockTest;

import org.apache.aurora.auth.CapabilityValidator;
import org.apache.aurora.auth.CapabilityValidator.AuditCheck;
import org.apache.aurora.auth.CapabilityValidator.Capability;
import org.apache.aurora.auth.SessionValidator.AuthFailedException;
import org.apache.aurora.gen.AddInstancesConfig;
import org.apache.aurora.gen.AssignedTask;
import org.apache.aurora.gen.AuroraAdmin;
import org.apache.aurora.gen.ConfigRewrite;
import org.apache.aurora.gen.Constraint;
import org.apache.aurora.gen.Container;
import org.apache.aurora.gen.ExecutorConfig;
import org.apache.aurora.gen.HostStatus;
import org.apache.aurora.gen.Hosts;
import org.apache.aurora.gen.Identity;
import org.apache.aurora.gen.InstanceConfigRewrite;
import org.apache.aurora.gen.InstanceKey;
import org.apache.aurora.gen.InstanceTaskConfig;
import org.apache.aurora.gen.JobConfigRewrite;
import org.apache.aurora.gen.JobConfiguration;
import org.apache.aurora.gen.JobKey;
import org.apache.aurora.gen.JobUpdate;
import org.apache.aurora.gen.JobUpdateInstructions;
import org.apache.aurora.gen.JobUpdatePulseStatus;
import org.apache.aurora.gen.JobUpdateRequest;
import org.apache.aurora.gen.JobUpdateSettings;
import org.apache.aurora.gen.JobUpdateSummary;
import org.apache.aurora.gen.LimitConstraint;
import org.apache.aurora.gen.ListBackupsResult;
import org.apache.aurora.gen.Lock;
import org.apache.aurora.gen.LockKey;
import org.apache.aurora.gen.MesosContainer;
import org.apache.aurora.gen.PulseJobUpdateResult;
import org.apache.aurora.gen.QueryRecoveryResult;
import org.apache.aurora.gen.Range;
import org.apache.aurora.gen.ReadOnlyScheduler;
import org.apache.aurora.gen.ResourceAggregate;
import org.apache.aurora.gen.Response;
import org.apache.aurora.gen.ResponseDetail;
import org.apache.aurora.gen.Result;
import org.apache.aurora.gen.RewriteConfigsRequest;
import org.apache.aurora.gen.ScheduleStatus;
import org.apache.aurora.gen.ScheduledTask;
import org.apache.aurora.gen.SessionKey;
import org.apache.aurora.gen.StartJobUpdateResult;
import org.apache.aurora.gen.TaskConfig;
import org.apache.aurora.gen.TaskConstraint;
import org.apache.aurora.gen.TaskQuery;
import org.apache.aurora.gen.ValueConstraint;
import org.apache.aurora.scheduler.TaskIdGenerator;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.Query;
import org.apache.aurora.scheduler.configuration.ConfigurationManager;
import org.apache.aurora.scheduler.configuration.SanitizedConfiguration;
import org.apache.aurora.scheduler.cron.CronException;
import org.apache.aurora.scheduler.cron.CronJobManager;
import org.apache.aurora.scheduler.cron.SanitizedCronJob;
import org.apache.aurora.scheduler.quota.QuotaCheckResult;
import org.apache.aurora.scheduler.quota.QuotaManager;
import org.apache.aurora.scheduler.state.LockManager;
import org.apache.aurora.scheduler.state.LockManager.LockException;
import org.apache.aurora.scheduler.state.MaintenanceController;
import org.apache.aurora.scheduler.state.StateChangeResult;
import org.apache.aurora.scheduler.state.StateManager;
import org.apache.aurora.scheduler.state.UUIDGenerator;
import org.apache.aurora.scheduler.storage.Storage.StorageException;
import org.apache.aurora.scheduler.storage.backup.Recovery;
import org.apache.aurora.scheduler.storage.backup.StorageBackup;
import org.apache.aurora.scheduler.storage.entities.IInstanceKey;
import org.apache.aurora.scheduler.storage.entities.IJobConfiguration;
import org.apache.aurora.scheduler.storage.entities.IJobKey;
import org.apache.aurora.scheduler.storage.entities.IJobUpdate;
import org.apache.aurora.scheduler.storage.entities.ILockKey;
import org.apache.aurora.scheduler.storage.entities.IRange;
import org.apache.aurora.scheduler.storage.entities.IResourceAggregate;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.aurora.scheduler.storage.testing.StorageTestUtil;
import org.apache.aurora.scheduler.updater.JobUpdateController;
import org.apache.aurora.scheduler.updater.JobUpdateController.AuditData;
import org.apache.aurora.scheduler.updater.UpdateStateException;
import org.apache.thrift.TException;
import org.easymock.IExpectationSetters;
import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.auth.CapabilityValidator.Capability.ROOT;
import static org.apache.aurora.auth.CapabilityValidator.Capability.UPDATE_COORDINATOR;
import static org.apache.aurora.auth.SessionValidator.SessionContext;
import static org.apache.aurora.gen.LockValidation.CHECKED;
import static org.apache.aurora.gen.LockValidation.UNCHECKED;
import static org.apache.aurora.gen.MaintenanceMode.DRAINING;
import static org.apache.aurora.gen.MaintenanceMode.NONE;
import static org.apache.aurora.gen.MaintenanceMode.SCHEDULED;
import static org.apache.aurora.gen.ResponseCode.AUTH_FAILED;
import static org.apache.aurora.gen.ResponseCode.INVALID_REQUEST;
import static org.apache.aurora.gen.ResponseCode.LOCK_ERROR;
import static org.apache.aurora.gen.ResponseCode.OK;
import static org.apache.aurora.gen.ResponseCode.WARNING;
import static org.apache.aurora.scheduler.configuration.ConfigurationManager.DEDICATED_ATTRIBUTE;
import static org.apache.aurora.scheduler.storage.backup.Recovery.RecoveryException;
import static org.apache.aurora.scheduler.thrift.Fixtures.CRON_JOB;
import static org.apache.aurora.scheduler.thrift.Fixtures.ENOUGH_QUOTA;
import static org.apache.aurora.scheduler.thrift.Fixtures.JOB_KEY;
import static org.apache.aurora.scheduler.thrift.Fixtures.JOB_NAME;
import static org.apache.aurora.scheduler.thrift.Fixtures.LOCK;
import static org.apache.aurora.scheduler.thrift.Fixtures.LOCK_KEY;
import static org.apache.aurora.scheduler.thrift.Fixtures.NOT_ENOUGH_QUOTA;
import static org.apache.aurora.scheduler.thrift.Fixtures.ROLE;
import static org.apache.aurora.scheduler.thrift.Fixtures.ROLE_IDENTITY;
import static org.apache.aurora.scheduler.thrift.Fixtures.TASK_ID;
import static org.apache.aurora.scheduler.thrift.Fixtures.UPDATE_KEY;
import static org.apache.aurora.scheduler.thrift.Fixtures.USER;
import static org.apache.aurora.scheduler.thrift.Fixtures.UU_ID;
import static org.apache.aurora.scheduler.thrift.Fixtures.assertOkResponse;
import static org.apache.aurora.scheduler.thrift.Fixtures.assertResponse;
import static org.apache.aurora.scheduler.thrift.Fixtures.defaultTask;
import static org.apache.aurora.scheduler.thrift.Fixtures.makeJob;
import static org.apache.aurora.scheduler.thrift.Fixtures.makeProdJob;
import static org.apache.aurora.scheduler.thrift.Fixtures.nonProductionTask;
import static org.apache.aurora.scheduler.thrift.Fixtures.okResponse;
import static org.apache.aurora.scheduler.thrift.Fixtures.productionTask;
import static org.apache.aurora.scheduler.thrift.Fixtures.response;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.MAX_TASKS_PER_JOB;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.MAX_TASK_ID_LENGTH;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.NOOP_JOB_UPDATE_MESSAGE;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.NO_CRON;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.jobAlreadyExistsMessage;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.killedByMessage;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.noCronScheduleMessage;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.notScheduledCronMessage;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.restartedByMessage;
import static org.apache.aurora.scheduler.thrift.SchedulerThriftInterface.transitionMessage;
import static org.easymock.EasyMock.anyInt;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SchedulerThriftInterfaceTest extends EasyMockTest {

  private static final SessionKey SESSION = new SessionKey();
  private static final String AUDIT_MESSAGE = "message";
  private static final AuditData AUDIT = new AuditData(USER, Optional.of(AUDIT_MESSAGE));

  private StorageTestUtil storageUtil;
  private LockManager lockManager;
  private CapabilityValidator userValidator;
  private SessionContext context;
  private StorageBackup backup;
  private Recovery recovery;
  private MaintenanceController maintenance;
  private AuroraAdmin.Iface thrift;
  private CronJobManager cronJobManager;
  private QuotaManager quotaManager;
  private StateManager stateManager;
  private TaskIdGenerator taskIdGenerator;
  private UUIDGenerator uuidGenerator;
  private JobUpdateController jobUpdateController;
  private ReadOnlyScheduler.Iface readOnlyScheduler;

  @Before
  public void setUp() throws Exception {
    storageUtil = new StorageTestUtil(this);
    storageUtil.expectOperations();
    lockManager = createMock(LockManager.class);
    userValidator = createMock(CapabilityValidator.class);
    context = createMock(SessionContext.class);
    setUpValidationExpectations();
    backup = createMock(StorageBackup.class);
    recovery = createMock(Recovery.class);
    maintenance = createMock(MaintenanceController.class);
    cronJobManager = createMock(CronJobManager.class);
    quotaManager = createMock(QuotaManager.class);
    stateManager = createMock(StateManager.class);
    taskIdGenerator = createMock(TaskIdGenerator.class);
    uuidGenerator = createMock(UUIDGenerator.class);
    jobUpdateController = createMock(JobUpdateController.class);
    readOnlyScheduler = createMock(ReadOnlyScheduler.Iface.class);

    thrift = getResponseProxy(
        new SchedulerThriftInterface(
            storageUtil.storage,
            lockManager,
            userValidator,
            backup,
            recovery,
            cronJobManager,
            maintenance,
            quotaManager,
            stateManager,
            taskIdGenerator,
            uuidGenerator,
            jobUpdateController,
            readOnlyScheduler));
  }

  private static AuroraAdmin.Iface getResponseProxy(final AuroraAdmin.Iface realThrift) {
    // Capture all API method calls to validate response objects.
    Class<AuroraAdmin.Iface> thriftClass = AuroraAdmin.Iface.class;
    return (AuroraAdmin.Iface) Proxy.newProxyInstance(
        thriftClass.getClassLoader(),
        new Class<?>[] {thriftClass},
        new InvocationHandler() {
          @Override
          public Object invoke(Object o, Method method, Object[] args) throws Throwable {
            Response response;
            try {
              response = (Response) method.invoke(realThrift, args);
            } catch (InvocationTargetException e) {
              Throwables.propagateIfPossible(e.getTargetException(), TException.class);
              throw e;
            }
            assertTrue(response.isSetResponseCode());
            assertNotNull(response.getDetails());
            return response;
          }
        });
  }

  private void setUpValidationExpectations() throws Exception {
    expect(userValidator.toString(SESSION)).andReturn(USER).anyTimes();
    expect(context.getIdentity()).andReturn(USER).anyTimes();
  }

  @Test
  public void testCreateJobNoLock() throws Exception {
    // Validate key is populated during sanitizing.
    JobConfiguration jobConfig = makeProdJob();
    jobConfig.getTaskConfig().unsetJob();

    IJobConfiguration job = IJobConfiguration.build(makeProdJob());
    SanitizedConfiguration sanitized = SanitizedConfiguration.fromUnsanitized(job);
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expectNoCronJob();
    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectInstanceQuotaCheck(sanitized, ENOUGH_QUOTA);

    stateManager.insertPendingTasks(
        storageUtil.mutableStoreProvider,
        sanitized.getJobConfig().getTaskConfig(),
        sanitized.getInstanceIds());

    control.replay();

    assertOkResponse(thrift.createJob(jobConfig, null, SESSION));
  }

  @Test
  public void testCreateJobWithLock() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeProdJob());
    SanitizedConfiguration sanitized = SanitizedConfiguration.fromUnsanitized(job);
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expectNoCronJob();
    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectInstanceQuotaCheck(sanitized, ENOUGH_QUOTA);

    stateManager.insertPendingTasks(
        storageUtil.mutableStoreProvider,
        sanitized.getJobConfig().getTaskConfig(),
        sanitized.getInstanceIds());

    control.replay();

    assertOkResponse(thrift.createJob(job.newBuilder(), LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testCreateJobFailsForCron() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeProdJob().setCronSchedule(""));
    expectAuth(ROLE, true);

    control.replay();

    assertEquals(
        invalidResponse(NO_CRON),
        thrift.createJob(job.newBuilder(), LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testCreateJobFailsAuth() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeJob());
    expectAuth(ROLE, false);
    control.replay();

    assertResponse(
        AUTH_FAILED,
        thrift.createJob(job.newBuilder(), null, SESSION));
  }

  @Test
  public void testCreateJobFailsConfigCheck() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeJob(null));
    expectAuth(ROLE, true);
    control.replay();

    assertResponse(
        INVALID_REQUEST,
        thrift.createJob(job.newBuilder(), null, SESSION));
  }

  @Test
  public void testCreateJobFailsLockCheck() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeJob());
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    expectLastCall().andThrow(new LockException("Invalid lock"));

    control.replay();

    assertResponse(LOCK_ERROR, thrift.createJob(job.newBuilder(), LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testCreateJobFailsJobExists() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeJob());
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active(), buildScheduledTask());

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.createJob(job.newBuilder(), null, SESSION));
  }

  @Test
  public void testCreateJobFailsCronJobExists() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeJob());
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expectCronJob();

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.createJob(job.newBuilder(), null, SESSION));
  }

  @Test
  public void testCreateJobFailsInstanceCheck() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(
        makeJob(defaultTask(true), MAX_TASKS_PER_JOB.get() + 1));

    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expectNoCronJob();
    expect(quotaManager.checkInstanceAddition(
        anyObject(ITaskConfig.class),
        anyInt(),
        eq(storageUtil.mutableStoreProvider))).andReturn(ENOUGH_QUOTA);

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.createJob(job.newBuilder(), null, SESSION));
  }

  @Test
  public void testCreateJobFailsTaskIdLength() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeJob());
    SanitizedConfiguration sanitized = SanitizedConfiguration.fromUnsanitized(job);
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expectNoCronJob();
    expect(quotaManager.checkInstanceAddition(
        anyObject(ITaskConfig.class),
        anyInt(),
        eq(storageUtil.mutableStoreProvider))).andReturn(ENOUGH_QUOTA);

    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(Strings.repeat("a", MAX_TASK_ID_LENGTH + 1));

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.createJob(job.newBuilder(), null, SESSION));
  }

  @Test
  public void testCreateJobFailsQuotaCheck() throws Exception {
    IJobConfiguration job = IJobConfiguration.build(makeProdJob());
    SanitizedConfiguration sanitized = SanitizedConfiguration.fromUnsanitized(job);
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expectNoCronJob();
    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectInstanceQuotaCheck(sanitized, NOT_ENOUGH_QUOTA);

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.createJob(job.newBuilder(), null, SESSION));
  }

  private void assertMessageMatches(Response response, final String string) {
    // TODO(wfarner): This test coverage could be much better.  Circle back to apply more thorough
    // response contents testing throughout.
    assertTrue(Iterables.any(response.getDetails(), detail -> detail.getMessage().equals(string)));
  }

  @Test
  public void testCreateEmptyJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    JobConfiguration job =
        new JobConfiguration().setKey(JOB_KEY.newBuilder()).setOwner(ROLE_IDENTITY);
    assertResponse(INVALID_REQUEST, thrift.createJob(job, null, SESSION));
  }

  @Test
  public void testCreateJobFailsNoExecutorConfig() throws Exception {
    JobConfiguration job = makeJob();
    job.getTaskConfig().unsetExecutorConfig();
    expectAuth(ROLE, true);

    control.replay();

    Response response = thrift.createJob(job, LOCK.newBuilder(), SESSION);
    assertResponse(INVALID_REQUEST, response);
    // TODO(wfarner): Don't rely on a magic string here, reference a constant from the source.
    assertMessageMatches(response, "Configuration may not be null");
  }

  @Test
  public void testCreateHomogeneousJobNoInstances() throws Exception {
    JobConfiguration job = makeJob();
    job.unsetInstanceCount();
    expectAuth(ROLE, true);

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.createJob(job, null, SESSION));
  }

  @Test
  public void testCreateJobNegativeInstanceCount() throws Exception {
    JobConfiguration job = makeJob();
    job.setInstanceCount(0 - 1);
    expectAuth(ROLE, true);

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.createJob(job, null, SESSION));
  }

  @Test
  public void testCreateJobNoResources() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TaskConfig task = productionTask();
    task.unsetNumCpus();
    task.unsetRamMb();
    task.unsetDiskMb();
    assertResponse(INVALID_REQUEST, thrift.createJob(makeJob(task), null, SESSION));
  }

  @Test
  public void testCreateJobBadCpu() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TaskConfig task = productionTask().setNumCpus(0.0);
    assertResponse(INVALID_REQUEST, thrift.createJob(makeJob(task), null, SESSION));
  }

  @Test
  public void testCreateJobBadRam() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TaskConfig task = productionTask().setRamMb(-123);
    assertResponse(INVALID_REQUEST, thrift.createJob(makeJob(task), null, SESSION));
  }

  @Test
  public void testCreateJobBadDisk() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TaskConfig task = productionTask().setDiskMb(0);
    assertResponse(INVALID_REQUEST, thrift.createJob(makeJob(task), null, SESSION));
  }

  @Test
  public void testCreateJobPopulateDefaults() throws Exception {
    TaskConfig task = new TaskConfig()
        .setContactEmail("testing@twitter.com")
        .setExecutorConfig(new ExecutorConfig("aurora", "config"))  // Arbitrary opaque data.
        .setNumCpus(1.0)
        .setRamMb(1024)
        .setDiskMb(1024)
        .setIsService(true)
        .setProduction(true)
        .setOwner(ROLE_IDENTITY)
        .setEnvironment("devel")
        .setContainer(Container.mesos(new MesosContainer()))
        .setJobName(JOB_NAME);
    JobConfiguration job = makeJob(task);

    expectAuth(ROLE, true);

    JobConfiguration sanitized = job.deepCopy();
    sanitized.getTaskConfig()
        .setJob(JOB_KEY.newBuilder())
        .setNumCpus(1.0)
        .setPriority(0)
        .setRamMb(1024)
        .setDiskMb(1024)
        .setIsService(true)
        .setProduction(true)
        .setRequestedPorts(ImmutableSet.of())
        .setTaskLinks(ImmutableMap.of())
        .setConstraints(ImmutableSet.of())
        .setMaxTaskFailures(1)
        .setEnvironment("devel");

    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expectNoCronJob();
    expect(taskIdGenerator.generate(ITaskConfig.build(sanitized.getTaskConfig()), 1))
        .andReturn(TASK_ID);
    expectInstanceQuotaCheck(ITaskConfig.build(sanitized.getTaskConfig()), ENOUGH_QUOTA);
    stateManager.insertPendingTasks(
        storageUtil.mutableStoreProvider,
        ITaskConfig.build(sanitized.getTaskConfig()),
        ImmutableSet.of(0));

    control.replay();

    assertOkResponse(thrift.createJob(job, null, SESSION));
  }

  @Test
  public void testCreateUnauthorizedDedicatedJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TaskConfig task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(ImmutableSet.of("mesos")));
    assertResponse(INVALID_REQUEST, thrift.createJob(makeJob(task), null, SESSION));
  }

  @Test
  public void testLimitConstraintForDedicatedJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TaskConfig task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(1));
    assertResponse(INVALID_REQUEST, thrift.createJob(makeJob(task), null, SESSION));
  }

  @Test
  public void testMultipleValueConstraintForDedicatedJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TaskConfig task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(ImmutableSet.of("mesos", "test")));
    assertResponse(INVALID_REQUEST, thrift.createJob(makeJob(task), null, SESSION));
  }

  private IScheduledTask buildTaskForJobUpdate(int instanceId) {
    return buildTaskForJobUpdate(instanceId, "data");
  }

  private IScheduledTask buildTaskForJobUpdate(int instanceId, String executorData) {
    return IScheduledTask.build(new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setInstanceId(instanceId)
            .setTask(ConfigurationManager.applyDefaultsIfUnset(populatedTask()
                .setRamMb(5)
                .setIsService(true)
                .setExecutorConfig(new ExecutorConfig().setData(executorData))))));
  }

  private IScheduledTask buildScheduledTask() {
    return buildScheduledTask(JOB_NAME, TASK_ID);
  }

  private static IScheduledTask buildScheduledTask(String jobName, String taskId) {
    return IScheduledTask.build(new ScheduledTask()
        .setAssignedTask(new AssignedTask()
            .setTaskId(taskId)
            .setTask(new TaskConfig()
                .setJob(JOB_KEY.newBuilder().setName(jobName))
                .setOwner(ROLE_IDENTITY)
                .setEnvironment("devel")
                .setJobName(jobName))));
  }

  private void expectTransitionsToKilling() {
    expect(stateManager.changeState(
        storageUtil.mutableStoreProvider,
        TASK_ID,
        Optional.absent(),
        ScheduleStatus.KILLING,
        killedByMessage(USER))).andReturn(StateChangeResult.SUCCESS);
  }

  @Test
  public void testUserKillTasks() throws Exception {
    Query.Builder query = Query.unscoped().byJob(JOB_KEY).active();
    expectAuth(ROOT, false);
    expectAuth(ROLE, true);
    storageUtil.expectTaskFetch(query, buildScheduledTask());
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    expectTransitionsToKilling();

    control.replay();

    assertOkResponse(thrift.killTasks(query.get(), LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testAdminKillTasks() throws Exception {
    Query.Builder query = Query.unscoped().byJob(JOB_KEY).active();
    expectAuth(ROOT, true);
    storageUtil.expectTaskFetch(query, buildScheduledTask());
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expectTransitionsToKilling();

    control.replay();

    assertOkResponse(thrift.killTasks(query.get(), null, SESSION));
  }

  @Test
  public void testKillByJobName() throws Exception {
    TaskQuery query = new TaskQuery().setJobName("job");
    expectAuth(ROOT, true);
    storageUtil.expectTaskFetch(Query.arbitrary(query).active(), buildScheduledTask());
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expectTransitionsToKilling();

    control.replay();

    assertEquals(okEmptyResponse(), thrift.killTasks(query, null, SESSION));
  }

  @Test
  public void testKillQueryActive() throws Exception {
    Query.Builder query = Query.unscoped().byJob(JOB_KEY);
    expectAuth(ROOT, true);
    storageUtil.expectTaskFetch(query.active(), buildScheduledTask());
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expectTransitionsToKilling();

    control.replay();

    assertOkResponse(thrift.killTasks(query.get(), null, SESSION));
  }

  @Test
  public void testKillTasksLockCheckFailed() throws Exception {
    Query.Builder query = Query.unscoped().byJob(JOB_KEY).active();
    IScheduledTask task2 = buildScheduledTask("job_bar", TASK_ID);
    ILockKey key2 = ILockKey.build(LockKey.job(
        JobKeys.from(ROLE, "devel", "job_bar").newBuilder()));
    expectAuth(ROOT, false);
    expectAuth(ROLE, true);
    storageUtil.expectTaskFetch(query, buildScheduledTask(), task2);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    lockManager.validateIfLocked(key2, java.util.Optional.of(LOCK));
    expectLastCall().andThrow(new LockException("Failed lock check."));

    control.replay();

    assertResponse(LOCK_ERROR, thrift.killTasks(query.get(), LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testKillByTaskId() throws Exception {
    // A non-admin user may kill their own tasks when specified by task IDs.
    Query.Builder query = Query.taskScoped("taskid");
    expectAuth(ROOT, false);
    expectAuth(ImmutableSet.of(ROLE), true);
    // This query happens twice - once for authentication (without consistency) and once again
    // to perform the state change (within a write transaction).
    storageUtil.expectTaskFetch(query.active(), buildScheduledTask()).times(2);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expectTransitionsToKilling();

    control.replay();

    assertOkResponse(thrift.killTasks(query.get(), null, SESSION));
  }

  @Test
  public void testKillByStatus() throws Exception {
    // A non-admin user may not kill arbitrary tasks.
    Query.Builder query = Query.statusScoped(ScheduleStatus.RUNNING);
    expectAuth(ROOT, false);

    control.replay();

    assertResponse(AUTH_FAILED, thrift.killTasks(query.get(), null, SESSION));
  }

  @Test
  public void testKillWithRoleSpecs() throws Exception {
    // The query performed here is somewhat nonsensical, since we would not have any tasks owned by
    // multiple roles.  However, that behavior is defined in the storage system.
    Query.Builder query = Query.arbitrary(new TaskQuery()
        .setRole("a")
        .setJobKeys(ImmutableSet.of(JobKeys.from("b", "devel", "job").newBuilder())));

    expectAuth(ROOT, false);
    expectAuth(ImmutableSet.of("a", "b"), true);
    storageUtil.expectTaskFetch(query.active(), buildScheduledTask());
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expectTransitionsToKilling();

    control.replay();

    assertOkResponse(thrift.killTasks(query.get(), null, SESSION));
  }

  @Test
  public void testKillTasksAuthFailure() throws Exception {
    Query.Builder query = Query.unscoped().byJob(JOB_KEY).active();
    expectAuth(ROOT, false);
    expectAuth(ROLE, false);

    control.replay();

    assertResponse(AUTH_FAILED, thrift.killTasks(query.get(), null, SESSION));
  }

  @Test
  public void testKillTasksInvalidJobName() throws Exception {
    TaskQuery query = new TaskQuery()
        .setOwner(ROLE_IDENTITY)
        .setJobName("");

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.killTasks(query, null, SESSION));
  }

  @Test
  public void testKillNonExistentTasks() throws Exception {
    Query.Builder query = Query.unscoped().byJob(JOB_KEY).active();
    expectAuth(ROOT, true);
    storageUtil.expectTaskFetch(query);

    control.replay();

    Response response = thrift.killTasks(query.get(), null, SESSION);
    assertOkResponse(response);
    assertMessageMatches(response, SchedulerThriftInterface.NO_TASKS_TO_KILL_MESSAGE);
  }

  @Test
  public void testKillAuthenticatesQueryRole() throws Exception {
    expectAuth(ROOT, false);
    expectAuth(ImmutableSet.of("foo"), true);

    Query.Builder query = Query.roleScoped("foo").active();

    storageUtil.expectTaskFetch(query, buildScheduledTask());
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expectTransitionsToKilling();

    control.replay();

    assertOkResponse(thrift.killTasks(query.get(), null, SESSION));
  }

  @Test
  public void testKillCronAuthenticatesQueryJobKeyRole() throws Exception {
    expectAuth(ROOT, false);
    IJobKey key = JobKeys.from("role", "env", "job");

    Query.Builder query = Query.arbitrary(new TaskQuery().setJobKeys(
        ImmutableSet.of(key.newBuilder())));

    storageUtil.expectTaskFetch(query.active());
    expectAuth(ImmutableSet.of("role"), true);

    control.replay();
    assertOkResponse(thrift.killTasks(query.get(), null, SESSION));
  }

  @Test
  public void testSetQuota() throws Exception {
    ResourceAggregate resourceAggregate = new ResourceAggregate()
        .setNumCpus(10)
        .setDiskMb(100)
        .setRamMb(200);
    quotaManager.saveQuota(
        ROLE,
        IResourceAggregate.build(resourceAggregate),
        storageUtil.mutableStoreProvider);

    control.replay();

    assertOkResponse(thrift.setQuota(ROLE, resourceAggregate, SESSION));
  }

  @Test
  public void testSetQuotaFails() throws Exception {
    ResourceAggregate resourceAggregate = new ResourceAggregate()
        .setNumCpus(10)
        .setDiskMb(100)
        .setRamMb(200);
    quotaManager.saveQuota(
        ROLE,
        IResourceAggregate.build(resourceAggregate),
        storageUtil.mutableStoreProvider);

    expectLastCall().andThrow(new QuotaManager.QuotaException("fail"));

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.setQuota(ROLE, resourceAggregate, SESSION));
  }

  @Test
  public void testForceTaskState() throws Exception {
    ScheduleStatus status = ScheduleStatus.FAILED;

    expect(stateManager.changeState(
        storageUtil.mutableStoreProvider,
        TASK_ID,
        Optional.absent(),
        ScheduleStatus.FAILED,
        Optional.of(transitionMessage(USER).get()))).andReturn(StateChangeResult.SUCCESS);

    expectAuth(ROOT, true);
    expectAuth(ROOT, false);

    control.replay();

    assertOkResponse(thrift.forceTaskState(TASK_ID, status, SESSION));
    assertEquals(
        response(AUTH_FAILED, Optional.absent(), AUTH_DENIED_MESSAGE),
        thrift.forceTaskState(TASK_ID, status, SESSION));
  }

  @Test
  public void testBackupControls() throws Exception {
    backup.backupNow();

    Set<String> backups = ImmutableSet.of("a", "b");
    expect(recovery.listBackups()).andReturn(backups);

    String backupId = "backup";
    recovery.stage(backupId);

    Query.Builder query = Query.taskScoped("taskId");
    Set<IScheduledTask> queryResult = ImmutableSet.of(
        IScheduledTask.build(new ScheduledTask().setStatus(ScheduleStatus.RUNNING)));
    expect(recovery.query(query)).andReturn(queryResult);

    recovery.deleteTasks(query);

    recovery.commit();

    recovery.unload();

    control.replay();

    assertEquals(okEmptyResponse(), thrift.performBackup(SESSION));

    assertEquals(
        okResponse(Result.listBackupsResult(new ListBackupsResult().setBackups(backups))),
        thrift.listBackups(SESSION));

    assertEquals(okEmptyResponse(), thrift.stageRecovery(backupId, SESSION));

    assertEquals(
        okResponse(Result.queryRecoveryResult(
            new QueryRecoveryResult().setTasks(IScheduledTask.toBuildersSet(queryResult)))),
        thrift.queryRecovery(query.get(), SESSION));

    assertEquals(okEmptyResponse(), thrift.deleteRecoveryTasks(query.get(), SESSION));

    assertEquals(okEmptyResponse(), thrift.commitRecovery(SESSION));

    assertEquals(okEmptyResponse(), thrift.unloadRecovery(SESSION));
  }

  @Test
  public void testRecoveryException() throws Exception {
    Throwable recoveryException = new RecoveryException("Injected");

    String backupId = "backup";
    recovery.stage(backupId);
    expectLastCall().andThrow(recoveryException);

    control.replay();

    try {
      thrift.stageRecovery(backupId, SESSION);
      fail("No recovery exception thrown.");
    } catch (RecoveryException e) {
      assertEquals(recoveryException.getMessage(), e.getMessage());
    }
  }

  @Test
  public void testForceTaskStateAuthFailure() throws Exception {
    expectAuth(ROOT, false);

    control.replay();

    assertResponse(AUTH_FAILED, thrift.forceTaskState("task", ScheduleStatus.FAILED, SESSION));
  }

  @Test
  public void testRestartShards() throws Exception {
    Set<Integer> shards = ImmutableSet.of(0);

    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    storageUtil.expectTaskFetch(
        Query.instanceScoped(JOB_KEY, shards).active(),
        buildScheduledTask());

    expect(stateManager.changeState(
        storageUtil.mutableStoreProvider,
        TASK_ID,
        Optional.absent(),
        ScheduleStatus.RESTARTING,
        restartedByMessage(USER))).andReturn(StateChangeResult.SUCCESS);

    control.replay();

    assertOkResponse(
        thrift.restartShards(JOB_KEY.newBuilder(), shards, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testRestartShardsAuthFailure() throws Exception {
    expectAuth(ROLE, false);

    control.replay();

    assertResponse(
        AUTH_FAILED,
        thrift.restartShards(JOB_KEY.newBuilder(), ImmutableSet.of(0), null, SESSION));
  }

  @Test
  public void testRestartShardsLockCheckFails() throws Exception {
    Set<Integer> shards = ImmutableSet.of(1, 6);

    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    expectLastCall().andThrow(new LockException("test"));

    control.replay();

    assertResponse(
        LOCK_ERROR,
        thrift.restartShards(JOB_KEY.newBuilder(), shards, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testRestartShardsNotFoundTasksFailure() throws Exception {
    Set<Integer> shards = ImmutableSet.of(1, 6);

    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    storageUtil.expectTaskFetch(Query.instanceScoped(JOB_KEY, shards).active());

    control.replay();

    assertResponse(
        INVALID_REQUEST,
        thrift.restartShards(JOB_KEY.newBuilder(), shards, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testReplaceCronTemplate() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    SanitizedConfiguration sanitized =
        SanitizedConfiguration.fromUnsanitized(IJobConfiguration.build(CRON_JOB));

    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectCronQuotaCheck(sanitized.getJobConfig(), ENOUGH_QUOTA);
    cronJobManager.updateJob(anyObject(SanitizedCronJob.class));
    control.replay();

    // Validate key is populated during sanitizing.
    JobConfiguration jobConfig = CRON_JOB;
    jobConfig.getTaskConfig().unsetJob();
    assertOkResponse(thrift.replaceCronTemplate(jobConfig, null, SESSION));
  }

  @Test
  public void testReplaceCronTemplateFailedAuth() throws Exception {
    expectAuth(ROLE, false);

    control.replay();

    assertResponse(AUTH_FAILED, thrift.replaceCronTemplate(CRON_JOB, null, SESSION));
  }

  @Test
  public void testReplaceCronTemplateFailedLockValidation() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expectLastCall().andThrow(new LockException("Failed lock."));
    control.replay();

    assertResponse(LOCK_ERROR, thrift.replaceCronTemplate(CRON_JOB, null, SESSION));
  }

  @Test
  public void testReplaceCronTemplateDoesNotExist() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    SanitizedConfiguration sanitized =
        SanitizedConfiguration.fromUnsanitized(IJobConfiguration.build(CRON_JOB));

    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectCronQuotaCheck(sanitized.getJobConfig(), ENOUGH_QUOTA);
    cronJobManager.updateJob(anyObject(SanitizedCronJob.class));
    expectLastCall().andThrow(new CronException("Nope"));

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.replaceCronTemplate(CRON_JOB, null, SESSION));
  }

  @Test
  public void testStartCronJob() throws Exception {
    expectAuth(ROLE, true);
    cronJobManager.startJobNow(JOB_KEY);
    control.replay();
    assertResponse(OK, thrift.startCronJob(JOB_KEY.newBuilder(), SESSION));
  }

  @Test
  public void testStartCronJobFailsAuth() throws Exception {
    expectAuth(ROLE, false);
    control.replay();
    assertResponse(AUTH_FAILED, thrift.startCronJob(JOB_KEY.newBuilder(), SESSION));
  }

  @Test
  public void testStartCronJobFailsInCronManager() throws Exception {
    expectAuth(ROLE, true);
    cronJobManager.startJobNow(JOB_KEY);
    expectLastCall().andThrow(new CronException("failed"));
    control.replay();
    assertResponse(INVALID_REQUEST, thrift.startCronJob(JOB_KEY.newBuilder(), SESSION));
  }

  @Test
  public void testScheduleCronCreatesJob() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    SanitizedConfiguration sanitized =
        SanitizedConfiguration.fromUnsanitized(IJobConfiguration.build(CRON_JOB));

    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectCronQuotaCheck(sanitized.getJobConfig(), ENOUGH_QUOTA);

    expectNoCronJob().times(2);
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    cronJobManager.createJob(SanitizedCronJob.from(sanitized));
    control.replay();
    assertResponse(OK, thrift.scheduleCronJob(CRON_JOB, null, SESSION));
  }

  @Test
  public void testScheduleCronFailsCreationDueToExistingNonCron() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    SanitizedConfiguration sanitized =
        SanitizedConfiguration.fromUnsanitized(IJobConfiguration.build(CRON_JOB));

    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectCronQuotaCheck(sanitized.getJobConfig(), ENOUGH_QUOTA);

    expectNoCronJob();
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active(), buildScheduledTask());
    control.replay();
    assertEquals(
        invalidResponse(jobAlreadyExistsMessage(JOB_KEY)),
        thrift.scheduleCronJob(CRON_JOB, null, SESSION));
  }

  @Test
  public void testScheduleCronUpdatesJob() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    SanitizedConfiguration sanitized =
        SanitizedConfiguration.fromUnsanitized(IJobConfiguration.build(CRON_JOB));

    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectCronQuotaCheck(sanitized.getJobConfig(), ENOUGH_QUOTA);

    expectCronJob();
    cronJobManager.updateJob(SanitizedCronJob.from(sanitized));
    control.replay();

    // Validate key is populated during sanitizing.
    JobConfiguration jobConfig = CRON_JOB;
    jobConfig.getTaskConfig().unsetJob();
    assertResponse(OK, thrift.scheduleCronJob(jobConfig, null, SESSION));
  }

  @Test
  public void testUpdateScheduledCronJobFailedAuth() throws Exception {
    expectAuth(ROLE, false);
    control.replay();
    assertResponse(AUTH_FAILED, thrift.scheduleCronJob(CRON_JOB, null, SESSION));
  }

  @Test
  public void testScheduleCronJobFailedTaskConfigValidation() throws Exception {
    expectAuth(ROLE, true);
    control.replay();
    IJobConfiguration job = IJobConfiguration.build(makeJob(null));
    assertResponse(
        INVALID_REQUEST,
        thrift.scheduleCronJob(job.newBuilder(), null, SESSION));
  }

  @Test
  public void testScheduleCronJobFailsLockValidation() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    expectLastCall().andThrow(new LockException("Failed lock"));
    control.replay();
    assertResponse(LOCK_ERROR, thrift.scheduleCronJob(CRON_JOB, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testScheduleCronJobFailsWithNoCronSchedule() throws Exception {
    expectAuth(ROLE, true);
    control.replay();

    assertEquals(
        invalidResponse(noCronScheduleMessage(JOB_KEY)),
        thrift.scheduleCronJob(makeJob(), null, SESSION));
  }

  @Test
  public void testScheduleCronFailsQuotaCheck() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    SanitizedConfiguration sanitized =
        SanitizedConfiguration.fromUnsanitized(IJobConfiguration.build(CRON_JOB));

    expect(taskIdGenerator.generate(sanitized.getJobConfig().getTaskConfig(), 1))
        .andReturn(TASK_ID);
    expectCronQuotaCheck(sanitized.getJobConfig(), NOT_ENOUGH_QUOTA);

    control.replay();
    assertResponse(INVALID_REQUEST, thrift.scheduleCronJob(CRON_JOB, null, SESSION));
  }

  @Test
  public void testDescheduleCronJob() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expect(cronJobManager.deleteJob(JOB_KEY)).andReturn(true);
    control.replay();
    assertResponse(OK, thrift.descheduleCronJob(CRON_JOB.getKey(), null, SESSION));
  }

  @Test
  public void testDescheduleCronJobFailsAuth() throws Exception {
    expectAuth(ROLE, false);
    control.replay();
    assertResponse(AUTH_FAILED,
        thrift.descheduleCronJob(CRON_JOB.getKey(), null, SESSION));
  }

  @Test
  public void testDescheduleCronJobFailsLockValidation() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expectLastCall().andThrow(new LockException("Failed lock"));
    control.replay();
    assertResponse(LOCK_ERROR, thrift.descheduleCronJob(CRON_JOB.getKey(), null, SESSION));
  }

  @Test
  public void testDescheduleNotACron() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    expect(cronJobManager.deleteJob(JOB_KEY)).andReturn(false);
    control.replay();

    assertEquals(
        invalidResponse(notScheduledCronMessage(JOB_KEY)),
        thrift.descheduleCronJob(JOB_KEY.newBuilder(), null, SESSION));
  }

  @Test
  public void testRewriteShardTaskMissing() throws Exception {
    InstanceKey instance = new InstanceKey(JobKeys.from("foo", "bar", "baz").newBuilder(), 0);

    storageUtil.expectTaskFetch(
        Query.instanceScoped(IJobKey.build(instance.getJobKey()), instance.getInstanceId())
            .active());

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.instanceRewrite(
            new InstanceConfigRewrite(instance, productionTask(), productionTask()))));
    assertResponse(WARNING, thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testRewriteNoCommands() throws Exception {
    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(ImmutableList.of());
    assertResponse(INVALID_REQUEST, thrift.rewriteConfigs(request, SESSION));
  }

  @Test(expected = RuntimeException.class)
  public void testRewriteInvalidJob() throws Exception {
    control.replay();

    IJobConfiguration job = IJobConfiguration.build(makeJob());
    thrift.rewriteConfigs(
        new RewriteConfigsRequest(
            ImmutableList.of(ConfigRewrite.jobRewrite(
                new JobConfigRewrite(job.newBuilder(), job.newBuilder().setTaskConfig(null))))),
        SESSION);
  }

  @Test
  public void testRewriteChangeJobKey() throws Exception {
    control.replay();

    IJobConfiguration job = IJobConfiguration.build(makeJob());
    JobKey rewrittenJobKey = JobKeys.from("a", "b", "c").newBuilder();
    Identity rewrittenIdentity = new Identity(rewrittenJobKey.getRole(), "steve");
    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.jobRewrite(new JobConfigRewrite(
            job.newBuilder(),
            job.newBuilder()
                .setTaskConfig(job.getTaskConfig().newBuilder().setJob(rewrittenJobKey)
                    .setOwner(rewrittenIdentity))
                .setOwner(rewrittenIdentity)
                .setKey(rewrittenJobKey)))));
    assertResponse(WARNING, thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testRewriteShardCasMismatch() throws Exception {
    TaskConfig storedConfig = productionTask();
    TaskConfig modifiedConfig =
        storedConfig.deepCopy().setExecutorConfig(new ExecutorConfig("aurora", "rewritten"));
    IScheduledTask storedTask = IScheduledTask.build(
        new ScheduledTask().setAssignedTask(new AssignedTask().setTask(storedConfig)));
    InstanceKey instance = new InstanceKey(
        JobKeys.from(
            storedConfig.getOwner().getRole(),
            storedConfig.getEnvironment(),
            storedConfig.getJobName()).newBuilder(),
        0);

    storageUtil.expectTaskFetch(
        Query.instanceScoped(IInstanceKey.build(instance)).active(), storedTask);

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.instanceRewrite(
            new InstanceConfigRewrite(instance, modifiedConfig, modifiedConfig))));
    assertResponse(WARNING, thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testRewriteInstance() throws Exception {
    TaskConfig storedConfig = productionTask();
    ITaskConfig modifiedConfig = ITaskConfig.build(
        storedConfig.deepCopy().setExecutorConfig(new ExecutorConfig("aurora", "rewritten")));
    String taskId = "task_id";
    IScheduledTask storedTask = IScheduledTask.build(new ScheduledTask().setAssignedTask(
        new AssignedTask()
            .setTaskId(taskId)
            .setTask(storedConfig)));
    InstanceKey instanceKey = new InstanceKey(
        JobKeys.from(
            storedConfig.getOwner().getRole(),
            storedConfig.getEnvironment(),
            storedConfig.getJobName()).newBuilder(),
        0);

    storageUtil.expectTaskFetch(
        Query.instanceScoped(IInstanceKey.build(instanceKey)).active(), storedTask);
    expect(storageUtil.taskStore.unsafeModifyInPlace(
        taskId,
        ITaskConfig.build(ConfigurationManager.applyDefaultsIfUnset(modifiedConfig.newBuilder()))))
        .andReturn(true);

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.instanceRewrite(
            new InstanceConfigRewrite(instanceKey, storedConfig, modifiedConfig.newBuilder()))));
    assertOkResponse(thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testRewriteInstanceUnchanged() throws Exception {
    TaskConfig config = productionTask();
    String taskId = "task_id";
    IScheduledTask task = IScheduledTask.build(new ScheduledTask().setAssignedTask(
        new AssignedTask()
            .setTaskId(taskId)
            .setTask(config)));
    InstanceKey instanceKey = new InstanceKey(
        JobKeys.from(
            config.getOwner().getRole(),
            config.getEnvironment(),
            config.getJobName()).newBuilder(),
        0);

    storageUtil.expectTaskFetch(
        Query.instanceScoped(IInstanceKey.build(instanceKey)).active(), task);
    expect(storageUtil.taskStore.unsafeModifyInPlace(
        taskId,
        ITaskConfig.build(ConfigurationManager.applyDefaultsIfUnset(config.deepCopy()))))
        .andReturn(false);

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.instanceRewrite(
            new InstanceConfigRewrite(instanceKey, config, config))));
    assertResponse(WARNING, thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testRewriteJobCasMismatch() throws Exception {
    JobConfiguration oldJob = makeJob(productionTask());
    JobConfiguration newJob = oldJob.deepCopy();
    newJob.getTaskConfig().setExecutorConfig(new ExecutorConfig("aurora", "rewritten"));
    expect(storageUtil.jobStore.fetchJob(IJobKey.build(oldJob.getKey())))
        .andReturn(Optional.of(IJobConfiguration.build(oldJob)));

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.jobRewrite(
            new JobConfigRewrite(newJob, newJob))));
    assertResponse(WARNING, thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testRewriteJobNotFound() throws Exception {
    JobConfiguration oldJob = makeJob(productionTask());
    JobConfiguration newJob = oldJob.deepCopy();
    newJob.getTaskConfig().setExecutorConfig(new ExecutorConfig("aurora", "rewritten"));
    expect(storageUtil.jobStore.fetchJob(IJobKey.build(oldJob.getKey())))
        .andReturn(Optional.absent());

    control.replay();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.jobRewrite(
            new JobConfigRewrite(oldJob, newJob))));
    assertResponse(WARNING, thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testRewriteJob() throws Exception {
    JobConfiguration oldJob = makeJob(productionTask());
    JobConfiguration newJob = oldJob.deepCopy();
    newJob.getTaskConfig().setExecutorConfig(new ExecutorConfig("aurora", "rewritten"));
    expect(storageUtil.jobStore.fetchJob(IJobKey.build(oldJob.getKey())))
        .andReturn(Optional.of(IJobConfiguration.build(oldJob)));
    storageUtil.jobStore.saveAcceptedJob(
        ConfigurationManager.validateAndPopulate(IJobConfiguration.build(newJob)));

    control.replay();

    // Validate key is populated during sanitizing.
    JobConfiguration requestConfig = oldJob.deepCopy();
    requestConfig.getTaskConfig().unsetJob();

    RewriteConfigsRequest request = new RewriteConfigsRequest(
        ImmutableList.of(ConfigRewrite.jobRewrite(
            new JobConfigRewrite(oldJob, newJob))));
    assertOkResponse(thrift.rewriteConfigs(request, SESSION));
  }

  @Test
  public void testUnauthorizedDedicatedJob() throws Exception {
    expectAuth(ROLE, true);

    control.replay();

    TaskConfig task = nonProductionTask();
    task.addToConstraints(dedicatedConstraint(ImmutableSet.of("mesos")));
    assertResponse(INVALID_REQUEST, thrift.createJob(makeJob(task), null, SESSION));
  }

  @Test
  public void testHostMaintenance() throws Exception {
    Set<String> hostnames = ImmutableSet.of("a");
    Set<HostStatus> none = ImmutableSet.of(new HostStatus("a", NONE));
    Set<HostStatus> scheduled = ImmutableSet.of(new HostStatus("a", SCHEDULED));
    Set<HostStatus> draining = ImmutableSet.of(new HostStatus("a", DRAINING));
    Set<HostStatus> drained = ImmutableSet.of(new HostStatus("a", DRAINING));
    expect(maintenance.getStatus(hostnames)).andReturn(none);
    expect(maintenance.startMaintenance(hostnames)).andReturn(scheduled);
    expect(maintenance.drain(hostnames)).andReturn(draining);
    expect(maintenance.getStatus(hostnames)).andReturn(draining);
    expect(maintenance.getStatus(hostnames)).andReturn(drained);
    expect(maintenance.endMaintenance(hostnames)).andReturn(none);

    control.replay();

    Hosts hosts = new Hosts(hostnames);

    assertEquals(
        none,
        thrift.maintenanceStatus(hosts, SESSION).getResult().getMaintenanceStatusResult()
            .getStatuses());
    assertEquals(
        scheduled,
        thrift.startMaintenance(hosts, SESSION).getResult().getStartMaintenanceResult()
            .getStatuses());
    assertEquals(
        draining,
        thrift.drainHosts(hosts, SESSION).getResult().getDrainHostsResult().getStatuses());
    assertEquals(
        draining,
        thrift.maintenanceStatus(hosts, SESSION).getResult().getMaintenanceStatusResult()
            .getStatuses());
    assertEquals(
        drained,
        thrift.maintenanceStatus(hosts, SESSION).getResult().getMaintenanceStatusResult()
            .getStatuses());
    assertEquals(
        none,
        thrift.endMaintenance(hosts, SESSION).getResult().getEndMaintenanceResult().getStatuses());
  }

  private static Response okEmptyResponse() {
    return response(OK, Optional.absent());
  }

  private static Response invalidResponse(String message) {
    return Responses.empty()
        .setResponseCode(INVALID_REQUEST)
        .setDetails(ImmutableList.of(new ResponseDetail(message)));
  }

  @Test
  public void testSnapshot() throws Exception {
    storageUtil.storage.snapshot();
    expectLastCall();

    storageUtil.storage.snapshot();
    expectLastCall().andThrow(new StorageException("mock error!"));

    control.replay();

    assertOkResponse(thrift.snapshot(SESSION));

    try {
      thrift.snapshot(SESSION);
      fail("No StorageException thrown.");
    } catch (StorageException e) {
      // Expected.
    }
  }

  private static AddInstancesConfig createInstanceConfig(TaskConfig task) {
    return new AddInstancesConfig()
        .setTaskConfig(task)
        .setInstanceIds(ImmutableSet.of(0))
        .setKey(JOB_KEY.newBuilder());
  }

  @Test
  public void testAddInstances() throws Exception {
    ITaskConfig populatedTask = ITaskConfig.build(populatedTask());
    expectAuth(ROLE, true);
    expectNoCronJob();
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expect(taskIdGenerator.generate(populatedTask, 1))
        .andReturn(TASK_ID);
    expectInstanceQuotaCheck(populatedTask, ENOUGH_QUOTA);
    stateManager.insertPendingTasks(
        storageUtil.mutableStoreProvider,
        populatedTask,
        ImmutableSet.of(0));

    control.replay();

    // Validate key is populated during sanitizing.
    AddInstancesConfig config = createInstanceConfig(populatedTask.newBuilder());
    config.getTaskConfig().unsetJob();
    assertOkResponse(thrift.addInstances(config, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testAddInstancesWithNullLock() throws Exception {
    ITaskConfig populatedTask = ITaskConfig.build(populatedTask());
    AddInstancesConfig config = createInstanceConfig(populatedTask.newBuilder());
    expectAuth(ROLE, true);
    expectNoCronJob();
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.empty());
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expect(taskIdGenerator.generate(populatedTask, 1))
        .andReturn(TASK_ID);
    expectInstanceQuotaCheck(populatedTask, ENOUGH_QUOTA);
    stateManager.insertPendingTasks(
        storageUtil.mutableStoreProvider,
        populatedTask,
        ImmutableSet.of(0));

    control.replay();

    assertOkResponse(thrift.addInstances(config, null, SESSION));
  }

  @Test
  public void testAddInstancesFailsAuth() throws Exception {
    AddInstancesConfig config = createInstanceConfig(defaultTask(true));
    expectAuth(ROLE, false);

    control.replay();

    assertResponse(AUTH_FAILED, thrift.addInstances(config, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testAddInstancesFailsConfigCheck() throws Exception {
    AddInstancesConfig config = createInstanceConfig(defaultTask(true).setJobName(null));
    expectAuth(ROLE, true);

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.addInstances(config, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testAddInstancesFailsCronJob() throws Exception {
    AddInstancesConfig config = createInstanceConfig(defaultTask(true));
    expectAuth(ROLE, true);
    expectCronJob();

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.addInstances(config, LOCK.newBuilder(), SESSION));
  }

  @Test(expected = StorageException.class)
  public void testAddInstancesFailsWithNonTransient() throws Exception {
    AddInstancesConfig config = createInstanceConfig(defaultTask(true));
    expectAuth(ROLE, true);
    expect(storageUtil.jobStore.fetchJob(JOB_KEY)).andThrow(new StorageException("no retry"));

    control.replay();

    thrift.addInstances(config, LOCK.newBuilder(), SESSION);
  }

  @Test
  public void testAddInstancesLockCheckFails() throws Exception {
    AddInstancesConfig config = createInstanceConfig(defaultTask(true));
    expectAuth(ROLE, true);
    expectNoCronJob();
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    expectLastCall().andThrow(new LockException("Failed lock check."));

    control.replay();

    assertResponse(LOCK_ERROR, thrift.addInstances(config, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testAddInstancesFailsTaskIdLength() throws Exception {
    ITaskConfig populatedTask = ITaskConfig.build(populatedTask());
    AddInstancesConfig config = createInstanceConfig(populatedTask.newBuilder());
    expectAuth(ROLE, true);
    expectNoCronJob();
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expect(quotaManager.checkInstanceAddition(
        anyObject(ITaskConfig.class),
        anyInt(),
        eq(storageUtil.mutableStoreProvider))).andReturn(ENOUGH_QUOTA);
    expect(taskIdGenerator.generate(populatedTask, 1))
        .andReturn(Strings.repeat("a", MAX_TASK_ID_LENGTH + 1));

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.addInstances(config, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testAddInstancesFailsQuotaCheck() throws Exception {
    ITaskConfig populatedTask = ITaskConfig.build(populatedTask());
    AddInstancesConfig config = createInstanceConfig(populatedTask.newBuilder());
    expectAuth(ROLE, true);
    expectNoCronJob();
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expect(taskIdGenerator.generate(populatedTask, 1))
        .andReturn(TASK_ID);
    expectInstanceQuotaCheck(populatedTask, NOT_ENOUGH_QUOTA);

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.addInstances(config, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testAddInstancesInstanceCollisionFailure() throws Exception {
    ITaskConfig populatedTask = ITaskConfig.build(populatedTask());
    AddInstancesConfig config = createInstanceConfig(populatedTask.newBuilder());
    expectAuth(ROLE, true);
    expectNoCronJob();
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    storageUtil.expectTaskFetch(Query.jobScoped(JOB_KEY).active());
    expect(taskIdGenerator.generate(populatedTask, 1))
        .andReturn(TASK_ID);
    expectInstanceQuotaCheck(populatedTask, ENOUGH_QUOTA);
    stateManager.insertPendingTasks(
        storageUtil.mutableStoreProvider,
        populatedTask,
        ImmutableSet.of(0));
    expectLastCall().andThrow(new IllegalArgumentException("instance collision"));

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.addInstances(config, LOCK.newBuilder(), SESSION));
  }

  @Test
  public void testAcquireLock() throws Exception {
    expectAuth(ROLE, true);
    expect(lockManager.acquireLock(LOCK_KEY, USER)).andReturn(LOCK);

    control.replay();

    Response response = thrift.acquireLock(LOCK_KEY.newBuilder(), SESSION);
    assertEquals(LOCK.newBuilder(), response.getResult().getAcquireLockResult().getLock());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testAcquireLockInvalidKey() throws Exception {
    control.replay();

    thrift.acquireLock(LockKey.job(new JobKey()), SESSION);
  }

  @Test
  public void testAcquireLockAuthFailed() throws Exception {
    expectAuth(ROLE, false);
    control.replay();

    assertResponse(AUTH_FAILED, thrift.acquireLock(LOCK_KEY.newBuilder(), SESSION));
  }

  @Test
  public void testAcquireLockFailed() throws Exception {
    expectAuth(ROLE, true);
    expect(lockManager.acquireLock(LOCK_KEY, USER))
        .andThrow(new LockException("Failed"));

    control.replay();

    assertResponse(LOCK_ERROR, thrift.acquireLock(LOCK_KEY.newBuilder(), SESSION));
  }

  @Test
  public void testReleaseLock() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    lockManager.releaseLock(LOCK);

    control.replay();

    assertOkResponse(thrift.releaseLock(LOCK.newBuilder(), CHECKED, SESSION));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testReleaseLockInvalidKey() throws Exception {
    control.replay();

    thrift.releaseLock(new Lock().setKey(LockKey.job(new JobKey())), CHECKED, SESSION);
  }

  @Test
  public void testReleaseLockAuthFailed() throws Exception {
    expectAuth(ROLE, false);
    control.replay();

    assertResponse(AUTH_FAILED, thrift.releaseLock(LOCK.newBuilder(), CHECKED, SESSION));
  }

  @Test
  public void testReleaseLockFailed() throws Exception {
    expectAuth(ROLE, true);
    lockManager.validateIfLocked(LOCK_KEY, java.util.Optional.of(LOCK));
    expectLastCall().andThrow(new LockException("Failed"));

    control.replay();

    assertResponse(LOCK_ERROR, thrift.releaseLock(LOCK.newBuilder(), CHECKED, SESSION));
  }

  @Test
  public void testReleaseLockUnchecked() throws Exception {
    expectAuth(ROLE, true);
    lockManager.releaseLock(LOCK);

    control.replay();

    assertEquals(okEmptyResponse(), thrift.releaseLock(LOCK.newBuilder(), UNCHECKED, SESSION));
  }

  @Test
  public void testStartUpdate() throws Exception {
    expectAuth(ROLE, true);
    expectNoCronJob();

    ITaskConfig newTask = buildTaskForJobUpdate(0).getAssignedTask().getTask();
    expect(taskIdGenerator.generate(newTask, 6)).andReturn(TASK_ID);

    IScheduledTask oldTask1 = buildTaskForJobUpdate(0, "old");
    IScheduledTask oldTask2 = buildTaskForJobUpdate(1, "old");
    IScheduledTask oldTask3 = buildTaskForJobUpdate(2, "old2");
    IScheduledTask oldTask4 = buildTaskForJobUpdate(3, "old2");
    IScheduledTask oldTask5 = buildTaskForJobUpdate(4, "old");
    IScheduledTask oldTask6 = buildTaskForJobUpdate(5, "old");
    IScheduledTask oldTask7 = buildTaskForJobUpdate(6, "old");

    IJobUpdate update = buildJobUpdate(6, newTask, ImmutableMap.of(
        oldTask1.getAssignedTask().getTask(), ImmutableSet.of(new Range(0, 1), new Range(4, 6)),
        oldTask3.getAssignedTask().getTask(), ImmutableSet.of(new Range(2, 3))
    ));

    expect(quotaManager.checkJobUpdate(
        update,
        storageUtil.mutableStoreProvider)).andReturn(ENOUGH_QUOTA);

    expect(uuidGenerator.createNew()).andReturn(UU_ID);
    storageUtil.expectTaskFetch(
        Query.unscoped().byJob(JOB_KEY).active(),
        oldTask1,
        oldTask2,
        oldTask3,
        oldTask4,
        oldTask5,
        oldTask6,
        oldTask7);

    jobUpdateController.start(update, AUDIT);

    control.replay();

    // Validate key is populated during sanitizing.
    JobUpdateRequest request = buildJobUpdateRequest(update);
    request.getTaskConfig().unsetJob();

    Response response = assertOkResponse(thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
    assertEquals(
        new StartJobUpdateResult(UPDATE_KEY.newBuilder()),
        response.getResult().getStartJobUpdateResult());
  }

  @Test
  public void testStartUpdateEmptyDesired() throws Exception {
    expectAuth(ROLE, true);
    expectNoCronJob();

    ITaskConfig newTask = buildTaskForJobUpdate(0).getAssignedTask().getTask();
    expect(taskIdGenerator.generate(newTask, 1)).andReturn(TASK_ID);

    IScheduledTask oldTask1 = buildTaskForJobUpdate(0);
    IScheduledTask oldTask2 = buildTaskForJobUpdate(1);

    // Set instance count to 1 to generate empty desired state in diff.
    IJobUpdate update = buildJobUpdate(
        1,
        newTask,
        ImmutableMap.of(oldTask1.getAssignedTask().getTask(), ImmutableSet.of(new Range(0, 1))));

    expect(quotaManager.checkJobUpdate(
        anyObject(IJobUpdate.class),
        eq(storageUtil.mutableStoreProvider))).andReturn(ENOUGH_QUOTA);

    // Set diff-adjusted IJobUpdate expectations.
    JobUpdate expected = update.newBuilder();
    expected.getInstructions().setInitialState(ImmutableSet.of(
        new InstanceTaskConfig(newTask.newBuilder(), ImmutableSet.of(new Range(1, 1)))));
    expected.getInstructions().unsetDesiredState();

    expect(uuidGenerator.createNew()).andReturn(UU_ID);
    storageUtil.expectTaskFetch(
        Query.unscoped().byJob(JOB_KEY).active(),
        oldTask1,
        oldTask2);

    jobUpdateController.start(IJobUpdate.build(expected), AUDIT);

    control.replay();

    Response response = assertOkResponse(
        thrift.startJobUpdate(buildJobUpdateRequest(update), AUDIT_MESSAGE, SESSION));
    assertEquals(
        new StartJobUpdateResult(UPDATE_KEY.newBuilder()),
        response.getResult().getStartJobUpdateResult());
  }

  @Test(expected = NullPointerException.class)
  public void testStartUpdateFailsNullRequest() throws Exception {
    control.replay();
    thrift.startJobUpdate(null, AUDIT_MESSAGE, SESSION);
  }

  @Test(expected = NullPointerException.class)
  public void testStartUpdateFailsNullTaskConfig() throws Exception {
    control.replay();
    thrift.startJobUpdate(
        new JobUpdateRequest(null, 5, buildJobUpdateSettings()),
        AUDIT_MESSAGE,
        SESSION);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testStartUpdateFailsInvalidJobKey() throws Exception {
    control.replay();
    thrift.startJobUpdate(
        new JobUpdateRequest(
            new TaskConfig()
                .setJobName("&")
                .setEnvironment("devel")
                .setOwner(new Identity(ROLE, null)),
            5,
            buildJobUpdateSettings()),
        AUDIT_MESSAGE,
        SESSION);
  }

  @Test
  public void testStartUpdateFailsInvalidGroupSize() throws Exception {
    control.replay();

    JobUpdateRequest updateRequest = buildServiceJobUpdateRequest();
    updateRequest.getSettings().setUpdateGroupSize(0);

    assertEquals(
        invalidResponse(SchedulerThriftInterface.INVALID_GROUP_SIZE),
        thrift.startJobUpdate(updateRequest, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsInvalidMaxInstanceFailures() throws Exception {
    control.replay();

    JobUpdateRequest updateRequest = buildServiceJobUpdateRequest();
    updateRequest.getSettings().setMaxPerInstanceFailures(-1);

    assertEquals(
        invalidResponse(SchedulerThriftInterface.INVALID_MAX_INSTANCE_FAILURES),
        thrift.startJobUpdate(updateRequest, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsInvalidMaxFailedInstances() throws Exception {
    control.replay();

    JobUpdateRequest updateRequest = buildServiceJobUpdateRequest();
    updateRequest.getSettings().setMaxFailedInstances(-1);

    assertEquals(
        invalidResponse(SchedulerThriftInterface.INVALID_MAX_FAILED_INSTANCES),
        thrift.startJobUpdate(updateRequest, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsInvalidMinWaitInRunning() throws Exception {
    control.replay();

    JobUpdateRequest updateRequest = buildServiceJobUpdateRequest();
    updateRequest.getSettings().setMinWaitInInstanceRunningMs(-1);

    assertEquals(
        invalidResponse(SchedulerThriftInterface.INVALID_MIN_WAIT_TO_RUNNING),
        thrift.startJobUpdate(updateRequest, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsNonServiceTask() throws Exception {
    control.replay();
    JobUpdateRequest request = buildJobUpdateRequest(populatedTask().setIsService(false));
    assertResponse(INVALID_REQUEST, thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsInvalidPulseTimeout() throws Exception {
    control.replay();

    JobUpdateRequest updateRequest = buildServiceJobUpdateRequest();
    updateRequest.getSettings().setBlockIfNoPulsesAfterMs(-1);

    assertEquals(
        invalidResponse(SchedulerThriftInterface.INVALID_PULSE_TIMEOUT),
        thrift.startJobUpdate(updateRequest, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsAuth() throws Exception {
    JobUpdateRequest request = buildServiceJobUpdateRequest(populatedTask());
    expectAuth(ROLE, false);

    control.replay();
    assertResponse(AUTH_FAILED, thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsForCronJob() throws Exception {
    JobUpdateRequest request = buildServiceJobUpdateRequest(populatedTask());
    expectAuth(ROLE, true);
    expectCronJob();

    control.replay();
    assertEquals(invalidResponse(NO_CRON), thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsConfigValidation() throws Exception {
    JobUpdateRequest request =
        buildJobUpdateRequest(populatedTask().setIsService(true).setNumCpus(-1));
    expectAuth(ROLE, true);

    control.replay();
    assertResponse(INVALID_REQUEST, thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartNoopUpdate() throws Exception {
    expectAuth(ROLE, true);
    expectNoCronJob();
    expect(uuidGenerator.createNew()).andReturn(UU_ID);
    ITaskConfig newTask = buildTaskForJobUpdate(0).getAssignedTask().getTask();

    IScheduledTask oldTask = buildTaskForJobUpdate(0);
    storageUtil.expectTaskFetch(Query.unscoped().byJob(JOB_KEY).active(), oldTask);

    IJobUpdate update = buildJobUpdate(
        1,
        newTask,
        ImmutableMap.of(oldTask.getAssignedTask().getTask(), ImmutableSet.of(new Range(0, 0))));

    control.replay();
    JobUpdateRequest request = buildJobUpdateRequest(update);
    Response response = thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION);
    assertResponse(OK, response);
    assertEquals(
        NOOP_JOB_UPDATE_MESSAGE,
        Iterables.getOnlyElement(response.getDetails()).getMessage());
  }

  @Test
  public void testStartUpdateInvalidScope() throws Exception {
    expectAuth(ROLE, true);
    expectNoCronJob();
    expect(uuidGenerator.createNew()).andReturn(UU_ID);

    IScheduledTask oldTask = buildTaskForJobUpdate(0);
    storageUtil.expectTaskFetch(Query.unscoped().byJob(JOB_KEY).active(), oldTask);

    ITaskConfig newTask = buildTaskForJobUpdate(0).getAssignedTask().getTask();
    JobUpdate builder = buildJobUpdate(
        1,
        newTask,
        ImmutableMap.of(oldTask.getAssignedTask().getTask(), ImmutableSet.of(new Range(0, 0))))
        .newBuilder();
    builder.getInstructions().getSettings()
        .setUpdateOnlyTheseInstances(ImmutableSet.of(new Range(100, 100)));

    control.replay();
    JobUpdateRequest request = buildJobUpdateRequest(IJobUpdate.build(builder));
    assertResponse(INVALID_REQUEST, thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsInstanceCountCheck() throws Exception {
    JobUpdateRequest request = buildServiceJobUpdateRequest(populatedTask());
    request.setInstanceCount(4001);
    expectAuth(ROLE, true);
    expectNoCronJob();
    expect(uuidGenerator.createNew()).andReturn(UU_ID);
    storageUtil.expectTaskFetch(Query.unscoped().byJob(JOB_KEY).active());
    expect(quotaManager.checkJobUpdate(
        anyObject(IJobUpdate.class),
        eq(storageUtil.mutableStoreProvider))).andReturn(ENOUGH_QUOTA);

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsTaskIdLength() throws Exception {
    JobUpdateRequest request = buildServiceJobUpdateRequest(populatedTask());
    expectAuth(ROLE, true);
    expectNoCronJob();
    expect(uuidGenerator.createNew()).andReturn(UU_ID);
    storageUtil.expectTaskFetch(Query.unscoped().byJob(JOB_KEY).active());
    expect(quotaManager.checkJobUpdate(
        anyObject(IJobUpdate.class),
        eq(storageUtil.mutableStoreProvider))).andReturn(ENOUGH_QUOTA);

    expect(taskIdGenerator.generate(ITaskConfig.build(request.getTaskConfig()), 6))
        .andReturn(Strings.repeat("a", MAX_TASK_ID_LENGTH + 1));

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsQuotaCheck() throws Exception {
    JobUpdateRequest request = buildServiceJobUpdateRequest(populatedTask());
    expectAuth(ROLE, true);
    expectNoCronJob();
    expect(uuidGenerator.createNew()).andReturn(UU_ID);

    IScheduledTask oldTask = buildTaskForJobUpdate(0);
    storageUtil.expectTaskFetch(Query.unscoped().byJob(JOB_KEY).active(), oldTask);
    expect(taskIdGenerator.generate(ITaskConfig.build(request.getTaskConfig()), 6))
        .andReturn(TASK_ID);

    ITaskConfig config = ITaskConfig.build(request.getTaskConfig());
    IJobUpdate update = buildJobUpdate(6, config, ImmutableMap.of(
        oldTask.getAssignedTask().getTask(), ImmutableSet.of(new Range(0, 0))));

    expect(quotaManager.checkJobUpdate(
        update,
        storageUtil.mutableStoreProvider)).andReturn(NOT_ENOUGH_QUOTA);

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.startJobUpdate(request, AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testStartUpdateFailsInController() throws Exception {
    expectAuth(ROLE, true);
    expectNoCronJob();

    IScheduledTask oldTask = buildTaskForJobUpdate(0, "old");
    ITaskConfig newTask = buildTaskForJobUpdate(0, "new").getAssignedTask().getTask();

    IJobUpdate update = buildJobUpdate(
        1,
        newTask,
        ImmutableMap.of(oldTask.getAssignedTask().getTask(), ImmutableSet.of(new Range(0, 0))));

    expect(uuidGenerator.createNew()).andReturn(UU_ID);
    expect(taskIdGenerator.generate(newTask, 1)).andReturn(TASK_ID);
    expect(quotaManager.checkJobUpdate(
        update,
        storageUtil.mutableStoreProvider)).andReturn(ENOUGH_QUOTA);

    storageUtil.expectTaskFetch(Query.unscoped().byJob(JOB_KEY).active(), oldTask);
    jobUpdateController.start(update, AUDIT);
    expectLastCall().andThrow(new UpdateStateException("failed"));

    control.replay();

    assertResponse(
        INVALID_REQUEST,
        thrift.startJobUpdate(buildJobUpdateRequest(update), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testPauseJobUpdateByCoordinator() throws Exception {
    expectAuth(UPDATE_COORDINATOR, true);
    jobUpdateController.pause(UPDATE_KEY, AUDIT);

    control.replay();

    assertResponse(OK, thrift.pauseJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testPauseJobUpdateByUser() throws Exception {
    expectAuth(ROLE, true);
    expectAuth(UPDATE_COORDINATOR, false);
    jobUpdateController.pause(UPDATE_KEY, AUDIT);

    control.replay();

    assertResponse(OK, thrift.pauseJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testPauseMessageTooLong() throws Exception {
    expectAuth(ROLE, true);
    expectAuth(UPDATE_COORDINATOR, false);

    control.replay();

    assertResponse(
        OK,
        thrift.pauseJobUpdate(
            UPDATE_KEY.newBuilder(),
            Strings.repeat("*", AuditData.MAX_MESSAGE_LENGTH + 1),
            SESSION));
  }

  @Test
  public void testPauseJobUpdateFailsAuth() throws Exception {
    expectAuth(UPDATE_COORDINATOR, false);
    expectAuth(ROLE, false);

    control.replay();

    assertResponse(
        AUTH_FAILED,
        thrift.pauseJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testPauseJobUpdateFailsInController() throws Exception {
    expectAuth(UPDATE_COORDINATOR, true);
    jobUpdateController.pause(UPDATE_KEY, AUDIT);
    expectLastCall().andThrow(new UpdateStateException("failed"));

    control.replay();

    assertResponse(
        INVALID_REQUEST,
        thrift.pauseJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testResumeJobUpdateByCoordinator() throws Exception {
    expectAuth(UPDATE_COORDINATOR, true);
    jobUpdateController.resume(UPDATE_KEY, AUDIT);

    control.replay();

    assertResponse(OK, thrift.resumeJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testResumeJobUpdateByUser() throws Exception {
    expectAuth(ROLE, true);
    expectAuth(UPDATE_COORDINATOR, false);
    jobUpdateController.resume(UPDATE_KEY, AUDIT);

    control.replay();

    assertResponse(OK, thrift.resumeJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testResumeJobUpdateFailsAuth() throws Exception {
    expectAuth(UPDATE_COORDINATOR, false);
    expectAuth(ROLE, false);

    control.replay();

    assertResponse(
        AUTH_FAILED,
        thrift.resumeJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testResumeJobUpdateFailsInController() throws Exception {
    expectAuth(UPDATE_COORDINATOR, true);
    jobUpdateController.resume(UPDATE_KEY, AUDIT);
    expectLastCall().andThrow(new UpdateStateException("failed"));

    control.replay();

    assertResponse(
        INVALID_REQUEST,
        thrift.resumeJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testAbortJobUpdateByCoordinator() throws Exception {
    expectAuth(UPDATE_COORDINATOR, true);
    jobUpdateController.abort(UPDATE_KEY, AUDIT);

    control.replay();

    assertResponse(OK, thrift.abortJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testAbortJobUpdateByUser() throws Exception {
    expectAuth(ROLE, true);
    expectAuth(UPDATE_COORDINATOR, false);
    jobUpdateController.abort(UPDATE_KEY, AUDIT);

    control.replay();

    assertResponse(OK, thrift.abortJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testAbortJobUpdateFailsAuth() throws Exception {
    expectAuth(UPDATE_COORDINATOR, false);
    expectAuth(ROLE, false);

    control.replay();

    assertResponse(
        AUTH_FAILED,
        thrift.abortJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testAbortJobUpdateFailsInController() throws Exception {
    expectAuth(UPDATE_COORDINATOR, true);
    jobUpdateController.abort(UPDATE_KEY, AUDIT);
    expectLastCall().andThrow(new UpdateStateException("failed"));

    control.replay();

    assertResponse(
        INVALID_REQUEST,
        thrift.abortJobUpdate(UPDATE_KEY.newBuilder(), AUDIT_MESSAGE, SESSION));
  }

  @Test
  public void testPulseJobUpdatePulsedAsCoordinator() throws Exception {
    expectAuth(UPDATE_COORDINATOR, true);
    expect(jobUpdateController.pulse(UPDATE_KEY)).andReturn(JobUpdatePulseStatus.OK);

    control.replay();

    assertEquals(
        okResponse(Result.pulseJobUpdateResult(new PulseJobUpdateResult(JobUpdatePulseStatus.OK))),
        thrift.pulseJobUpdate(UPDATE_KEY.newBuilder(), SESSION));
  }

  @Test
  public void testPulseJobUpdatePulsedAsUser() throws Exception {
    expectAuth(UPDATE_COORDINATOR, false);
    expectAuth(ROLE, true);
    expect(jobUpdateController.pulse(UPDATE_KEY)).andReturn(JobUpdatePulseStatus.OK);

    control.replay();

    assertEquals(
        okResponse(Result.pulseJobUpdateResult(new PulseJobUpdateResult(JobUpdatePulseStatus.OK))),
        thrift.pulseJobUpdate(UPDATE_KEY.newBuilder(), SESSION));
  }

  @Test
  public void testPulseJobUpdateFails() throws Exception {
    expectAuth(UPDATE_COORDINATOR, true);
    expect(jobUpdateController.pulse(UPDATE_KEY)).andThrow(new UpdateStateException("failure"));

    control.replay();

    assertResponse(INVALID_REQUEST, thrift.pulseJobUpdate(UPDATE_KEY.newBuilder(), SESSION));
  }

  @Test
  public void testPulseJobUpdateFailsAuth() throws Exception {
    expectAuth(UPDATE_COORDINATOR, false);
    expectAuth(ROLE, false);

    control.replay();

    assertResponse(AUTH_FAILED, thrift.pulseJobUpdate(UPDATE_KEY.newBuilder(), SESSION));
  }

  private static final String AUTH_DENIED_MESSAGE = "Denied!";

  private IExpectationSetters<?> expectAuth(Set<String> roles, boolean allowed)
      throws AuthFailedException {

    if (!allowed) {
      return expect(userValidator.checkAuthenticated(SESSION, roles))
          .andThrow(new AuthFailedException(AUTH_DENIED_MESSAGE));
    } else {
      return expect(userValidator.checkAuthenticated(SESSION, roles))
          .andReturn(context);
    }
  }

  private IExpectationSetters<?> expectAuth(String role, boolean allowed)
      throws AuthFailedException {

    return expectAuth(ImmutableSet.of(role), allowed);
  }

  private IExpectationSetters<?> expectAuth(Capability capability, boolean allowed)
      throws AuthFailedException {

    if (!allowed) {
      return expect(userValidator.checkAuthorized(
          eq(SESSION),
          eq(capability),
          anyObject(AuditCheck.class))
      ).andThrow(new AuthFailedException(AUTH_DENIED_MESSAGE));
    } else {
      return expect(userValidator.checkAuthorized(
          eq(SESSION),
          eq(capability),
          anyObject(AuditCheck.class))).andReturn(context);
    }
  }

  private static TaskConfig populatedTask() {
    return defaultTask(true).setConstraints(Sets.newHashSet(
        new Constraint("host", TaskConstraint.limit(new LimitConstraint(1)))));
  }

  private static Constraint dedicatedConstraint(int value) {
    return new Constraint(DEDICATED_ATTRIBUTE, TaskConstraint.limit(new LimitConstraint(value)));
  }

  private static Constraint dedicatedConstraint(Set<String> values) {
    return new Constraint(DEDICATED_ATTRIBUTE,
        TaskConstraint.value(new ValueConstraint(false, values)));
  }

  private static JobUpdateRequest buildServiceJobUpdateRequest() {
    return buildServiceJobUpdateRequest(defaultTask(true));
  }

  private static JobUpdateRequest buildServiceJobUpdateRequest(TaskConfig config) {
    return buildJobUpdateRequest(config.setIsService(true));
  }

  private static JobUpdateRequest buildJobUpdateRequest(TaskConfig config) {
    return new JobUpdateRequest()
        .setInstanceCount(6)
        .setSettings(buildJobUpdateSettings())
        .setTaskConfig(ConfigurationManager.applyDefaultsIfUnset(config));
  }

  private static JobUpdateSettings buildJobUpdateSettings() {
    return new JobUpdateSettings()
        .setUpdateGroupSize(10)
        .setMaxFailedInstances(2)
        .setMaxPerInstanceFailures(1)
        .setMaxWaitToInstanceRunningMs(30000)
        .setMinWaitInInstanceRunningMs(15000)
        .setRollbackOnFailure(true);
  }

  private static Integer rangesToInstanceCount(Set<IRange> ranges) {
    int instanceCount = 0;
    for (IRange range : ranges) {
      instanceCount += range.getLast() - range.getFirst() + 1;
    }

    return instanceCount;
  }

  private static JobUpdateRequest buildJobUpdateRequest(IJobUpdate update) {
    return new JobUpdateRequest()
        .setInstanceCount(rangesToInstanceCount(
            update.getInstructions().getDesiredState().getInstances()))
        .setSettings(update.getInstructions().getSettings().newBuilder())
        .setTaskConfig(update.getInstructions().getDesiredState().getTask().newBuilder());
  }

  private static IJobUpdate buildJobUpdate(
      int instanceCount,
      ITaskConfig newConfig,
      ImmutableMap<ITaskConfig, ImmutableSet<Range>> oldConfigMap) {

    ImmutableSet.Builder<InstanceTaskConfig> builder = ImmutableSet.builder();
    for (Map.Entry<ITaskConfig, ImmutableSet<Range>> entry : oldConfigMap.entrySet()) {
      builder.add(new InstanceTaskConfig(entry.getKey().newBuilder(), entry.getValue()));
    }

    return IJobUpdate.build(new JobUpdate()
        .setSummary(new JobUpdateSummary()
            .setKey(UPDATE_KEY.newBuilder())
            .setUser(ROLE_IDENTITY.getUser()))
        .setInstructions(new JobUpdateInstructions()
            .setSettings(buildJobUpdateSettings())
            .setDesiredState(new InstanceTaskConfig()
                .setTask(newConfig.newBuilder())
                .setInstances(ImmutableSet.of(new Range(0, instanceCount - 1))))
            .setInitialState(builder.build())));
  }

  private IExpectationSetters<?> expectCronJob() {
    return expect(storageUtil.jobStore.fetchJob(JOB_KEY))
        .andReturn(Optional.of(IJobConfiguration.build(CRON_JOB)));
  }

  private IExpectationSetters<?> expectNoCronJob() {
    return expect(storageUtil.jobStore.fetchJob(JOB_KEY))
        .andReturn(Optional.absent());
  }

  private IExpectationSetters<?> expectInstanceQuotaCheck(
      SanitizedConfiguration sanitized,
      QuotaCheckResult result) {

    return expectInstanceQuotaCheck(sanitized.getJobConfig().getTaskConfig(), result);
  }

  private IExpectationSetters<?> expectInstanceQuotaCheck(
      ITaskConfig config,
      QuotaCheckResult result) {

    return expect(quotaManager.checkInstanceAddition(
        config,
        1,
        storageUtil.mutableStoreProvider)).andReturn(result);
  }

  private IExpectationSetters<?> expectCronQuotaCheck(
      IJobConfiguration config,
      QuotaCheckResult result) {

    return expect(quotaManager.checkCronUpdate(config, storageUtil.mutableStoreProvider))
        .andReturn(result);
  }
}
