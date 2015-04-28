GIT_COMMIT ?= HEAD
DATETIME := $(shell date +%Y%m%d%H%M)
GIT_SHA := $(shell git rev-parse --short $(GIT_COMMIT))

GIT_VERSION := $(shell git describe $(GIT_COMMIT) 2>/dev/null)
GIT_VERSION := $(subst -, ,$(GIT_VERSION))

VERSION ?= $(word 1,$(GIT_VERSION))
ifeq (,$(VERSION))
VERSION := 0.8.0
endif
RELEASE ?= $(word 2,$(GIT_VERSION)).$(word 3,$(GIT_VERSION))
ifeq (.,$(RELEASE))
RELEASE := 0
endif

SOURCE_TARGET := rpmbuild/SOURCES/aurora-$(VERSION).tar.gz
SOURCE ?= $(SOURCE_TARGET)

.PHONY: all nightly_version release_version clean mkdir srpm nightly_srpm release_srpm

all: nightly

nightly_version:
  $(eval VERSION := $(DATETIME).$(GIT_SHA))

release_version:
  $(eval VERSION := $(VERSION)-$(RELEASE))

clean:
  rm -rf rpmbuild

mkdir: clean
  mkdir -p rpmbuild
  mkdir -p rpmbuild/BUILD
  mkdir -p rpmbuild/BUILDROOT
  mkdir -p rpmbuild/RPMS
  mkdir -p rpmbuild/SOURCES
  mkdir -p rpmbuild/SRPMS

$(SOURCE_TARGET): mkdir
  if [ -f "$(SOURCE)" ]; then \
    cp $(SOURCE) $(SOURCE_TARGET); \
  else \
    (cd `git rev-parse --show-toplevel` && git archive --format=tar \
      --prefix=aurora-$(VERSION)/ $(GIT_COMMIT)) | gzip > $@; \
  fi

srpm: $(SOURCE_TARGET)
  rpmbuild $(RPM_OPTS) \
    --define "_topdir %(pwd)" \
    --define "_builddir %{_topdir}/rpmbuild/BUILD" \
    --define "_buildrootdir %{_topdir}/rpmbuild/BUILDROOT" \
    --define "_rpmdir %{_topdir}/rpmbuild/RPMS" \
    --define "_srcrpmdir %{_topdir}/rpmbuild/SRPMS" \
    --define "_specdir %{_topdir}" \
    --define "_sourcedir  %{_topdir}/rpmbuild/SOURCES" \
    --define "AURORA_VERSION $(VERSION)" \
    --nodeps -bs aurora.spec

nightly_srpm: nightly_version srpm

release_srpm: release_version srpm
