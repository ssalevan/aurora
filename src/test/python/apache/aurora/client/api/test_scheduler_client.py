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

import inspect
import time
import unittest

import mock
import pytest
from mox import IgnoreArg, IsA, Mox
from requests.auth import AuthBase
from thrift.transport import TTransport
from twitter.common.quantity import Amount, Time
from twitter.common.zookeeper.kazoo_client import TwitterKazooClient
from twitter.common.zookeeper.serverset.endpoint import ServiceInstance

import apache.aurora.client.api.scheduler_client as scheduler_client
from apache.aurora.common.cluster import Cluster
from apache.aurora.common.transport import TRequestsTransport

import gen.apache.aurora.api.AuroraAdmin as AuroraAdmin
import gen.apache.aurora.api.AuroraSchedulerManager as AuroraSchedulerManager
from gen.apache.aurora.api.constants import THRIFT_API_VERSION
from gen.apache.aurora.api.ttypes import (
    Hosts,
    JobConfiguration,
    JobKey,
    JobUpdateQuery,
    JobUpdateRequest,
    Lock,
    LockValidation,
    ResourceAggregate,
    Response,
    ResponseCode,
    ResponseDetail,
    RewriteConfigsRequest,
    ScheduleStatus,
    ServerInfo,
    SessionKey,
    TaskQuery
)

ROLE = 'foorole'
JOB_NAME = 'barjobname'
JOB_ENV = 'devel'
JOB_KEY = JobKey(role=ROLE, environment=JOB_ENV, name=JOB_NAME)
DEFAULT_RESPONSE = Response(serverInfo=ServerInfo(thriftAPIVersion=THRIFT_API_VERSION))


def test_coverage():
  """Make sure a new thrift RPC doesn't get added without minimal test coverage."""
  for name, klass in inspect.getmembers(AuroraAdmin) + inspect.getmembers(AuroraSchedulerManager):
    if name.endswith('_args'):
      rpc_name = name[:-len('_args')]
      assert hasattr(TestSchedulerProxyAdminInjection, 'test_%s' % rpc_name), (
          'No test defined for RPC %s' % rpc_name)


SESSION = SessionKey(mechanism='test', data='test')


class TestSchedulerProxy(scheduler_client.SchedulerProxy):
  """In testing we shouldn't use the real SSHAgentAuthenticator."""

  def session_key(self):
    return SESSION


class TestSchedulerProxyInjection(unittest.TestCase):
  def setUp(self):
    self.mox = Mox()

    self.mox.StubOutClassWithMocks(AuroraAdmin, 'Client')
    self.mox.StubOutClassWithMocks(scheduler_client, 'SchedulerClient')

    self.mock_scheduler_client = self.mox.CreateMock(scheduler_client.SchedulerClient)
    self.mock_thrift_client = self.mox.CreateMock(AuroraAdmin.Client)

    scheduler_client.SchedulerClient.get(IgnoreArg(), verbose=IgnoreArg()).AndReturn(
        self.mock_scheduler_client)
    self.mock_scheduler_client.get_thrift_client().AndReturn(self.mock_thrift_client)

  def tearDown(self):
    self.mox.UnsetStubs()
    self.mox.VerifyAll()

  def make_scheduler_proxy(self):
    return TestSchedulerProxy(Cluster(name='local'))

  def test_startCronJob(self):
    self.mock_thrift_client.startCronJob(IsA(JobKey), SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().startCronJob(JOB_KEY)

  def test_createJob(self):
    self.mock_thrift_client.createJob(
        IsA(JobConfiguration),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().createJob(JobConfiguration())

  def test_replaceCronTemplate(self):
    self.mock_thrift_client.replaceCronTemplate(
        IsA(JobConfiguration),
        IsA(Lock),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().replaceCronTemplate(JobConfiguration(), Lock())

  def test_scheduleCronJob(self):
    self.mock_thrift_client.scheduleCronJob(
        IsA(JobConfiguration),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().scheduleCronJob(JobConfiguration())

  def test_descheduleCronJob(self):
    self.mock_thrift_client.descheduleCronJob(
        IsA(JobKey),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().descheduleCronJob(JOB_KEY)

  def test_populateJobConfig(self):
    self.mock_thrift_client.populateJobConfig(IsA(JobConfiguration)).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().populateJobConfig(JobConfiguration())

  def test_restartShards(self):
    self.mock_thrift_client.restartShards(
        IsA(JobKey),
        IgnoreArg(),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().restartShards(JOB_KEY, set([0]))

  def test_getTasksStatus(self):
    self.mock_thrift_client.getTasksStatus(IsA(TaskQuery)).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().getTasksStatus(TaskQuery())

  def test_getJobs(self):
    self.mock_thrift_client.getJobs(IgnoreArg()).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().getJobs(ROLE)

  def test_killTasks(self):
    self.mock_thrift_client.killTasks(IsA(TaskQuery), SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().killTasks(TaskQuery())

  def test_getQuota(self):
    self.mock_thrift_client.getQuota(IgnoreArg()).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().getQuota(ROLE)

  def test_api_version_mismatch(self):
    resp = Response(serverInfo=ServerInfo(thriftAPIVersion=THRIFT_API_VERSION + 1))
    self.mock_thrift_client.getQuota(IgnoreArg()).AndReturn(resp)
    self.mox.ReplayAll()
    with pytest.raises(scheduler_client.SchedulerProxy.ThriftInternalError):
      self.make_scheduler_proxy().getQuota(ROLE)

  def test_addInstances(self):
    self.mock_thrift_client.addInstances(
      IsA(JobKey),
      IgnoreArg(),
      IsA(Lock),
      SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().addInstances(JobKey(), {}, Lock())

  def test_acquireLock(self):
    self.mock_thrift_client.acquireLock(IsA(Lock), SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().acquireLock(Lock())

  def test_releaseLock(self):
    self.mock_thrift_client.releaseLock(
        IsA(Lock),
        IsA(LockValidation),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().releaseLock(Lock(), LockValidation())

  def test_getJobUpdateSummaries(self):
    self.mock_thrift_client.getJobUpdateSummaries(IsA(JobUpdateQuery)).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().getJobUpdateSummaries(JobUpdateQuery())

  def test_getJobUpdateDetails(self):
    self.mock_thrift_client.getJobUpdateDetails('update_id').AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().getJobUpdateDetails('update_id')

  def test_startJobUpdate(self):
    self.mock_thrift_client.startJobUpdate(
        IsA(JobUpdateRequest),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().startJobUpdate(JobUpdateRequest())

  def test_pauseJobUpdate(self):
    self.mock_thrift_client.pauseJobUpdate('update_id', SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().pauseJobUpdate('update_id')

  def test_resumeJobUpdate(self):
    self.mock_thrift_client.resumeJobUpdate(
        'update_id',
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().resumeJobUpdate('update_id')

  def test_abortJobUpdate(self):
    self.mock_thrift_client.abortJobUpdate('update_id', SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().abortJobUpdate('update_id')

  def test_pulseJobUpdate(self):
    self.mock_thrift_client.pulseJobUpdate('update_id', SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().pulseJobUpdate('update_id')

  def test_raise_auth_error(self):
    self.mock_thrift_client.killTasks(TaskQuery(), None, None, SESSION).AndRaise(
        TRequestsTransport.AuthError())
    self.mox.ReplayAll()
    with pytest.raises(scheduler_client.SchedulerProxy.AuthError):
      self.make_scheduler_proxy().killTasks(TaskQuery(), None, None)


class TestSchedulerProxyAdminInjection(TestSchedulerProxyInjection):
  def test_startMaintenance(self):
    self.mock_thrift_client.startMaintenance(
      IsA(Hosts),
      SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().startMaintenance(Hosts())

  def test_drainHosts(self):
    self.mock_thrift_client.drainHosts(IsA(Hosts), SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().drainHosts(Hosts())

  def test_maintenanceStatus(self):
    self.mock_thrift_client.maintenanceStatus(
      IsA(Hosts),
      SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().maintenanceStatus(Hosts())

  def test_endMaintenance(self):
    self.mock_thrift_client.endMaintenance(IsA(Hosts), SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().endMaintenance(Hosts())

  def test_setQuota(self):
    self.mock_thrift_client.setQuota(
        IgnoreArg(),
        IsA(ResourceAggregate),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().setQuota(ROLE, ResourceAggregate())

  def test_forceTaskState(self):
    self.mock_thrift_client.forceTaskState(
        'taskid',
        IgnoreArg(),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().forceTaskState('taskid', ScheduleStatus.LOST)

  def test_performBackup(self):
    self.mock_thrift_client.performBackup(SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().performBackup()

  def test_listBackups(self):
    self.mock_thrift_client.listBackups(SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().listBackups()

  def test_stageRecovery(self):
    self.mock_thrift_client.stageRecovery(
        IsA(TaskQuery),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().stageRecovery(TaskQuery())

  def test_queryRecovery(self):
    self.mock_thrift_client.queryRecovery(
      IsA(TaskQuery),
      SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().queryRecovery(TaskQuery())

  def test_deleteRecoveryTasks(self):
    self.mock_thrift_client.deleteRecoveryTasks(
        IsA(TaskQuery),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().deleteRecoveryTasks(TaskQuery())

  def test_commitRecovery(self):
    self.mock_thrift_client.commitRecovery(SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().commitRecovery()

  def test_unloadRecovery(self):
    self.mock_thrift_client.unloadRecovery(SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().unloadRecovery()

  def test_snapshot(self):
    self.mock_thrift_client.snapshot(SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().snapshot()

  def test_rewriteConfigs(self):
    self.mock_thrift_client.rewriteConfigs(
        IsA(RewriteConfigsRequest),
        SESSION).AndReturn(DEFAULT_RESPONSE)
    self.mox.ReplayAll()
    self.make_scheduler_proxy().rewriteConfigs(RewriteConfigsRequest())


def mock_auth():
  return mock.create_autospec(spec=AuthBase, instance=True)


@pytest.mark.parametrize('scheme', ('http', 'https'))
def test_url_when_not_connected_and_cluster_has_no_proxy_url(scheme):
  host = 'some-host.example.com'
  port = 31181

  mock_zk = mock.create_autospec(spec=TwitterKazooClient, instance=True)

  service_json = '''{
    "additionalEndpoints": {
        "%(scheme)s": {
            "host": "%(host)s",
            "port": %(port)d
        }
    },
    "serviceEndpoint": {
        "host": "%(host)s",
        "port": %(port)d
    },
    "shard": 0,
    "status": "ALIVE"
  }''' % dict(host=host, port=port, scheme=scheme)

  service_endpoints = [ServiceInstance.unpack(service_json)]

  def make_mock_client(proxy_url):
    client = scheduler_client.ZookeeperSchedulerClient(
        Cluster(proxy_url=proxy_url),
        auth=None,
        user_agent='Some-User-Agent',
        _deadline=lambda x, **kws: x())
    client.get_scheduler_serverset = mock.MagicMock(return_value=(mock_zk, service_endpoints))
    client.SERVERSET_TIMEOUT = Amount(0, Time.SECONDS)
    client._connect_scheduler = mock.MagicMock()
    return client

  client = make_mock_client(proxy_url=None)
  assert client.url == '%s://%s:%d' % (scheme, host, port)
  assert client.url == client.raw_url
  client._connect_scheduler.assert_has_calls([])

  client = make_mock_client(proxy_url='https://scheduler.proxy')
  assert client.url == 'https://scheduler.proxy'
  assert client.raw_url == '%s://%s:%d' % (scheme, host, port)
  client._connect_scheduler.assert_has_calls([])

  client = make_mock_client(proxy_url=None)
  client.get_thrift_client()
  assert client.url == '%s://%s:%d' % (scheme, host, port)
  client._connect_scheduler.assert_has_calls([mock.call('%s://%s:%d/api' % (scheme, host, port))])
  client._connect_scheduler.reset_mock()
  client.get_thrift_client()
  client._connect_scheduler.assert_has_calls([])


@mock.patch('apache.aurora.client.api.scheduler_client.TRequestsTransport', spec=TRequestsTransport)
def test_connect_scheduler(mock_client):
  mock_client.return_value.open.side_effect = [TTransport.TTransportException, True]
  mock_time = mock.create_autospec(spec=time, instance=True)

  client = scheduler_client.SchedulerClient(mock_auth(), 'Some-User-Agent', verbose=True)
  client._connect_scheduler('https://scheduler.example.com:1337', mock_time)

  assert mock_client.return_value.open.has_calls(mock.call(), mock.call())
  mock_time.sleep.assert_called_once_with(
      scheduler_client.SchedulerClient.RETRY_TIMEOUT.as_(Time.SECONDS))


@mock.patch('apache.aurora.client.api.scheduler_client.TRequestsTransport', spec=TRequestsTransport)
def test_connect_scheduler_with_user_agent(mock_transport):
  mock_transport.return_value.open.side_effect = [TTransport.TTransportException, True]
  mock_time = mock.create_autospec(spec=time, instance=True)

  auth = mock_auth()
  user_agent = 'Some-User-Agent'

  client = scheduler_client.SchedulerClient(auth, user_agent, verbose=True)

  uri = 'https://scheduler.example.com:1337'
  client._connect_scheduler(uri, mock_time)

  mock_transport.assert_called_once_with(uri, auth=auth, user_agent=user_agent)


@mock.patch('apache.aurora.client.api.scheduler_client.SchedulerClient',
            spec=scheduler_client.SchedulerClient)
@mock.patch('threading._Event.wait')
def test_transient_error(_, client):
  mock_scheduler_client = mock.create_autospec(
      spec=scheduler_client.SchedulerClient,
      spec_set=False,
      instance=True)
  mock_thrift_client = mock.create_autospec(spec=AuroraAdmin.Client, instance=True)
  mock_thrift_client.killTasks.side_effect = [
      Response(responseCode=ResponseCode.ERROR_TRANSIENT,
               details=[ResponseDetail(message="message1"), ResponseDetail(message="message2")],
               serverInfo=DEFAULT_RESPONSE.serverInfo),
      Response(responseCode=ResponseCode.ERROR_TRANSIENT,
               serverInfo=DEFAULT_RESPONSE.serverInfo),
      Response(responseCode=ResponseCode.OK,
               serverInfo=DEFAULT_RESPONSE.serverInfo)]

  mock_scheduler_client.get_thrift_client.return_value = mock_thrift_client
  client.get.return_value = mock_scheduler_client

  proxy = TestSchedulerProxy(Cluster(name='local'))
  proxy.killTasks(TaskQuery(), None)

  assert mock_thrift_client.killTasks.call_count == 3


@mock.patch('apache.aurora.client.api.scheduler_client.TRequestsTransport', spec=TRequestsTransport)
def test_connect_direct_scheduler_with_user_agent(mock_transport):
  mock_transport.return_value.open.side_effect = [TTransport.TTransportException, True]
  mock_time = mock.create_autospec(spec=time, instance=True)

  user_agent = 'Some-User-Agent'
  uri = 'https://scheduler.example.com:1337'

  client = scheduler_client.DirectSchedulerClient(
      uri,
      auth=None,
      verbose=True,
      user_agent=user_agent)

  client._connect_scheduler(uri, mock_time)

  mock_transport.assert_called_once_with(uri, auth=None, user_agent=user_agent)


@mock.patch('apache.aurora.client.api.scheduler_client.TRequestsTransport', spec=TRequestsTransport)
def test_connect_zookeeper_client_with_auth(mock_transport):
  mock_transport.return_value.open.side_effect = [TTransport.TTransportException, True]
  mock_time = mock.create_autospec(spec=time, instance=True)

  user_agent = 'Some-User-Agent'
  uri = 'https://scheduler.example.com:1337'
  auth = mock_auth()
  cluster = Cluster(zk='zk', zk_port='2181')

  def auth_factory(_):
    return auth

  client = scheduler_client.SchedulerClient.get(
      cluster,
      auth_factory=auth_factory,
      user_agent=user_agent)

  client._connect_scheduler(uri, mock_time)

  mock_transport.assert_called_once_with(uri, auth=auth, user_agent=user_agent)


@mock.patch('apache.aurora.client.api.scheduler_client.TRequestsTransport', spec=TRequestsTransport)
def test_connect_direct_client_with_auth(mock_transport):
  mock_transport.return_value.open.side_effect = [TTransport.TTransportException, True]
  mock_time = mock.create_autospec(spec=time, instance=True)

  user_agent = 'Some-User-Agent'
  uri = 'https://scheduler.example.com:1337'
  auth = mock_auth()
  cluster = Cluster(scheduler_uri='uri')

  def auth_factory(_):
    return auth

  client = scheduler_client.SchedulerClient.get(
      cluster,
      auth_factory=auth_factory,
      user_agent=user_agent)

  client._connect_scheduler(uri, mock_time)

  mock_transport.assert_called_once_with(uri, auth=auth, user_agent=user_agent)
