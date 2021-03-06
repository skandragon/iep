/*
 * Copyright 2014-2017 Netflix, Inc.
 *
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
package com.netflix.iep.platformservice;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.netflix.archaius.api.config.PollingStrategy;
import com.netflix.archaius.api.inject.RemoteLayer;
import com.netflix.archaius.config.EmptyConfig;
import com.netflix.archaius.config.polling.PollingResponse;
import com.netflix.archaius.guice.ArchaiusModule;
import com.netflix.archaius.typesafe.TypesafeConfig;
import com.netflix.iep.admin.AdminConfig;
import com.netflix.iep.admin.AdminModule;
import com.netflix.spectator.api.DefaultRegistry;
import com.netflix.spectator.api.Registry;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Helper for configuring archaius with the Netflix dynamic property source.
 */
public final class PlatformServiceModule extends ArchaiusModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlatformServiceModule.class);

  @Override protected void configureArchaius() {
    bindApplicationConfigurationOverride()
      .to(Key.get(com.netflix.archaius.api.Config.class, ApplicationLayer.class));
    AdminModule.endpointsBinder(binder()).addBinding("/props").to(PropsEndpoint.class);
  }

  private Config loadConfigByName(String name) {
    LOGGER.debug("loading config {}", name);
    if (name.startsWith("file:")) {
      File f = new File(name.substring("file:".length()));
      return ConfigFactory.parseFile(f);
    } else {
      return ConfigFactory.parseResources(name);
    }
  }

  private Config loadIncludes(Config baseConfig) {
    final String prop = "netflix.iep.include";
    Config acc = baseConfig;
    for (String name : baseConfig.getStringList(prop)) {
      Config cfg = loadConfigByName(name);
      acc = cfg.withFallback(acc);
    }
    return acc.resolve();
  }

  @Provides
  @Singleton
  Config providesTypesafeConfig() {
    final String prop = "netflix.iep.env.account-type";
    final Config baseConfig = ConfigFactory.load();
    final String envConfigName = "iep-" + baseConfig.getString(prop) + ".conf";
    final Config envConfig = loadConfigByName(envConfigName);
    return loadIncludes(envConfig.withFallback(baseConfig).resolve());
  }

  @Provides
  @Singleton
  @RemoteLayer
  private com.netflix.archaius.api.Config providesOverrideConfig(OptionalInjections opts, Config cfg)
      throws Exception {
    return getDynamicConfig(opts.getRegistry(), cfg);
  }

  @Provides
  @Singleton
  @ApplicationLayer
  protected com.netflix.archaius.api.Config providesAppConfig(final Config application) {
    return new TypesafeConfig(application);
  }

  @Provides
  @Singleton
  private AdminConfig providesAdminConfig(Config cfg) {
    return new AdminConfig() {
      @Override public int port() {
        return cfg.getInt("netflix.iep.admin.port");
      }

      @Override public int backlog() {
        return cfg.getInt("netflix.iep.admin.backlog");
      }

      @Override public Duration shutdownDelay() {
        long nanos = cfg.getDuration("netflix.iep.admin.shutdown-delay", TimeUnit.NANOSECONDS);
        return Duration.ofNanos(nanos);
      }

      @Override public String uiLocation() {
        final String k = "netflix.iep.admin.ui-location";
        return cfg.hasPath(k) ? cfg.getString(k) : "/ui";
      }
    };
  }

  @Override public boolean equals(Object obj) {
    return obj != null && getClass().equals(obj.getClass());
  }

  @Override public int hashCode() {
    return getClass().hashCode();
  }

  private static Callable<PollingResponse> getCallback(Registry registry, Config cfg)
      throws Exception {
    final String prop = "netflix.iep.archaius.url";
    final URL url = URI.create(cfg.getString(prop)).toURL();
    return new PropertiesReader(registry, url);
  }

  private static PollingStrategy getPollingStrategy(Config cfg) {
    final String propPollingInterval = "netflix.iep.archaius.polling-interval";
    final String propSyncInit = "netflix.iep.archaius.sync-init";
    final long interval = cfg.getDuration(propPollingInterval, TimeUnit.MILLISECONDS);
    return new FixedPollingStrategy(interval, TimeUnit.MILLISECONDS, cfg.getBoolean(propSyncInit));
  }

  public static com.netflix.archaius.api.Config getDynamicConfig(Registry registry, Config cfg)
      throws Exception {
    final String propUseDynamic = "netflix.iep.archaius.use-dynamic";
    return (cfg.getBoolean(propUseDynamic))
      ? new PollingDynamicConfig(getCallback(registry, cfg), getPollingStrategy(cfg))
      : EmptyConfig.INSTANCE;
  }

  @Singleton
  private static class OptionalInjections {
    @Inject(optional = true)
    Registry registry;

    Registry getRegistry() {
      if (registry == null) {
        registry = new DefaultRegistry();
      }
      return registry;
    }
  }
}
