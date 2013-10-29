"""HTTP interface to the Thermos TaskObserver

This modules provides an HTTP server which exposes information about Thermos tasks running on a
system. To do this, it relies heavily on the Thermos TaskObserver.

"""

import os
import mimetypes
import pkg_resources
import socket

from twitter.common import log
from twitter.common.http import HttpServer

from .file_browser import TaskObserverFileBrowser
from .json import TaskObserverJSONBindings
from .templating import HttpTemplate

from bottle import HTTPResponse

class StaticAssets(object):
  """
    Serve the /assets directory.
  """
  def __init__(self):
    self._assets = {}
    self._detect_assets()

  def _detect_assets(self):
    log.info('detecting assets...')
    assets = pkg_resources.resource_listdir(__name__, 'assets')
    cached_assets = {}
    for asset in assets:
      log.info('  detected asset: %s' % asset)
      cached_assets[asset] = pkg_resources.resource_string(
        __name__, os.path.join('assets', asset))
    self._assets = cached_assets

  @HttpServer.route("/favicon.ico")
  def handle_favicon(self):
    HttpServer.redirect("/assets/favicon.ico")

  @HttpServer.route("/assets/:filename")
  def handle_asset(self, filename):
    # TODO(wickman)  Add static_content to bottle.
    if filename in self._assets:
      mimetype, encoding = mimetypes.guess_type(filename)
      headers = {}
      if mimetype: headers['Content-Type'] = mimetype
      if encoding: headers['Content-Encoding'] = encoding
      return HTTPResponse(self._assets[filename], header=headers)
    else:
      HttpServer.abort(404, 'Unknown asset: %s' % filename)


class BottleObserver(HttpServer, StaticAssets, TaskObserverFileBrowser, TaskObserverJSONBindings):
  """
    A bottle wrapper around a Thermos TaskObserver.
  """

  def __init__(self, observer):
    self._observer = observer
    StaticAssets.__init__(self)
    TaskObserverFileBrowser.__init__(self)
    TaskObserverJSONBindings.__init__(self)
    HttpServer.__init__(self)

  @HttpServer.route("/")
  @HttpServer.view(HttpTemplate.load('index'))
  def handle_index(self):
    return dict(hostname=socket.gethostname())

  @HttpServer.route("/main")
  @HttpServer.route("/main/:type")
  @HttpServer.route("/main/:type/:offset")
  @HttpServer.route("/main/:type/:offset/:num")
  @HttpServer.mako_view(HttpTemplate.load('main'))
  def handle_main(self, type=None, offset=None, num=None):
    if type not in (None, 'all', 'finished', 'active'):
      HttpServer.abort(404, 'Invalid task type: %s' % type)
    if offset is not None:
      try:
        offset = int(offset)
      except ValueError:
        HttpServer.abort(404, 'Invalid offset: %s' % offset)
    if num is not None:
      try:
        num = int(num)
      except ValueError:
        HttpServer.abort(404, 'Invalid count: %s' % num)
    return self._observer.main(type, offset, num)

  @HttpServer.route("/task/:task_id")
  @HttpServer.mako_view(HttpTemplate.load('task'))
  def handle_task(self, task_id):
    task = self.get_task(task_id)
    processes = self._observer.processes([task_id])
    if not processes.get(task_id, None):
      HttpServer.abort(404, 'Unknown task_id: %s' % task_id)
    processes = processes[task_id]
    state = self._observer.state(task_id)

    return dict(
      task_id = task_id,
      task = task,
      statuses = self._observer.task_statuses(task_id),
      user = task['user'],
      ports = task['ports'],
      processes = processes,
      chroot = state.get('sandbox', ''),
      launch_time = state.get('launch_time', 0),
      hostname = state.get('hostname', 'localhost'),
    )

  def get_task(self, task_id):
    task = self._observer._task(task_id)
    if not task:
      HttpServer.abort(404, "Failed to find task %s.  Try again shortly." % task_id)
    return task

  @HttpServer.route("/rawtask/:task_id")
  @HttpServer.mako_view(HttpTemplate.load('rawtask'))
  def handle_rawtask(self, task_id):
    task = self.get_task(task_id)
    state = self._observer.state(task_id)
    return dict(
      hostname = state.get('hostname', 'localhost'),
      task_id = task_id,
      task_struct = task['task_struct']
    )

  @HttpServer.route("/process/:task_id/:process_id")
  @HttpServer.mako_view(HttpTemplate.load('process'))
  def handle_process(self, task_id, process_id):
    all_processes = {}
    current_run = self._observer.process(task_id, process_id)
    if not current_run:
      HttpServer.abort(404, 'Invalid task/process combination: %s/%s' % (task_id, process_id))
    process = self._observer.process_from_name(task_id, process_id)
    if process is None:
      msg = 'Could not recover process: %s/%s' % (task_id, process_id)
      log.error(msg)
      HttpServer.abort(404, msg)

    current_run_number = current_run['process_run']
    all_processes[current_run_number] = current_run
    for run in range(current_run_number):
      all_processes[run] = self._observer.process(task_id, process_id, run)
    def convert_process_tuple(run_tuple):
      process_tuple = dict(state = run_tuple['state'])
      if 'start_time' in run_tuple:
        process_tuple.update(start_time = run_tuple['start_time'])
      if 'stop_time' in run_tuple:
        process_tuple.update(stop_time = run_tuple['stop_time'])
      return process_tuple

    template = {
      'task_id': task_id,
      'process': {
         'name': process_id,
         'status': all_processes[current_run_number]["state"],
         'cmdline': process.cmdline().get()
      },
    }
    template['process'].update(**all_processes[current_run_number].get('used', {}))
    template['runs'] = dict((run, convert_process_tuple(run_tuple))
        for run, run_tuple in all_processes.items())
    log.info('Rendering template is: %s' % template)
    return template

