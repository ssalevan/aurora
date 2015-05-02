/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.http.api.security;

import java.lang.reflect.Method;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import com.google.inject.name.Names;
import com.google.inject.servlet.RequestScoped;
import com.google.inject.servlet.ServletModule;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;

import org.aopalliance.intercept.MethodInterceptor;
import org.apache.aurora.GuiceUtils;
import org.apache.aurora.gen.AuroraAdmin;
import org.apache.aurora.gen.AuroraSchedulerManager;
import org.apache.aurora.scheduler.app.Modules;
import org.apache.aurora.scheduler.http.api.ApiModule;
import org.apache.aurora.scheduler.thrift.aop.AnnotatedAuroraAdmin;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.guice.aop.ShiroAopModule;
import org.apache.shiro.guice.web.ShiroWebModule;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.authc.BasicHttpAuthenticationFilter;

import static java.util.Objects.requireNonNull;

import static org.apache.aurora.scheduler.spi.Permissions.Domain.THRIFT_AURORA_ADMIN;
import static org.apache.aurora.scheduler.spi.Permissions.Domain.THRIFT_AURORA_SCHEDULER_MANAGER;

/**
 * Provides HTTP Basic Authentication for the API using Apache Shiro. When enabled, prevents
 * unauthenticated access to write APIs. Write API access must also be authorized, with permissions
 * configured in a shiro.ini file. For an example of this file, see the test resources included with
 * this package.
 */
public class ApiSecurityModule extends ServletModule {
  public static final String HTTP_REALM_NAME = "Apache Aurora Scheduler";

  @CmdLine(name = "shiro_realm_modules",
      help = "Guice modules for configuring Shiro Realms.")
  private static final Arg<Set<Module>> SHIRO_REALM_MODULE = Arg.<Set<Module>>create(
      ImmutableSet.of(Modules.lazilyInstantiated(IniShiroRealmModule.class)));

  @VisibleForTesting
  static final Matcher<Method> AURORA_SCHEDULER_MANAGER_SERVICE =
      GuiceUtils.interfaceMatcher(AuroraSchedulerManager.Iface.class, true);

  @VisibleForTesting
  static final Matcher<Method> AURORA_ADMIN_SERVICE =
      GuiceUtils.interfaceMatcher(AuroraAdmin.Iface.class, true);

  public enum HttpAuthenticationMechanism {
    /**
     * No security.
     */
    NONE,

    /**
     * HTTP Basic Authentication, produces {@link org.apache.shiro.authc.UsernamePasswordToken}s.
     */
    BASIC,

    /**
     * Use GSS-Negotiate. Only Kerberos and SPNEGO-with-Kerberos GSS mechanisms are supported.
     */
    NEGOTIATE,
  }

  @CmdLine(name = "http_authentication_mechanism", help = "HTTP Authentication mechanism to use.")
  private static final Arg<HttpAuthenticationMechanism> HTTP_AUTHENTICATION_MECHANISM =
      Arg.create(HttpAuthenticationMechanism.NONE);

  private final HttpAuthenticationMechanism mechanism;
  private final Set<Module> shiroConfigurationModules;

  public ApiSecurityModule() {
    this(HTTP_AUTHENTICATION_MECHANISM.get(), SHIRO_REALM_MODULE.get());
  }

  @VisibleForTesting
  ApiSecurityModule(Module shiroConfigurationModule) {
    this(HttpAuthenticationMechanism.BASIC, ImmutableSet.of(shiroConfigurationModule));
  }

  private ApiSecurityModule(
      HttpAuthenticationMechanism mechanism,
      Set<Module> shiroConfigurationModules) {

    this.mechanism = requireNonNull(mechanism);
    this.shiroConfigurationModules = requireNonNull(shiroConfigurationModules);
  }

  @Override
  protected void configureServlets() {
    if (mechanism != HttpAuthenticationMechanism.NONE) {
      doConfigureServlets();
    }
  }

  private void doConfigureServlets() {
    install(ShiroWebModule.guiceFilterModule(ApiModule.API_PATH));
    install(new ShiroWebModule(getServletContext()) {
      @Override
      @SuppressWarnings("unchecked")
      protected void configureShiroWeb() {
        for (Module module : shiroConfigurationModules) {
          // We can't wrap this in a PrivateModule because Guice Multibindings don't work with them
          // and we need a Set<Realm>.
          install(module);
        }

        switch (mechanism) {
          case BASIC:
            addFilterChain("/**",
                ShiroWebModule.NO_SESSION_CREATION,
                config(ShiroWebModule.AUTHC_BASIC, BasicHttpAuthenticationFilter.PERMISSIVE));
            break;

          case NEGOTIATE:
            addFilterChain("/**",
                ShiroWebModule.NO_SESSION_CREATION,
                Key.get(ShiroKerberosAuthenticationFilter.class));
            break;

          default:
            addError("Unrecognized HTTP authentication mechanism: " + mechanism);
            break;
        }
      }
    });

    bindConstant().annotatedWith(Names.named("shiro.applicationName")).to(HTTP_REALM_NAME);

    // TODO(ksweeney): Disable session cookie.
    // TODO(ksweeney): Disable RememberMe cookie.

    install(new ShiroAopModule());

    // It is important that authentication happen before authorization is attempted, otherwise
    // the authorizing interceptor will always fail.
    MethodInterceptor authenticatingInterceptor = new ShiroAuthenticatingThriftInterceptor();
    requestInjection(authenticatingInterceptor);
    bindInterceptor(
        Matchers.subclassesOf(AuroraSchedulerManager.Iface.class),
        AURORA_SCHEDULER_MANAGER_SERVICE.or(AURORA_ADMIN_SERVICE),
        authenticatingInterceptor);

    MethodInterceptor apiInterceptor = new ShiroAuthorizingParamInterceptor(
        THRIFT_AURORA_SCHEDULER_MANAGER);
    requestInjection(apiInterceptor);
    bindInterceptor(
        Matchers.subclassesOf(AuroraSchedulerManager.Iface.class),
        AURORA_SCHEDULER_MANAGER_SERVICE,
        apiInterceptor);

    MethodInterceptor adminInterceptor = new ShiroAuthorizingInterceptor(THRIFT_AURORA_ADMIN);
    requestInjection(adminInterceptor);
    bindInterceptor(
        Matchers.subclassesOf(AnnotatedAuroraAdmin.class),
        AURORA_ADMIN_SERVICE,
        adminInterceptor);
  }

  @Provides
  @RequestScoped
  Subject provideSubject() {
    return SecurityUtils.getSubject();
  }
}
