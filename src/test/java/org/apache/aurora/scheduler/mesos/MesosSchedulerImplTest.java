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
package org.apache.aurora.scheduler.mesos;

import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.testing.TearDown;
import com.google.common.util.concurrent.MoreExecutors;
import com.twitter.common.application.Lifecycle;
import com.twitter.common.base.Command;
import com.twitter.common.testing.easymock.EasyMockTest;

import org.apache.aurora.gen.Attribute;
import org.apache.aurora.gen.HostAttributes;
import org.apache.aurora.gen.MaintenanceMode;
import org.apache.aurora.scheduler.HostOffer;
import org.apache.aurora.scheduler.TaskLauncher;
import org.apache.aurora.scheduler.base.Conversions;
import org.apache.aurora.scheduler.base.SchedulerException;
import org.apache.aurora.scheduler.events.EventSink;
import org.apache.aurora.scheduler.events.PubsubEvent.DriverDisconnected;
import org.apache.aurora.scheduler.events.PubsubEvent.DriverRegistered;
import org.apache.aurora.scheduler.events.PubsubEvent.TaskStatusReceived;
import org.apache.aurora.scheduler.stats.CachedCounters;
import org.apache.aurora.scheduler.storage.Storage.StorageException;
import org.apache.aurora.scheduler.storage.entities.IHostAttributes;
import org.apache.aurora.scheduler.storage.testing.StorageTestUtil;
import org.apache.aurora.scheduler.testing.FakeStatsProvider;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskState;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.Protos.TaskStatus.Reason;
import org.apache.mesos.Protos.TaskStatus.Source;
import org.apache.mesos.SchedulerDriver;
import org.junit.Before;
import org.junit.Test;

import static org.apache.aurora.gen.MaintenanceMode.DRAINING;
import static org.apache.aurora.gen.MaintenanceMode.NONE;
import static org.apache.mesos.Protos.Offer;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertTrue;

public class MesosSchedulerImplTest extends EasyMockTest {

  private static final String FRAMEWORK_ID = "framework-id";
  private static final FrameworkID FRAMEWORK =
      FrameworkID.newBuilder().setValue(FRAMEWORK_ID).build();

  private static final String SLAVE_HOST = "slave-hostname";
  private static final SlaveID SLAVE_ID = SlaveID.newBuilder().setValue("slave-id").build();
  private static final String SLAVE_HOST_2 = "slave-hostname-2";
  private static final SlaveID SLAVE_ID_2 = SlaveID.newBuilder().setValue("slave-id-2").build();
  private static final ExecutorID EXECUTOR_ID =
      ExecutorID.newBuilder().setValue("executor-id").build();

  private static final OfferID OFFER_ID = OfferID.newBuilder().setValue("offer-id").build();
  private static final HostOffer OFFER = new HostOffer(
      Offer.newBuilder()
          .setFrameworkId(FRAMEWORK)
          .setSlaveId(SLAVE_ID)
          .setHostname(SLAVE_HOST)
          .setId(OFFER_ID)
          .build(),
      IHostAttributes.build(
          new HostAttributes()
              .setHost(SLAVE_HOST)
              .setSlaveId(SLAVE_ID.getValue())
              .setMode(NONE)
              .setAttributes(ImmutableSet.<Attribute>of())));
  private static final OfferID OFFER_ID_2 = OfferID.newBuilder().setValue("offer-id-2").build();
  private static final HostOffer OFFER_2 = new HostOffer(
      Offer.newBuilder(OFFER.getOffer())
          .setSlaveId(SLAVE_ID_2)
          .setHostname(SLAVE_HOST_2)
          .setId(OFFER_ID_2)
          .build(),
      IHostAttributes.build(
          new HostAttributes()
              .setHost(SLAVE_HOST_2)
              .setSlaveId(SLAVE_ID_2.getValue())
              .setMode(NONE)
              .setAttributes(ImmutableSet.<Attribute>of())));

  private static final TaskStatus STATUS = TaskStatus.newBuilder()
      .setState(TaskState.TASK_RUNNING)
      .setSource(Source.SOURCE_SLAVE)
      // Only testing data plumbing, this field with TASK_RUNNING would not normally happen,
      .setReason(Reason.REASON_COMMAND_EXECUTOR_FAILED)
      .setMessage("message")
      .setTimestamp(1D)
      .setTaskId(TaskID.newBuilder().setValue("task-id").build())
      .build();

  private static final TaskStatusReceived PUBSUB_EVENT = new TaskStatusReceived(
      STATUS.getState(),
      Optional.of(STATUS.getSource()),
      Optional.of(STATUS.getReason()),
      Optional.of(1000000L)
  );

  private Logger log;
  private StorageTestUtil storageUtil;
  private Command shutdownCommand;
  private TaskLauncher systemLauncher;
  private TaskLauncher userLauncher;
  private SchedulerDriver driver;
  private EventSink eventSink;

  private MesosSchedulerImpl scheduler;

  @Before
  public void setUp() {
    log = Logger.getAnonymousLogger();
    log.setLevel(Level.INFO);
    storageUtil = new StorageTestUtil(this);
    shutdownCommand = createMock(Command.class);
    final Lifecycle lifecycle =
        new Lifecycle(shutdownCommand, createMock(UncaughtExceptionHandler.class));
    systemLauncher = createMock(TaskLauncher.class);
    userLauncher = createMock(TaskLauncher.class);
    eventSink = createMock(EventSink.class);

    scheduler = new MesosSchedulerImpl(
        storageUtil.storage,
        lifecycle,
        ImmutableList.of(systemLauncher, userLauncher),
        eventSink,
        MoreExecutors.sameThreadExecutor(),
        log,
        new CachedCounters(new FakeStatsProvider()));
    driver = createMock(SchedulerDriver.class);
  }

  @Test(expected = IllegalStateException.class)
  public void testBadOrdering() {
    control.replay();

    // Should fail since the scheduler is not yet registered.
    scheduler.resourceOffers(driver, ImmutableList.<Offer>of());
  }

  @Test
  public void testNoOffers() {
    new RegisteredFixture() {
      @Override
      void test() {
        scheduler.resourceOffers(driver, ImmutableList.<Offer>of());
      }
    }.run();
  }

  @Test
  public void testNoAccepts() {
    new OfferFixture() {
      @Override
      void respondToOffer() {
        expectOfferAttributesSaved(OFFER);
        expect(systemLauncher.willUse(OFFER)).andReturn(false);
        expect(userLauncher.willUse(OFFER)).andReturn(false);
      }
    }.run();
  }

  @Test
  public void testOfferFirstAccepts() {
    new OfferFixture() {
      @Override
      void respondToOffer() {
        expectOfferAttributesSaved(OFFER);
        expect(systemLauncher.willUse(OFFER)).andReturn(true);
      }
    }.run();
  }

  @Test
  public void testOfferFirstAcceptsFineLogging() {
    log.setLevel(Level.FINE);
    new OfferFixture() {
      @Override
      void respondToOffer() {
        expectOfferAttributesSaved(OFFER);
        expect(systemLauncher.willUse(OFFER)).andReturn(true);
      }
    }.run();
  }

  @Test
  public void testOfferSchedulerAccepts() {
    new OfferFixture() {
      @Override
      void respondToOffer() {
        expectOfferAttributesSaved(OFFER);
        expect(systemLauncher.willUse(OFFER)).andReturn(false);
        expect(userLauncher.willUse(OFFER)).andReturn(true);
      }
    }.run();
  }

  @Test
  public void testAttributesModePreserved() {
    new OfferFixture() {
      @Override
      void respondToOffer() {
        IHostAttributes draining =
            IHostAttributes.build(OFFER.getAttributes().newBuilder().setMode(DRAINING));
        expect(storageUtil.attributeStore.getHostAttributes(OFFER.getOffer().getHostname()))
            .andReturn(Optional.of(draining));
        IHostAttributes saved = IHostAttributes.build(
            Conversions.getAttributes(OFFER.getOffer()).newBuilder().setMode(DRAINING));
        expect(storageUtil.attributeStore.saveHostAttributes(saved)).andReturn(true);

        HostOffer offer = new HostOffer(OFFER.getOffer(), draining);
        expect(systemLauncher.willUse(offer)).andReturn(false);
        expect(userLauncher.willUse(offer)).andReturn(true);
      }
    }.run();
  }

  @Test
  public void testStatusUpdateNoAccepts() {
    new StatusFixture() {
      @Override
      void expectations() {
        eventSink.post(PUBSUB_EVENT);
        expect(systemLauncher.statusUpdate(status)).andReturn(false);
        expect(userLauncher.statusUpdate(status)).andReturn(false);
        expect(driver.acknowledgeStatusUpdate(status)).andReturn(Protos.Status.DRIVER_RUNNING);
      }
    }.run();
  }

  private class FirstLauncherAccepts extends StatusFixture {
    FirstLauncherAccepts(TaskStatus status) {
      super(status);
    }

    @Override
    void expectations() {
      eventSink.post(new TaskStatusReceived(
          status.getState(),
          Optional.fromNullable(status.getSource()),
          Optional.fromNullable(status.getReason()),
          Optional.of(1000000L)
      ));
      expect(systemLauncher.statusUpdate(status)).andReturn(true);
      expect(driver.acknowledgeStatusUpdate(status)).andReturn(Protos.Status.DRIVER_RUNNING);
    }
  }

  @Test
  public void testStatusUpdateFirstAccepts() {
    // Test multiple variations of fields in TaskStatus to cover all branches.
    new FirstLauncherAccepts(STATUS).run();
    control.verify();
    control.reset();
    new FirstLauncherAccepts(STATUS.toBuilder().clearSource().build()).run();
    control.verify();
    control.reset();
    new FirstLauncherAccepts(STATUS.toBuilder().clearReason().build()).run();
    control.verify();
    control.reset();
    new FirstLauncherAccepts(STATUS.toBuilder().clearMessage().build()).run();
  }

  @Test
  public void testStatusUpdateSecondAccepts() {
    new StatusFixture() {
      @Override
      void expectations() {
        eventSink.post(PUBSUB_EVENT);
        expect(systemLauncher.statusUpdate(status)).andReturn(false);
        expect(userLauncher.statusUpdate(status)).andReturn(true);
        expect(driver.acknowledgeStatusUpdate(status)).andReturn(Protos.Status.DRIVER_RUNNING);
      }
    }.run();
  }

  @Test(expected = SchedulerException.class)
  public void testStatusUpdateFails() {
    new StatusFixture() {
      @Override
      void expectations() {
        eventSink.post(PUBSUB_EVENT);
        expect(systemLauncher.statusUpdate(status)).andReturn(false);
        expect(userLauncher.statusUpdate(status)).andThrow(new StorageException("Injected."));
      }
    }.run();
  }

  @Test
  public void testMultipleOffers() {
    new RegisteredFixture() {
      @Override
      void expectations() {
        expectOfferAttributesSaved(OFFER);
        expectOfferAttributesSaved(OFFER_2);
        expect(systemLauncher.willUse(OFFER)).andReturn(false);
        expect(userLauncher.willUse(OFFER)).andReturn(true);
        expect(systemLauncher.willUse(OFFER_2)).andReturn(false);
        expect(userLauncher.willUse(OFFER_2)).andReturn(false);
      }

      @Override
      void test() {
        scheduler.resourceOffers(driver, ImmutableList.of(OFFER.getOffer(), OFFER_2.getOffer()));
      }
    }.run();
  }

  @Test
  public void testDisconnected() {
    new RegisteredFixture() {
      @Override
      void expectations() {
        eventSink.post(new DriverDisconnected());
      }

      @Override
      void test() {
        scheduler.disconnected(driver);
      }
    }.run();
  }

  @Test
  public void testFrameworkMessageIgnored() {
    control.replay();

    scheduler.frameworkMessage(
        driver,
        EXECUTOR_ID,
        SLAVE_ID,
        "hello".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void testSlaveLost() {
    control.replay();

    scheduler.slaveLost(driver, SLAVE_ID);
  }

  @Test
  public void testReregistered() {
    control.replay();

    scheduler.reregistered(driver, MasterInfo.getDefaultInstance());
  }

  @Test
  public void testOfferRescinded() {
    systemLauncher.cancelOffer(OFFER_ID);
    userLauncher.cancelOffer(OFFER_ID);

    control.replay();

    scheduler.offerRescinded(driver, OFFER_ID);
  }

  @Test
  public void testError() {
    shutdownCommand.execute();

    control.replay();

    scheduler.error(driver, "error");
  }

  @Test
  public void testExecutorLost() {
    control.replay();

    scheduler.executorLost(driver, EXECUTOR_ID, SLAVE_ID, 1);
  }

  private void expectOfferAttributesSaved(HostOffer offer) {
    expect(storageUtil.attributeStore.getHostAttributes(offer.getOffer().getHostname()))
        .andReturn(Optional.<IHostAttributes>absent());
    IHostAttributes defaultMode = IHostAttributes.build(
        Conversions.getAttributes(offer.getOffer()).newBuilder().setMode(MaintenanceMode.NONE));
    expect(storageUtil.attributeStore.saveHostAttributes(defaultMode)).andReturn(true);
  }

  private abstract class RegisteredFixture {
    private final AtomicBoolean runCalled = new AtomicBoolean(false);

    RegisteredFixture() {
      // Prevent otherwise silent noop tests that forget to call run().
      addTearDown(new TearDown() {
        @Override
        public void tearDown() {
          assertTrue(runCalled.get());
        }
      });
    }

    void run() {
      runCalled.set(true);
      eventSink.post(new DriverRegistered());
      storageUtil.expectOperations();
      storageUtil.schedulerStore.saveFrameworkId(FRAMEWORK_ID);
      expectations();

      control.replay();

      scheduler.registered(driver, FRAMEWORK, MasterInfo.getDefaultInstance());
      test();
    }

    void expectations() {
      // Default no-op, subclasses may override.
    }

    abstract void test();
  }

  private abstract class OfferFixture extends RegisteredFixture {
    OfferFixture() {
      super();
    }

    abstract void respondToOffer();

    @Override
    void expectations() {
      respondToOffer();
    }

    @Override
    void test() {
      scheduler.resourceOffers(driver, ImmutableList.of(OFFER.getOffer()));
    }
  }

  private abstract class StatusFixture extends RegisteredFixture {
    protected final TaskStatus status;

    StatusFixture() {
      this(STATUS);
    }

    StatusFixture(TaskStatus status) {
      super();
      this.status = status;
    }

    @Override
    void test() {
      scheduler.statusUpdate(driver, status);
    }
  }
}
