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


GIT_COMMIT ?= HEAD
GIT_SHA := $(shell git rev-parse --short $(GIT_COMMIT))
GIT_TOPDIR := $(shell git rev-parse --show-toplevel)

DIST_DIR := $(GIT_TOPDIR)/dist

DATETIME := $(shell date +%Y%m%d%H%M)

VERSION := $(shell cat $(GIT_TOPDIR)/.auroraversion | tr '-' '.')

RPM_TOPDIR ?= $(DIST_DIR)

SOURCE ?= $(SOURCE_TARGET)

.PHONY: all nightly_version release_version get_source_target clean mkdir srpm nightly_srpm nightly_rpm release_srpm release_rpm tar_source

all: nightly_srpm

nightly_version:
	$(eval VERSION := $(VERSION).$(DATETIME).$(GIT_SHA))

release_version:
	$(eval VERSION := $(VERSION))

get_source_target:
	$(eval SOURCE_TARGET := $(RPM_TOPDIR)/rpmbuild/SOURCES/aurora.tar.gz)

clean:
	rm -rf $(RPM_TOPDIR)/rpmbuild

mkdir: clean
	mkdir -p $(RPM_TOPDIR)/rpmbuild/BUILD
	mkdir -p $(RPM_TOPDIR)/rpmbuild/BUILDROOT
	mkdir -p $(RPM_TOPDIR)/rpmbuild/RPMS
	mkdir -p $(RPM_TOPDIR)/rpmbuild/SOURCES
	mkdir -p $(RPM_TOPDIR)/rpmbuild/SRPMS

tar_source: mkdir
	cd `git rev-parse --show-toplevel` && git archive --format=tar \
		--prefix=aurora/ $(GIT_COMMIT) | gzip > $(SOURCE_TARGET)

srpm: get_source_target tar_source
	rpmbuild $(RPM_OPTS) \
    --define "_topdir $(RPM_TOPDIR)" \
 	  --define "_builddir %{_topdir}/rpmbuild/BUILD" \
    --define "_buildrootdir %{_topdir}/rpmbuild/BUILDROOT" \
    --define "_rpmdir %{_topdir}/rpmbuild/RPMS" \
    --define "_srcrpmdir %{_topdir}/rpmbuild/SRPMS" \
    --define "_specdir %(pwd)" \
    --define "_sourcedir  %{_topdir}/rpmbuild/SOURCES" \
    --define "AURORA_VERSION $(VERSION)" \
    --nodeps -bs aurora.spec

rpm: get_source_target tar_source
	rpmbuild $(RPM_OPTS) \
    --define "_topdir $(RPM_TOPDIR)" \
 	  --define "_builddir %{_topdir}/rpmbuild/BUILD" \
    --define "_buildrootdir %{_topdir}/rpmbuild/BUILDROOT" \
    --define "_rpmdir %{_topdir}/rpmbuild/RPMS" \
    --define "_srcrpmdir %{_topdir}/rpmbuild/SRPMS" \
    --define "_specdir %(pwd)" \
    --define "_sourcedir  %{_topdir}/rpmbuild/SOURCES" \
    --define "AURORA_VERSION $(VERSION)" \
    --nodeps -ba aurora.spec

nightly_srpm: nightly_version srpm

nightly_rpm: nightly_version rpm

release_srpm: release_version srpm

release_rpm: release_version rpm
