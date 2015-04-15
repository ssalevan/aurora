%if %{?!MESOS_BASEURL:1}0
%global MESOS_BASEURL https://archive.apache.org/dist/mesos
%endif

%if %{?!MESOS_VERSION:1}0
%global MESOS_VERSION 0.21.1
%endif

Name:           aurora
Version:        0.8.0
Release:        1%{?dist}
Summary:        A Mesos framework for scheduling and executing long-running services and cron jobs.

License:        ASL 2.0
URL:            http://%{name}.apache.org/
Source0:        https://github.com/apache/%{name}/archive/%{version}/%{name}-%{version}.tar.gz

BuildRequires:  java-1.7.0-openjdk-devel
BuildRequires:  jpackage-utils
BuildRequires:  python27-devel
BuildRequires:  python-mesos

Requires:       java-1.7.0-openjdk


%description
Apache Aurora is a service scheduler that runs on top of Mesos, enabling you to schedule
long-running services that take advantage of Mesos' scalability, fault-tolerance, and
resource isolation.


%package client
Summary:  A client for scheduling services against Apache Aurora
Group: Development/Tools
Requires: python27

%description client
A set of command-line applications


%package thermos
Summary: Mesos executor that executes tasks scheduled by the Aurora scheduler
Group: Applications/System
Requires: cyrus-sasl-libs
Requires: mesos
Requires: mesos-python
Requires: python27

%description -n thermos
Thermos a simple process management framework used for orchestrating dependent processes
within a single Mesos chroot.


%prep
%setup -q -n %{name}-%{version}


%build
# Preferences Python 2.7 over the system Python.
export PATH=/usr/python2.7/bin:$PATH

# Ensures that Gradle finds the RPM-provided Java.
export JAVA_HOME=/usr

wget $MESOS_BASEURL/%{MESOS_VERSION}/mesos-%{MESOS_VERSION}.tar.gz
tar zxvf mesos-%{MESOS_VERSION}.tar.gz
cd mesos-%{MESOS_VERSION}
./configure --disable-java
make
find . -name '*.egg' -exec cp -v {} /vagrant \\;

# Builds the Aurora scheduler.
./gradlew distZip

# Builds Aurora client PEX binaries.
./pants build -i 'CPython>=2.7.0' src/main/python/apache/aurora/client/bin:aurora_admin
./pants build -i 'CPython>=2.7.0' src/main/python/apache/aurora/client/bin:aurora_client

# Builds Aurora Thermos and GC executor PEX binaries.
./pants build -i 'CPython>=2.7.0' src/main/python/apache/aurora/executor/bin:gc_executor
./pants build -i 'CPython>=2.7.0' src/main/python/apache/aurora/executor/bin:thermos_executor
./pants build -i 'CPython>=2.7.0' src/main/python/apache/aurora/executor/bin:thermos_runner
./pants build -i 'CPython>=2.7.0' src/main/python/apache/thermos/observer/bin:thermos_observer

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
mkdir -p %{buildroot}%{_sysconfdir}/init.d
mkdir -p %{buildroot}%{_sysconfdir}/logrotate.d


%files
%doc LICENSE README.md


%files -n thermos

%doc docs/*



%changelog
* Tue Apr  14 2014 Steve Salevan <steve.salevan@gmail.com>
- Initial specfile writeup.
