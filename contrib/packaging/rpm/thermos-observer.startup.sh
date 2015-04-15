#!/bin/bash
# Starts up a Thermos observer process.

source /etc/sysconfig/thermos-observer

exec /usr/bin/thermos_observer "${OBSERVER_ARGS[@]}"
