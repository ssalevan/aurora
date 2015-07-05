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
package org.apache.aurora.scheduler.async;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.RateLimiter;
import com.google.inject.AbstractModule;
import com.google.inject.PrivateModule;
import com.google.inject.TypeLiteral;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;
import com.twitter.common.args.constraints.NotNegative;
import com.twitter.common.args.constraints.Positive;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.stats.StatsProvider;
import com.twitter.common.util.BackoffStrategy;
import com.twitter.common.util.Random;
import com.twitter.common.util.TruncatedBinaryBackoff;

import org.apache.aurora.scheduler.SchedulerServicesModule;
import org.apache.aurora.scheduler.async.OfferManager.OfferManagerImpl;
import org.apache.aurora.scheduler.async.OfferManager.OfferReturnDelay;
import org.apache.aurora.scheduler.async.RescheduleCalculator.RescheduleCalculatorImpl;
import org.apache.aurora.scheduler.async.TaskGroups.TaskGroupsSettings;
import org.apache.aurora.scheduler.async.TaskHistoryPruner.HistoryPrunnerSettings;
import org.apache.aurora.scheduler.async.TaskReconciler.TaskReconcilerSettings;
import org.apache.aurora.scheduler.async.TaskScheduler.TaskSchedulerImpl;
import org.apache.aurora.scheduler.async.preemptor.BiCache;
import org.apache.aurora.scheduler.async.preemptor.BiCache.BiCacheSettings;
import org.apache.aurora.scheduler.base.AsyncUtil;
import org.apache.aurora.scheduler.base.TaskGroupKey;
import org.apache.aurora.scheduler.events.PubsubEventModule;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Objects.requireNonNull;

/**
 * Binding module for async task management.
 */
public class AsyncModule extends AbstractModule {

  private static final Logger LOG = Logger.getLogger(AsyncModule.class.getName());

  @CmdLine(name = "async_worker_threads",
      help = "The number of worker threads to process async task operations with.")
  private static final Arg<Integer> ASYNC_WORKER_THREADS = Arg.create(1);

  @CmdLine(name = "transient_task_state_timeout",
      help = "The amount of time after which to treat a task stuck in a transient state as LOST.")
  private static final Arg<Amount<Long, Time>> TRANSIENT_TASK_STATE_TIMEOUT =
      Arg.create(Amount.of(5L, Time.MINUTES));

  @Positive
  @CmdLine(name = "first_schedule_delay",
      help = "Initial amount of time to wait before first attempting to schedule a PENDING task.")
  private static final Arg<Amount<Long, Time>> FIRST_SCHEDULE_DELAY =
      Arg.create(Amount.of(1L, Time.MILLISECONDS));

  @Positive
  @CmdLine(name = "initial_schedule_penalty",
      help = "Initial amount of time to wait before attempting to schedule a task that has failed"
          + " to schedule.")
  private static final Arg<Amount<Long, Time>> INITIAL_SCHEDULE_PENALTY =
      Arg.create(Amount.of(1L, Time.SECONDS));

  @CmdLine(name = "max_schedule_penalty",
      help = "Maximum delay between attempts to schedule a PENDING tasks.")
  private static final Arg<Amount<Long, Time>> MAX_SCHEDULE_PENALTY =
      Arg.create(Amount.of(1L, Time.MINUTES));

  @CmdLine(name = "min_offer_hold_time",
      help = "Minimum amount of time to hold a resource offer before declining.")
  @NotNegative
  private static final Arg<Amount<Integer, Time>> MIN_OFFER_HOLD_TIME =
      Arg.create(Amount.of(5, Time.MINUTES));

  @CmdLine(name = "offer_hold_jitter_window",
      help = "Maximum amount of random jitter to add to the offer hold time window.")
  @NotNegative
  private static final Arg<Amount<Integer, Time>> OFFER_HOLD_JITTER_WINDOW =
      Arg.create(Amount.of(1, Time.MINUTES));

  @CmdLine(name = "history_prune_threshold",
      help = "Time after which the scheduler will prune terminated task history.")
  private static final Arg<Amount<Long, Time>> HISTORY_PRUNE_THRESHOLD =
      Arg.create(Amount.of(2L, Time.DAYS));

  @CmdLine(name = "history_max_per_job_threshold",
      help = "Maximum number of terminated tasks to retain in a job history.")
  private static final Arg<Integer> HISTORY_MAX_PER_JOB_THRESHOLD = Arg.create(100);

  @CmdLine(name = "history_min_retention_threshold",
      help = "Minimum guaranteed time for task history retention before any pruning is attempted.")
  private static final Arg<Amount<Long, Time>> HISTORY_MIN_RETENTION_THRESHOLD =
      Arg.create(Amount.of(1L, Time.HOURS));

  @CmdLine(name = "max_schedule_attempts_per_sec",
      help = "Maximum number of scheduling attempts to make per second.")
  private static final Arg<Double> MAX_SCHEDULE_ATTEMPTS_PER_SEC = Arg.create(40D);

  @CmdLine(name = "flapping_task_threshold",
      help = "A task that repeatedly runs for less than this time is considered to be flapping.")
  private static final Arg<Amount<Long, Time>> FLAPPING_THRESHOLD =
      Arg.create(Amount.of(5L, Time.MINUTES));

  @CmdLine(name = "initial_flapping_task_delay",
      help = "Initial amount of time to wait before attempting to schedule a flapping task.")
  private static final Arg<Amount<Long, Time>> INITIAL_FLAPPING_DELAY =
      Arg.create(Amount.of(30L, Time.SECONDS));

  @CmdLine(name = "max_flapping_task_delay",
      help = "Maximum delay between attempts to schedule a flapping task.")
  private static final Arg<Amount<Long, Time>> MAX_FLAPPING_DELAY =
      Arg.create(Amount.of(5L, Time.MINUTES));

  @CmdLine(name = "max_reschedule_task_delay_on_startup",
      help = "Upper bound of random delay for pending task rescheduling on scheduler startup.")
  private static final Arg<Amount<Integer, Time>> MAX_RESCHEDULING_DELAY =
      Arg.create(Amount.of(30, Time.SECONDS));

  @CmdLine(name = "job_update_history_per_job_threshold",
      help = "Maximum number of completed job updates to retain in a job update history.")
  private static final Arg<Integer> JOB_UPDATE_HISTORY_PER_JOB_THRESHOLD = Arg.create(10);

  @CmdLine(name = "job_update_history_pruning_interval",
      help = "Job update history pruning interval.")
  private static final Arg<Amount<Long, Time>> JOB_UPDATE_HISTORY_PRUNING_INTERVAL =
      Arg.create(Amount.of(15L, Time.MINUTES));

  @CmdLine(name = "job_update_history_pruning_threshold",
      help = "Time after which the scheduler will prune completed job update history.")
  private static final Arg<Amount<Long, Time>> JOB_UPDATE_HISTORY_PRUNING_THRESHOLD =
      Arg.create(Amount.of(30L, Time.DAYS));

  @CmdLine(name = "initial_task_kill_retry_interval",
      help = "When killing a task, retry after this delay if mesos has not responded,"
          + " backing off up to transient_task_state_timeout")
  private static final Arg<Amount<Long, Time>> INITIAL_TASK_KILL_RETRY_INTERVAL =
      Arg.create(Amount.of(5L, Time.SECONDS));

  @CmdLine(name = "offer_reservation_duration", help = "Time to reserve a slave's offers while "
      + "trying to satisfy a task preempting another.")
  private static final Arg<Amount<Long, Time>> RESERVATION_DURATION =
      Arg.create(Amount.of(3L, Time.MINUTES));

  // Reconciliation may create a big surge of status updates in a large cluster. Setting the default
  // initial delay to 1 minute to ease up storage contention during scheduler start up.
  @CmdLine(name = "reconciliation_initial_delay",
      help = "Initial amount of time to delay task reconciliation after scheduler start up.")
  private static final Arg<Amount<Long, Time>> RECONCILIATION_INITIAL_DELAY =
      Arg.create(Amount.of(1L, Time.MINUTES));

  @Positive
  @CmdLine(name = "reconciliation_explicit_interval",
      help = "Interval on which scheduler will ask Mesos for status updates of all non-terminal "
      + "tasks known to scheduler.")
  private static final Arg<Amount<Long, Time>> RECONCILIATION_EXPLICIT_INTERVAL =
      Arg.create(Amount.of(60L, Time.MINUTES));

  @Positive
  @CmdLine(name = "reconciliation_implicit_interval",
      help = "Interval on which scheduler will ask Mesos for status updates of all non-terminal "
          + "tasks known to Mesos.")
  private static final Arg<Amount<Long, Time>> RECONCILIATION_IMPLICIT_INTERVAL =
      Arg.create(Amount.of(60L, Time.MINUTES));

  @CmdLine(name = "reconciliation_schedule_spread",
      help = "Difference between explicit and implicit reconciliation intervals intended to "
          + "create a non-overlapping task reconciliation schedule.")
  private static final Arg<Amount<Long, Time>> RECONCILIATION_SCHEDULE_SPREAD =
      Arg.create(Amount.of(30L, Time.MINUTES));

  @Qualifier
  @Target({ FIELD, PARAMETER, METHOD }) @Retention(RUNTIME)
  private @interface AsyncExecutor { }

  @VisibleForTesting
  static final String TIMEOUT_QUEUE_GAUGE = "timeout_queue_size";

  @VisibleForTesting
  static final String ASYNC_TASKS_GAUGE = "async_tasks_completed";

  @Override
  protected void configure() {
    // Don't worry about clean shutdown, these can be daemon and cleanup-free.
    final ScheduledThreadPoolExecutor executor =
        AsyncUtil.loggingScheduledExecutor(ASYNC_WORKER_THREADS.get(), "AsyncProcessor-%d", LOG);
    bind(ScheduledThreadPoolExecutor.class).annotatedWith(AsyncExecutor.class).toInstance(executor);
    SchedulerServicesModule.addAppStartupServiceBinding(binder()).to(RegisterGauges.class);

    // AsyncModule itself is not a subclass of PrivateModule because TaskEventModule internally uses
    // a MultiBinder, which cannot span multiple injectors.
    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(new TypeLiteral<Amount<Long, Time>>() { })
            .toInstance(TRANSIENT_TASK_STATE_TIMEOUT.get());
        bind(ScheduledExecutorService.class).toInstance(executor);

        bind(TaskTimeout.class).in(Singleton.class);
        expose(TaskTimeout.class);
      }
    });
    PubsubEventModule.bindSubscriber(binder(), TaskTimeout.class);
    SchedulerServicesModule.addSchedulerActiveServiceBinding(binder()).to(TaskTimeout.class);

    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(TaskGroupsSettings.class).toInstance(new TaskGroupsSettings(
            FIRST_SCHEDULE_DELAY.get(),
            new TruncatedBinaryBackoff(
                INITIAL_SCHEDULE_PENALTY.get(),
                MAX_SCHEDULE_PENALTY.get()),
            RateLimiter.create(MAX_SCHEDULE_ATTEMPTS_PER_SEC.get())));

        bind(RescheduleCalculatorImpl.RescheduleCalculatorSettings.class)
            .toInstance(new RescheduleCalculatorImpl.RescheduleCalculatorSettings(
                new TruncatedBinaryBackoff(INITIAL_FLAPPING_DELAY.get(), MAX_FLAPPING_DELAY.get()),
                FLAPPING_THRESHOLD.get(),
                MAX_RESCHEDULING_DELAY.get()));

        bind(RescheduleCalculator.class).to(RescheduleCalculatorImpl.class).in(Singleton.class);
        expose(RescheduleCalculator.class);
        bind(TaskGroups.class).in(Singleton.class);
        expose(TaskGroups.class);
      }
    });
    PubsubEventModule.bindSubscriber(binder(), TaskGroups.class);

    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(new TypeLiteral<BiCache<String, TaskGroupKey>>() { }).in(Singleton.class);
        bind(BiCacheSettings.class).toInstance(
            new BiCacheSettings(RESERVATION_DURATION.get(), "reservation_cache_size"));
        bind(TaskScheduler.class).to(TaskSchedulerImpl.class);
        bind(TaskSchedulerImpl.class).in(Singleton.class);
        expose(TaskScheduler.class);
      }
    });
    PubsubEventModule.bindSubscriber(binder(), TaskScheduler.class);

    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(OfferReturnDelay.class).toInstance(
            new RandomJitterReturnDelay(
                MIN_OFFER_HOLD_TIME.get().as(Time.MILLISECONDS),
                OFFER_HOLD_JITTER_WINDOW.get().as(Time.MILLISECONDS),
                new Random.SystemRandom(new java.util.Random())));
        bind(ScheduledExecutorService.class).toInstance(executor);
        bind(OfferManager.class).to(OfferManagerImpl.class);
        bind(OfferManagerImpl.class).in(Singleton.class);
        expose(OfferManager.class);
      }
    });
    PubsubEventModule.bindSubscriber(binder(), OfferManager.class);

    install(new PrivateModule() {
      @Override
      protected void configure() {
        // TODO(ksweeney): Create a configuration validator module so this can be injected.
        // TODO(William Farner): Revert this once large task counts is cheap ala hierarchichal store
        bind(HistoryPrunnerSettings.class).toInstance(new HistoryPrunnerSettings(
            HISTORY_PRUNE_THRESHOLD.get(),
            HISTORY_MIN_RETENTION_THRESHOLD.get(),
            HISTORY_MAX_PER_JOB_THRESHOLD.get()
        ));
        bind(ScheduledExecutorService.class).toInstance(executor);

        bind(TaskHistoryPruner.class).in(Singleton.class);
        expose(TaskHistoryPruner.class);
      }
    });
    PubsubEventModule.bindSubscriber(binder(), TaskHistoryPruner.class);

    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(ScheduledExecutorService.class).toInstance(executor);
        bind(TaskThrottler.class).in(Singleton.class);
        expose(TaskThrottler.class);
      }
    });
    PubsubEventModule.bindSubscriber(binder(), TaskThrottler.class);

    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(TaskReconcilerSettings.class).toInstance(new TaskReconcilerSettings(
            RECONCILIATION_INITIAL_DELAY.get(),
            RECONCILIATION_EXPLICIT_INTERVAL.get(),
            RECONCILIATION_IMPLICIT_INTERVAL.get(),
            RECONCILIATION_SCHEDULE_SPREAD.get()));
        bind(ScheduledExecutorService.class).toInstance(executor);
        bind(TaskReconciler.class).in(Singleton.class);
        expose(TaskReconciler.class);
      }
    });
    SchedulerServicesModule.addSchedulerActiveServiceBinding(binder()).to(TaskReconciler.class);

    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(JobUpdateHistoryPruner.HistoryPrunerSettings.class).toInstance(
            new JobUpdateHistoryPruner.HistoryPrunerSettings(
                JOB_UPDATE_HISTORY_PRUNING_INTERVAL.get(),
                JOB_UPDATE_HISTORY_PRUNING_THRESHOLD.get(),
                JOB_UPDATE_HISTORY_PER_JOB_THRESHOLD.get()));

        bind(ScheduledExecutorService.class).toInstance(
            AsyncUtil.singleThreadLoggingScheduledExecutor("JobUpdatePruner-%d", LOG));

        bind(JobUpdateHistoryPruner.class).in(Singleton.class);
        expose(JobUpdateHistoryPruner.class);
      }
    });
    SchedulerServicesModule.addSchedulerActiveServiceBinding(binder())
        .to(JobUpdateHistoryPruner.class);

    install(new PrivateModule() {
      @Override
      protected void configure() {
        bind(ScheduledExecutorService.class).toInstance(executor);
        bind(BackoffStrategy.class).toInstance(
            new TruncatedBinaryBackoff(
                INITIAL_TASK_KILL_RETRY_INTERVAL.get(),
                TRANSIENT_TASK_STATE_TIMEOUT.get()));
        bind(KillRetry.class).in(Singleton.class);
        expose(KillRetry.class);
      }
    });
    PubsubEventModule.bindSubscriber(binder(), KillRetry.class);
  }

  static class RegisterGauges extends AbstractIdleService {
    private final StatsProvider statsProvider;
    private final ScheduledThreadPoolExecutor executor;

    @Inject
    RegisterGauges(
        StatsProvider statsProvider,
        @AsyncExecutor ScheduledThreadPoolExecutor executor) {

      this.statsProvider = requireNonNull(statsProvider);
      this.executor = requireNonNull(executor);
    }

    @Override
    protected void startUp() {
      statsProvider.makeGauge(
          TIMEOUT_QUEUE_GAUGE,
          new Supplier<Integer>() {
            @Override
            public Integer get() {
              return executor.getQueue().size();
            }
          });
      statsProvider.makeGauge(
          ASYNC_TASKS_GAUGE,
          new Supplier<Long>() {
            @Override
            public Long get() {
              return executor.getCompletedTaskCount();
            }
          }
      );
    }

    @Override
    protected void shutDown() {
      // Nothing to do - await VM shutdown.
    }
  }
}
