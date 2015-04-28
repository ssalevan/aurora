# Overridable variables;
%if %{?!AURORA_VERSION:1}0
%global AURORA_VERSION 0.8.0
%endif
%if %{?!GRADLE_BASEURL:1}0
%global GRADLE_BASEURL https://services.gradle.org/distributions
%endif
%if %{?!GRADLE_VERSION:1}0
%global GRADLE_VERSION 2.3
%endif
%if %{?!JAVA_VERSION:!}0
%global JAVA_VERSION 1.7.0
%endif
%if %{?!MESOS_BASEURL:1}0
%global MESOS_BASEURL https://archive.apache.org/dist/mesos
%endif
%if %{?!MESOS_VERSION:1}0
%global MESOS_VERSION 0.21.1
%endif
%if %{?!PEX_BINARIES:1}0
%global PEX_BINARIES aurora aurora_admin gc_executor thermos_executor thermos_runner thermos_observer
%endif
%if %{?!PYTHON_VERSION:1}0
%global PYTHON_VERSION 1.7
%endif


Name:          aurora
Version:       %{AURORA_VERSION}
Release:       1%{?dist}
Summary:       A Mesos framework for scheduling and executing long-running services and cron jobs.
Group:         Applications/System
License:       ASL 2.0
URL:           https://%{name}.apache.org/

Source0:       https://github.com/apache/%{name}/archive/%{version}/%{name}-%{version}.tar.gz

BuildRequires: apr-devel
BuildRequires: cmake
BuildRequires: cyrus-sasl-devel
BuildRequires: gcc
BuildRequires: gcc-c++
BuildRequires: glibc-static
BuildRequires: java-devel = %{JAVA_VERSION}
BuildRequires: libcurl-devel
BuildRequires: patch
BuildRequires: python-devel = %{PYTHON_VERSION}
BuildRequires: subversion-devel
BuildRequires: tar
BuildRequires: unzip
BuildRequires: wget
BuildRequires: zlib-devel

Requires:      daemonize
Requires:      java = %{JAVA_VERSION}


%description
Apache Aurora is a service scheduler that runs on top of Mesos, enabling you to schedule
long-running services that take advantage of Mesos' scalability, fault-tolerance, and
resource isolation.


%package client
Summary: A client for scheduling services against the Aurora scheduler
Group: Development/Tools

Requires: python = %{PYTHON_VERSION}

%description client
A set of command-line applications used for interacting with and administering Aurora
schedulers.


%package thermos
Summary: Mesos executor that executes tasks scheduled by the Aurora scheduler
Group: Applications/System

Requires: cyrus-sasl-libs
Requires: daemonize
%ifarch x86_64
%if 0%{?fedora} >= 20
Requires: docker-io
%else
Requires: docker
%endif
%endif
Requires: mesos
Requires: python = %{PYTHON_VERSION}
%if 0%{?fedora} >= 20
Requires: mesos-python
%endif

%description thermos
Thermos a simple process management framework used for orchestrating dependent processes
within a single Mesos chroot.  It works in tandem with Aurora to ensure that tasks
scheduled by it are properly executed on Mesos slaves and provides a Web UI to monitor the
state of all running tasks.


%prep
%setup -q -n %{name}-%{version}


%build
# Downloads Gradle executable.
wget %{GRADLE_BASEURL}/gradle-%{GRADLE_VERSION}-bin.zip
unzip gradle-%{GRADLE_VERSION}-bin.zip

# Creates Pants directory where we'll store our native Mesos Python eggs.
mkdir -p .pants.d/python/eggs/

# Builds mesos-native and mesos-interface eggs if not currently packaged.
%if 0%{?fedora} < 20
wget "%{MESOS_BASEURL}/%{MESOS_VERSION}/mesos-%{MESOS_VERSION}.tar.gz"
tar xvzf mesos-%{MESOS_VERSION}.tar.gz
pushd mesos-%{MESOS_VERSION}
./configure --disable-java
make
find . -name '*.egg' -exec cp -v {} ../.pants.d/python/eggs/ \;
popd
%endif

# Builds the Aurora scheduler.
./gradle-%{GRADLE_VERSION}/bin/gradle distZip

# Builds Aurora client PEX binaries.
./pants binary src/main/python/apache/aurora/admin:aurora_admin
./pants binary src/main/python/apache/aurora/client/cli:aurora

# Builds Aurora Thermos and GC executor PEX binaries.
./pants binary src/main/python/apache/aurora/executor/bin:gc_executor
./pants binary src/main/python/apache/aurora/executor/bin:thermos_executor
./pants binary src/main/python/apache/aurora/executor/bin:thermos_runner
./pants binary src/main/python/apache/thermos/observer/bin:thermos_observer

# Packages the Thermos runner within the Thermos executor.
python <<EOF
import contextlib
import zipfile
with contextlib.closing(zipfile.ZipFile('dist/thermos_executor.pex', 'a')) as zf:
  zf.writestr('apache/aurora/executor/resources/__init__.py', '')
  zf.write('dist/thermos_runner.pex', 'apache/aurora/executor/resources/thermos_runner.pex')
EOF

chmod +x ./dist/*.pex


%install
rm -rf $RPM_BUILD_ROOT

# Builds installation directory structure.
mkdir -p %{buildroot}%{_bindir}
mkdir -p %{buildroot}%{_docdir}/%{name}-%{version}
mkdir -p %{buildroot}%{_prefix}/lib/%{name}
mkdir -p %{buildroot}%{_sharedstatedir}
mkdir -p %{buildroot}%{_localstatedir}/lib/%{name}
mkdir -p %{buildroot}%{_localstatedir}/log/%{name}
mkdir -p %{buildroot}%{_localstatedir}/log/thermos-observer
mkdir -p %{buildroot}%{_sysconfdir}/%{name}
mkdir -p %{buildroot}%{_sysconfdir}/init.d
mkdir -p %{buildroot}%{_sysconfdir}/systemd/system
mkdir -p %{buildroot}%{_sysconfdir}/logrotate.d
mkdir -p %{buildroot}%{_sysconfdir}/sysconfig

# Installs the Aurora scheduler that has been built into /usr.
unzip dist/distributions/aurora-scheduler-*.zip -d %{_prefix}/lib/%{name}

# Removes unnecessary BAT file.
rm -f %{buildroot}%{_bindir}/aurora-scheduler.bat

# Installs all PEX binaries.
for pex_binary in %{PEX_BINARIES}; do
  install -m 755 dist/${pex_binary}.pex %{buildroot}%{_bindir}/${pex_binary}
done

# Installs all support scripting.
%if 0%{?fedora}
install -m 644 contrib/packaging/rpm/%{name}.service %{buildroot}%{_sysconfdir}/systemd/system/%{name}.service
install -m 644 contrib/packaging/rpm/thermos-observer.service %{buildroot}%{_sysconfdir}/systemd/system/thermos-observer.service
%else
install -m 755 contrib/packaging/rpm/%{name}.init.sh %{buildroot}%{_sysconfdir}/init.d/%{name}
install -m 755 contrib/packaging/rpm/thermos-observer.init.sh %{buildroot}%{_sysconfdir}/init.d/thermos-observer
%endif

install -m 755 contrib/packaging/rpm/%{name}.startup.sh %{buildroot}%{_bindir}/%{name}-scheduler-startup
install -m 755 contrib/packaging/rpm/thermos-observer.startup.sh %{buildroot}%{_bindir}/thermos-observer-startup

install -m 644 contrib/packaging/rpm/%{name}.sysconfig %{buildroot}%{_sysconfdir}/sysconfig/%{name}
install -m 644 contrib/packaging/rpm/thermos-observer.sysconfig %{buildroot}%{_sysconfdir}/sysconfig/thermos-observer

install -m 644 contrib/packaging/rpm/%{name}.logrotate %{buildroot}%{_sysconfdir}/logrotate.d/%{name}
install -m 644 contrib/packaging/rpm/thermos-observer.logrotate %{buildroot}%{_sysconfdir}/logrotate.d/thermos-observer

install -m 644 contrib/packaging/rpm/clusters.json %{buildroot}%{_sysconfdir}/%{name}/clusters.json


# Pre/post installation scripts:
%post
%systemd_post aurora.service

%preun
%systemd_preun aurora.service

%postun
%systemd_postun_with_restart aurora.service


%post thermos
%systemd_post thermos-observer.service

%preun thermos
%systemd_preun thermos-observer.service

%postun thermos
%systemd_postun_with_restart thermos-observer.service


%files
%defattr(-,root,root,-)
%doc docs/*.md
%{_bindir}/aurora-scheduler-startup
%{_localstatedir}/lib/%{name}
%{_localstatedir}/log/%{name}
%{_prefix}/lib/%{name}/bin/*
%{_prefix}/lib/%{name}/lib/*
%if 0%{?fedora}
%{_sysconfdir}/systemd/system/%{name}.service
%else
%{_sysconfdir}/init.d/%{name}
%endif
%config(noreplace) %{_sysconfdir}/logrotate.d/%{name}
%config(noreplace) %{_sysconfdir}/sysconfig/%{name}


%files client
%defattr(-,root,root,-)
%{_bindir}/%{name}
%{_bindir}/%{name}_admin
%config(noreplace) %{_sysconfdir}/%{name}/clusters.json


%files thermos
%defattr(-,root,root,-)
%{_bindir}/gc_executor
%{_bindir}/thermos_executor
%{_bindir}/thermos_observer
%{_bindir}/thermos_runner
%{_bindir}/thermos-observer-startup
%{_localstatedir}/log/thermos
%{_localstatedir}/run/thermos
%if 0%{?fedora}
%{_sysconfdir}/systemd/system/thermos-observer.service
%else
%{_sysconfdir}/init.d/thermos-observer
%endif
%config(noreplace) %{_sysconfdir}/logrotate.d/thermos-observer
%config(noreplace) %{_sysconfdir}/sysconfig/thermos-observer


%changelog
* Tue Apr 14 2014 Steve Salevan <steve.salevan@gmail.com>
- Initial specfile writeup.
