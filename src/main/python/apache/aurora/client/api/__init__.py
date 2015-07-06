#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

from __future__ import print_function

from twitter.common import log

from apache.aurora.client.base import combine_messages
from apache.aurora.common.aurora_job_key import AuroraJobKey
from apache.aurora.common.auth.auth_module_manager import make_session_key
from apache.aurora.common.cluster import Cluster

from .restarter import Restarter
from .scheduler_client import SchedulerProxy
from .sla import Sla
from .updater import Updater
from .updater_util import UpdaterConfig

from gen.apache.aurora.api.constants import LIVE_STATES
from gen.apache.aurora.api.ttypes import (
    JobKey,
    JobUpdateKey,
    JobUpdateQuery,
    JobUpdateRequest,
    Lock,
    ResourceAggregate,
    ResponseCode,
    TaskQuery
)


class AuroraClientAPI(object):
  """This class provides the API to talk to the twitter scheduler"""

  class Error(Exception): pass
  class ClusterMismatch(Error, ValueError): pass
  class ThriftInternalError(Error): pass
  class UpdateConfigError(Error): pass

  def __init__(
      self,
      cluster,
      user_agent,
      verbose=False,
      session_key_factory=make_session_key):

    if not isinstance(cluster, Cluster):
      raise TypeError('AuroraClientAPI expects instance of Cluster for "cluster", got %s' %
          type(cluster))
    self._scheduler_proxy = SchedulerProxy(
        cluster,
        verbose=verbose,
        session_key_factory=session_key_factory,
        user_agent=user_agent)
    self._cluster = cluster

  @property
  def cluster(self):
    return self._cluster

  @property
  def scheduler_proxy(self):
    return self._scheduler_proxy

  def create_job(self, config, lock=None):
    log.info('Creating job %s' % config.name())
    log.debug('Full configuration: %s' % config.job())
    log.debug('Lock %s' % lock)
    return self._scheduler_proxy.createJob(config.job(), lock)

  def schedule_cron(self, config, lock=None):
    log.info("Registering job %s with cron" % config.name())
    log.debug('Full configuration: %s' % config.job())
    log.debug('Lock %s' % lock)
    return self._scheduler_proxy.scheduleCronJob(config.job(), lock)

  def deschedule_cron(self, jobkey, lock=None):
    log.info("Removing cron schedule for job %s" % jobkey)
    return self._scheduler_proxy.descheduleCronJob(jobkey.to_thrift(), lock)

  def populate_job_config(self, config):
    return self._scheduler_proxy.populateJobConfig(config.job())

  def start_cronjob(self, job_key):
    self._assert_valid_job_key(job_key)

    log.info("Starting cron job: %s" % job_key)
    return self._scheduler_proxy.startCronJob(job_key.to_thrift())

  def get_jobs(self, role):
    log.info("Retrieving jobs for role %s" % role)
    return self._scheduler_proxy.getJobs(role)

  def kill_job(self, job_key, instances=None, lock=None):
    log.info("Killing tasks for job: %s" % job_key)
    self._assert_valid_job_key(job_key)

    # Leave query.owner.user unset so the query doesn't filter jobs only submitted by a particular
    # user.
    query = job_key.to_thrift_query()
    if instances is not None:
      log.info("Instances to be killed: %s" % instances)
      query.instanceIds = frozenset([int(s) for s in instances])
    return self._scheduler_proxy.killTasks(query, lock)

  def check_status(self, job_key):
    self._assert_valid_job_key(job_key)

    log.info("Checking status of %s" % job_key)
    return self.query_no_configs(job_key.to_thrift_query())

  @classmethod
  def build_query(cls, role, job, env=None, instances=None, statuses=LIVE_STATES):
    return TaskQuery(jobKeys=[JobKey(role=role, environment=env, name=job)],
                     statuses=statuses,
                     instanceIds=instances)

  def query(self, query):
    try:
      return self._scheduler_proxy.getTasksStatus(query)
    except SchedulerProxy.ThriftInternalError as e:
      raise self.ThriftInternalError(e.args[0])

  def query_no_configs(self, query):
    """Returns all matching tasks without TaskConfig.executorConfig set."""
    try:
      return self._scheduler_proxy.getTasksWithoutConfigs(query)
    except SchedulerProxy.ThriftInternalError as e:
      raise self.ThriftInternalError(e.args[0])

  def update_job(self, config, health_check_interval_seconds=3, instances=None):
    """Run a job update for a given config, for the specified instances.  If
       instances is left unspecified, update all instances.  Returns whether or not
       the update was successful."""

    log.info("Updating job: %s" % config.name())
    updater = Updater(config, health_check_interval_seconds, self._scheduler_proxy)

    return updater.update(instances)

  def start_job_update(self, config, message, instances=None):
    """Requests Scheduler to start job update process.

    Arguments:
    config -- AuroraConfig instance with update details.
    message -- Audit message to include with the change.
    instances -- Optional list of instances to restrict update to.

    Returns response object with update ID and acquired job lock.
    """
    try:
      settings = UpdaterConfig(**config.update_config().get()).to_thrift_update_settings(instances)
    except ValueError as e:
      raise self.UpdateConfigError(str(e))

    log.info("Starting update for: %s" % config.name())
    request = JobUpdateRequest(
        instanceCount=config.instances(),
        settings=settings,
        taskConfig=config.job().taskConfig
    )

    return self._scheduler_proxy.startJobUpdate(request, message)

  def pause_job_update(self, update_key, message):
    """Requests Scheduler to pause active job update.

    Arguments:
    update_key -- Update identifier.
    message -- Audit message to include with the change.

    Returns response object.
    """
    return self._scheduler_proxy.pauseJobUpdate(update_key, message)

  def resume_job_update(self, update_key, message):
    """Requests Scheduler to resume a job update paused previously.

    Arguments:
    update_key -- Update identifier.
    message -- Audit message to include with the change.

    Returns response object.
    """
    return self._scheduler_proxy.resumeJobUpdate(update_key, message)

  def abort_job_update(self, update_key, message):
    """Requests Scheduler to abort active or paused job update.

    Arguments:
    update_key -- Update identifier.
    message -- Audit message to include with the change.

    Returns response object.
    """
    return self._scheduler_proxy.abortJobUpdate(update_key, message)

  def query_job_updates(
      self,
      role=None,
      job_key=None,
      user=None,
      update_statuses=None,
      update_key=None):
    """Returns all job updates matching the query.

    Arguments:
    role -- job role.
    job_key -- job key.
    user -- user who initiated an update.
    update_statuses -- set of JobUpdateStatus to match.
    update_key -- JobUpdateKey to match.

    Returns response object with all matching job update summaries.
    """
    # TODO(wfarner): Consider accepting JobUpdateQuery in this function instead of kwargs.
    return self._scheduler_proxy.getJobUpdateSummaries(
        JobUpdateQuery(
            role=role,
            jobKey=job_key.to_thrift() if job_key else None,
            user=user,
            updateStatuses=update_statuses,
            key=update_key))

  def get_job_update_details(self, key):
    """Gets JobUpdateDetails for the specified job update ID.

    Arguments:
    id -- job update ID.

    Returns a response object with JobUpdateDetails.
    """
    if not isinstance(key, JobUpdateKey):
      raise self.TypeError('Invalid key %r: expected %s but got %s'
                           % (key, JobUpdateKey.__name__, key.__class__.__name__))
    return self._scheduler_proxy.getJobUpdateDetails(key)

  def cancel_update(self, job_key):
    """Cancel the update represented by job_key. Returns whether or not the cancellation was
       successful."""
    self._assert_valid_job_key(job_key)

    log.info("Canceling update on job %s" % job_key)
    resp = Updater.cancel_update(self._scheduler_proxy, job_key)
    if resp.responseCode != ResponseCode.OK:
      log.error('Error cancelling the update: %s' % combine_messages(resp))
    return resp

  def restart(self, job_key, instances, updater_config, health_check_interval_seconds):
    """Perform a rolling restart of the job.

       If instances is None or [], restart all instances.  Returns the
       scheduler response for the last restarted batch of instances (which
       allows the client to show the job URL), or the status check response
       if no tasks were active.
    """
    self._assert_valid_job_key(job_key)

    return Restarter(job_key, updater_config, health_check_interval_seconds, self._scheduler_proxy
    ).restart(instances)

  def start_maintenance(self, hosts):
    log.info("Starting maintenance for: %s" % hosts.hostNames)
    return self._scheduler_proxy.startMaintenance(hosts)

  def drain_hosts(self, hosts):
    log.info("Draining tasks on: %s" % hosts.hostNames)
    return self._scheduler_proxy.drainHosts(hosts)

  def maintenance_status(self, hosts):
    log.info("Maintenance status for: %s" % hosts.hostNames)
    return self._scheduler_proxy.maintenanceStatus(hosts)

  def end_maintenance(self, hosts):
    log.info("Ending maintenance for: %s" % hosts.hostNames)
    return self._scheduler_proxy.endMaintenance(hosts)

  def get_quota(self, role):
    log.info("Getting quota for: %s" % role)
    return self._scheduler_proxy.getQuota(role)

  def set_quota(self, role, cpu, ram, disk):
    log.info("Setting quota for user:%s cpu:%f ram:%d disk: %d"
              % (role, cpu, ram, disk))
    return self._scheduler_proxy.setQuota(role, ResourceAggregate(cpu, ram, disk))

  def force_task_state(self, task_id, status):
    log.info("Requesting that task %s transition to state %s" % (task_id, status))
    return self._scheduler_proxy.forceTaskState(task_id, status)

  def perform_backup(self):
    return self._scheduler_proxy.performBackup()

  def list_backups(self):
    return self._scheduler_proxy.listBackups()

  def stage_recovery(self, backup_id):
    return self._scheduler_proxy.stageRecovery(backup_id)

  def query_recovery(self, query):
    return self._scheduler_proxy.queryRecovery(query)

  def delete_recovery_tasks(self, query):
    return self._scheduler_proxy.deleteRecoveryTasks(query)

  def commit_recovery(self):
    return self._scheduler_proxy.commitRecovery()

  def unload_recovery(self):
    return self._scheduler_proxy.unloadRecovery()

  def snapshot(self):
    return self._scheduler_proxy.snapshot()

  def unsafe_rewrite_config(self, rewrite_request):
    return self._scheduler_proxy.rewriteConfigs(rewrite_request)

  def get_locks(self):
    return self._scheduler_proxy.getLocks()

  def sla_get_job_uptime_vector(self, job_key):
    self._assert_valid_job_key(job_key)
    return Sla(self._scheduler_proxy).get_job_uptime_vector(job_key)

  def sla_get_safe_domain_vector(self, min_instance_count, hosts=None):
    return Sla(self._scheduler_proxy).get_domain_uptime_vector(
        self._cluster,
        min_instance_count,
        hosts)

  def _assert_valid_lock(self, lock):
    if not isinstance(lock, Lock):
      raise TypeError('Invalid lock %r: expected %s but got %s'
          % (lock, AuroraJobKey.__name__, lock.__class__.__name__))

  def _assert_valid_job_key(self, job_key):
    if not isinstance(job_key, AuroraJobKey):
      raise TypeError('Invalid job_key %r: expected %s but got %s'
          % (job_key, AuroraJobKey.__name__, job_key.__class__.__name__))
    if job_key.cluster != self.cluster.name:
      raise self.ClusterMismatch('job %s does not belong to cluster %s' % (job_key,
          self.cluster.name))
