Building Aurora RPMs
====================

Building RPMs for Aurora can be accomplished via calling rpmbuild directly or by using the
provided Mock (https://fedoraproject.org/wiki/Mock) configurations to do so within a
hermetic build environment.

Builds performed against the following distributions have been tested successfully:

*  CentOS 6/7
*  Fedora 19/20

Building With Mock
------------------

Mock allows for ease of compiling across different distributions should you be operating
Mesos within a heterogenous environment of Red Hat-derived operating systems.

To do so:

```bash
# Installs mock.
yum install -y mock

# Builds a nightly SRPM.
make

# Builds RPM hermetically via Mock.
mock -c (el-6-x86_64|fedora-(19|20)-x86_64) rebuild ./dist/rpmbuild/SRPMS/*.src.rpm
```

Building Directly
-----------------

Building directly is an option should you wish to build for the currently running
Red Hat-derived operating system.

To do so:

```bash
# Installs necessary dependencies.
yum install -y apr-devel cyrus-sasl-devel gcc gcc-c++ git java-1.7.0-openjdk-devel \
  libcurl-devel patch python python-devel subversion-devel tar unzip wget zlib-devel

# Builds a nightly RPM.
make nightly_rpm
```
