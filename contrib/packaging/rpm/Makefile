GIT_COMMIT ?= HEAD
GIT_SHA := $(shell git rev-parse --short $(GIT_COMMIT))
GIT_TOPDIR := $(shell git rev-parse --show-toplevel)

DIST_DIR := $(GIT_TOPDIR)/dist

DATETIME := $(shell date +%Y%m%d%H%M)

VERSION := $(shell cat $(GIT_TOPDIR)/.auroraversion)

RPM_TOPDIR ?= $(DIST_DIR)

SOURCE_TARGET := $(RPM_TOPDIR)/SOURCES/aurora-$(VERSION).tar.gz
SOURCE ?= $(SOURCE_TARGET)

.PHONY: all nightly_version release_version clean mkdir srpm nightly_srpm release_srpm

all: nightly_srpm

nightly_version:
	$(eval VERSION := $(VERSION)-$(DATETIME).$(GIT_SHA))

release_version:
	$(eval VERSION := $(VERSION))

clean:
	rm -rf $(RPM_TOPDIR)

mkdir: clean
	mkdir -p $(RPM_TOPDIR)/rpmbuild/BUILD
	mkdir -p $(RPM_TOPDIR)/rpmbuild/BUILDROOT
	mkdir -p $(RPM_TOPDIR)/rpmbuild/RPMS
	mkdir -p $(RPM_TOPDIR)/rpmbuild/SOURCES
	mkdir -p $(RPM_TOPDIR)/rpmbuild/SRPMS

$(SOURCE_TARGET): mkdir
	if [ -f "$(SOURCE)" ]; then \
		cp $(SOURCE) $(SOURCE_TARGET); \
	else \
	(cd `git rev-parse --show-toplevel` && git archive --format=tar \
		--prefix=aurora-$(VERSION)/ $(GIT_COMMIT)) | gzip > $@; \
	fi

srpm: $(SOURCE_TARGET)
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

nightly_srpm: nightly_version srpm

release_srpm: release_version srpm
