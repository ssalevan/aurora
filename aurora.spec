%if %{?!AURORA_VERSION:1}0
%global AURORA_VERSION 0.8.0
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


Name:          aurora
Version:       %{AURORA_VERSION}
Release:       1%{?dist}
Summary:       A Mesos framework for scheduling and executing long-running services and cron jobs.
Group:         Applications/System

License:       ASL 2.0
URL:           http://%{name}.apache.org/
Source0:       https://github.com/apache/%{name}/archive/%{version}/%{name}-%{version}.tar.gz

BuildRequires: gcc
BuildRequires: gcc-c++
BuildRequires: python-mesos
BuildRequires: unzip
BuildRequires: wget

%if 0%{?fedora} >= 16
BuildRequires: java-devel
BuildRequires: python-devel
%else
BuildRequires: java-1.7.0-openjdk-devel
BuildRequires: python27-devel
%endif

Requires:      java-1.7.0-openjdk


%description
Apache Aurora is a service scheduler that runs on top of Mesos, enabling you to schedule
long-running services that take advantage of Mesos' scalability, fault-tolerance, and
resource isolation.


%package client
Summary:  A client for scheduling services against the Aurora scheduler
Group: Development/Tools
Requires: python27

%description client
A set of command-line applications used for interacting with the Aurora executor for
Mesos tasks.

%package thermos
Summary: Mesos executor that executes tasks scheduled by the Aurora scheduler
Group: Applications/System
Requires: cyrus-sasl-libs
Requires: mesos
Requires: mesos-python
Requires: python27

%description thermos
Thermos a simple process management framework used for orchestrating dependent processes
within a single Mesos chroot.  It works in tandem with Aurora to ensure that tasks
scheduled by Aurora are properly executed on Mesos slaves.


%prep
%setup -q -n %{name}-%{version}


%build
# Preferences Python 2.7 over the system Python.
export PATH=/usr/python2.7/bin:$PATH

# Ensures that Gradle finds the RPM-provided Java.
export JAVA_HOME=/usr

# Downloads Gradle executable.
wget https://services.gradle.org/distributions/gradle-2.3-bin.zip
unzip gradle-2.3-bin.zip

# Builds the Aurora scheduler.
./gradle-2.3/bin/gradle distZip

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
install -m 755 contrib/packaging/rpm/%{name}.init.sh %{buildroot}%{_sysconfdir}/init.d/%{name}
install -m 755 contrib/packaging/rpm/thermos-observer.init.sh %{buildroot}%{_sysconfdir}/init.d/thermos-observer

install -m 755 contrib/packaging/rpm/%{name}.startup.sh %{buildroot}%{_bindir}/%{name}-scheduler-startup
install -m 755 contrib/packaging/rpm/thermos-observer.startup.sh %{buildroot}%{_bindir}/thermos-observer-startup

install -m 644 contrib/packaging/rpm/%{name}.sysconfig %{buildroot}%{_sysconfdir}/sysconfig/%{name}
install -m 644 contrib/packaging/rpm/thermos-observer.sysconfig %{buildroot}%{_sysconfdir}/sysconfig/thermos-observer

install -m 644 contrib/packaging/rpm/%{name}.logrotate %{buildroot}%{_sysconfdir}/logrotate.d/%{name}
install -m 644 contrib/packaging/rpm/thermos-observer.logrotate %{buildroot}%{_sysconfdir}/logrotate.d/thermos-observer

install -m 644 contrib/packaging/rpm/clusters.json %{buildroot}%{_sysconfdir}/%{name}/clusters.json


%files
%defattr(-,root,root,-)
%doc docs/*.md
%{_bindir}/aurora-scheduler-startup
%{_localstatedir}/lib/%{name}
%{_localstatedir}/log/%{name}
%{_prefix}/lib/%{name}/bin/*
%{_prefix}/lib/%{name}/lib/*
%{_sysconfdir}/init.d/%{name}
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
%{_sysconfdir}/init.d/thermos-observer
%config(noreplace) %{_sysconfdir}/logrotate.d/thermos-observer
%config(noreplace) %{_sysconfdir}/sysconfig/%{name}


%changelog
* Tue Apr 14 2014 Steve Salevan <steve.salevan@gmail.com>
- Initial specfile writeup.
