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
package org.apache.aurora.scheduler.storage.db;

import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.twitter.common.base.MorePreconditions;

import org.apache.aurora.gen.JobUpdate;
import org.apache.aurora.gen.JobUpdateInstructions;
import org.apache.aurora.gen.JobUpdateStatus;
import org.apache.aurora.gen.storage.StoredJobUpdateDetails;
import org.apache.aurora.scheduler.stats.CachedCounters;
import org.apache.aurora.scheduler.storage.JobUpdateStore;
import org.apache.aurora.scheduler.storage.entities.IInstanceTaskConfig;
import org.apache.aurora.scheduler.storage.entities.IJobInstanceUpdateEvent;
import org.apache.aurora.scheduler.storage.entities.IJobUpdate;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateDetails;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateEvent;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateInstructions;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateKey;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateQuery;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateSummary;
import org.apache.aurora.scheduler.storage.entities.IRange;

import static java.util.Objects.requireNonNull;

import static com.twitter.common.inject.TimedInterceptor.Timed;

/**
 * A relational database-backed job update store.
 */
public class DbJobUpdateStore implements JobUpdateStore.Mutable {

  private final JobKeyMapper jobKeyMapper;
  private final JobUpdateDetailsMapper detailsMapper;
  private final JobUpdateEventMapper jobEventMapper;
  private final JobInstanceUpdateEventMapper instanceEventMapper;
  private final CachedCounters stats;

  @Inject
  DbJobUpdateStore(
      JobKeyMapper jobKeyMapper,
      JobUpdateDetailsMapper detailsMapper,
      JobUpdateEventMapper jobEventMapper,
      JobInstanceUpdateEventMapper instanceEventMapper,
      CachedCounters stats) {

    this.jobKeyMapper = requireNonNull(jobKeyMapper);
    this.detailsMapper = requireNonNull(detailsMapper);
    this.jobEventMapper = requireNonNull(jobEventMapper);
    this.instanceEventMapper = requireNonNull(instanceEventMapper);
    this.stats = requireNonNull(stats);
  }

  @Timed("job_update_store_save_update")
  @Override
  public void saveJobUpdate(IJobUpdate update, Optional<String> lockToken) {
    requireNonNull(update);
    if (!update.getInstructions().isSetDesiredState()
        && update.getInstructions().getInitialState().isEmpty()) {
      throw new IllegalArgumentException(
          "Missing both initial and desired states. At least one is required.");
    }

    IJobUpdateKey key = update.getSummary().getKey();
    jobKeyMapper.merge(key.getJob());
    detailsMapper.insert(update.newBuilder());

    if (lockToken.isPresent()) {
      detailsMapper.insertLockToken(key, lockToken.get());
    }

    // Insert optional instance update overrides.
    Set<IRange> instanceOverrides =
        update.getInstructions().getSettings().getUpdateOnlyTheseInstances();

    if (!instanceOverrides.isEmpty()) {
      detailsMapper.insertInstanceOverrides(key, IRange.toBuildersSet(instanceOverrides));
    }

    // Insert desired state task config and instance mappings.
    if (update.getInstructions().isSetDesiredState()) {
      IInstanceTaskConfig desired = update.getInstructions().getDesiredState();
      detailsMapper.insertTaskConfig(
          key,
          desired.getTask().newBuilder(),
          true,
          new InsertResult());

      detailsMapper.insertDesiredInstances(
          key,
          IRange.toBuildersSet(MorePreconditions.checkNotBlank(desired.getInstances())));
    }

    // Insert initial state task configs and instance mappings.
    if (!update.getInstructions().getInitialState().isEmpty()) {
      for (IInstanceTaskConfig config : update.getInstructions().getInitialState()) {
        InsertResult result = new InsertResult();
        detailsMapper.insertTaskConfig(key, config.getTask().newBuilder(), false, result);

        detailsMapper.insertTaskConfigInstances(
            result.getId(),
            IRange.toBuildersSet(MorePreconditions.checkNotBlank(config.getInstances())));
      }
    }
  }

  @VisibleForTesting
  static String statName(JobUpdateStatus status) {
    return "update_transition_" + status;
  }

  @Timed("job_update_store_save_event")
  @Override
  public void saveJobUpdateEvent(IJobUpdateKey key, IJobUpdateEvent event) {
    stats.get(statName(event.getStatus())).incrementAndGet();
    jobEventMapper.insert(key, event.newBuilder());
  }

  @Timed("job_update_store_save_instance_event")
  @Override
  public void saveJobInstanceUpdateEvent(IJobUpdateKey key, IJobInstanceUpdateEvent event) {
    instanceEventMapper.insert(key, event.newBuilder());
  }

  @Timed("job_update_store_delete_all")
  @Override
  public void deleteAllUpdatesAndEvents() {
    detailsMapper.truncate();
  }

  private static final Function<PruneVictim, Long> GET_ROW_ID = new Function<PruneVictim, Long>() {
    @Override
    public Long apply(PruneVictim victim) {
      return victim.getRowId();
    }
  };

  private static final Function<PruneVictim, IJobUpdateKey> GET_UPDATE_KEY =
      new Function<PruneVictim, IJobUpdateKey>() {
        @Override
        public IJobUpdateKey apply(PruneVictim victim) {
          return IJobUpdateKey.build(victim.getUpdate());
        }
      };

  @Timed("job_update_store_prune_history")
  @Override
  public Set<IJobUpdateKey> pruneHistory(int perJobRetainCount, long historyPruneThresholdMs) {
    ImmutableSet.Builder<IJobUpdateKey> pruned = ImmutableSet.builder();

    Set<Long> jobKeyIdsToPrune = detailsMapper.selectJobKeysForPruning(
        perJobRetainCount,
        historyPruneThresholdMs);

    for (long jobKeyId : jobKeyIdsToPrune) {
      Set<PruneVictim> pruneVictims = detailsMapper.selectPruneVictims(
          jobKeyId,
          perJobRetainCount,
          historyPruneThresholdMs);

      detailsMapper.deleteCompletedUpdates(
          FluentIterable.from(pruneVictims).transform(GET_ROW_ID).toSet());
      pruned.addAll(FluentIterable.from(pruneVictims).transform(GET_UPDATE_KEY));
    }

    return pruned.build();
  }

  @Timed("job_update_store_fetch_summaries")
  @Override
  public List<IJobUpdateSummary> fetchJobUpdateSummaries(IJobUpdateQuery query) {
    return IJobUpdateSummary.listFromBuilders(detailsMapper.selectSummaries(query.newBuilder()));
  }

  @Timed("job_update_store_fetch_details_list")
  @Override
  public List<IJobUpdateDetails> fetchJobUpdateDetails(IJobUpdateQuery query) {
    return FluentIterable
        .from(detailsMapper.selectDetailsList(query.newBuilder()))
        .transform(new Function<StoredJobUpdateDetails, IJobUpdateDetails>() {
          @Override
          public IJobUpdateDetails apply(StoredJobUpdateDetails input) {
            return IJobUpdateDetails.build(input.getDetails());
          }
        }).toList();
  }

  @Timed("job_update_store_fetch_details")
  @Override
  public Optional<IJobUpdateDetails> fetchJobUpdateDetails(final IJobUpdateKey key) {
    return Optional.fromNullable(detailsMapper.selectDetails(key))
        .transform(new Function<StoredJobUpdateDetails, IJobUpdateDetails>() {
          @Override
          public IJobUpdateDetails apply(StoredJobUpdateDetails input) {
            return IJobUpdateDetails.build(input.getDetails());
          }
        });
  }

  @Timed("job_update_store_fetch_update")
  @Override
  public Optional<IJobUpdate> fetchJobUpdate(IJobUpdateKey key) {
    return Optional.fromNullable(detailsMapper.selectUpdate(key))
        .transform(new Function<JobUpdate, IJobUpdate>() {
          @Override
          public IJobUpdate apply(JobUpdate input) {
            return IJobUpdate.build(input);
          }
        });
  }

  @Timed("job_update_store_fetch_instructions")
  @Override
  public Optional<IJobUpdateInstructions> fetchJobUpdateInstructions(IJobUpdateKey key) {
    return Optional.fromNullable(detailsMapper.selectInstructions(key))
        .transform(new Function<JobUpdateInstructions, IJobUpdateInstructions>() {
          @Override
          public IJobUpdateInstructions apply(JobUpdateInstructions input) {
            return IJobUpdateInstructions.build(input);
          }
        });
  }

  @Timed("job_update_store_fetch_all_details")
  @Override
  public Set<StoredJobUpdateDetails> fetchAllJobUpdateDetails() {
    return ImmutableSet.copyOf(detailsMapper.selectAllDetails());
  }

  @Timed("job_update_store_get_lock_token")
  @Override
  public Optional<String> getLockToken(IJobUpdateKey key) {
    // We assume here that cascading deletes will cause a lock-update associative row to disappear
    // when the lock is invalidated.  This further assumes that a lock row is deleted when a lock
    // is no longer valid.
    return Optional.fromNullable(detailsMapper.selectLockToken(key));
  }

  @Timed("job_update_store_fetch_instance_events")
  @Override
  public List<IJobInstanceUpdateEvent> fetchInstanceEvents(IJobUpdateKey key, int instanceId) {
    return IJobInstanceUpdateEvent.listFromBuilders(
        detailsMapper.selectInstanceUpdateEvents(key, instanceId));
  }
}
