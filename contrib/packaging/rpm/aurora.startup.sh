#!/bin/bash
# Ansible managed: /Users/steve/Workspace/spine/ansible/roles/aurora/templates/aurora-startup.sh.j2 modified on 2015-03-15 16:27:59 by steve on squonk.local
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

source /etc/sysconfig/aurora

# Environment variables control the behavior of the Mesos scheduler driver (libmesos).
export GLOG_v=${GLOG_V}
export LIBPROCESS_PORT="${LIBPROCESS_PORT}"
export LIBPROCESS_IP="${LIBPROCESS_IP}"
export JAVA_OPTS="${JAVA_OPTS[*]}"

exec /usr/lib/aurora/bin/aurora-scheduler "${AURORA_FLAGS[@]}"
