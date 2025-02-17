package io.quarkus.reactive.mssql.client.runtime;

import static io.quarkus.credentials.CredentialsProvider.PASSWORD_PROPERTY_NAME;
import static io.quarkus.credentials.CredentialsProvider.USER_PROPERTY_NAME;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configureJksKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configureJksTrustOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePemKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePemTrustOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePfxKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePfxTrustOptions;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.datasource.runtime.DataSourceRuntimeConfig;
import io.quarkus.datasource.runtime.DataSourcesRuntimeConfig;
import io.quarkus.reactive.datasource.runtime.DataSourceReactiveRuntimeConfig;
import io.quarkus.reactive.datasource.runtime.DataSourcesReactiveRuntimeConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Vertx;
import io.vertx.mssqlclient.MSSQLConnectOptions;
import io.vertx.mssqlclient.MSSQLPool;
import io.vertx.sqlclient.PoolOptions;

@SuppressWarnings("deprecation")
@Recorder
public class MSSQLPoolRecorder {

    private static final Logger log = Logger.getLogger(MSSQLPoolRecorder.class);

    public RuntimeValue<MSSQLPool> configureMSSQLPool(RuntimeValue<Vertx> vertx,
            Supplier<Integer> eventLoopCount,
            String dataSourceName,
            DataSourcesRuntimeConfig dataSourcesRuntimeConfig,
            DataSourcesReactiveRuntimeConfig dataSourcesReactiveRuntimeConfig,
            DataSourcesReactiveMSSQLConfig dataSourcesReactiveMSSQLConfig,
            ShutdownContext shutdown) {

        MSSQLPool mssqlPool = initialize(vertx.getValue(),
                eventLoopCount.get(),
                dataSourcesRuntimeConfig.getDataSourceRuntimeConfig(dataSourceName),
                dataSourcesReactiveRuntimeConfig.getDataSourceReactiveRuntimeConfig(dataSourceName),
                dataSourcesReactiveMSSQLConfig.getDataSourceReactiveRuntimeConfig(dataSourceName));

        shutdown.addShutdownTask(mssqlPool::close);
        return new RuntimeValue<>(mssqlPool);
    }

    public RuntimeValue<io.vertx.mutiny.mssqlclient.MSSQLPool> mutinyMSSQLPool(RuntimeValue<MSSQLPool> mssqlPool) {
        return new RuntimeValue<>(io.vertx.mutiny.mssqlclient.MSSQLPool.newInstance(mssqlPool.getValue()));
    }

    private MSSQLPool initialize(Vertx vertx,
            Integer eventLoopCount,
            DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMSSQLConfig dataSourceReactiveMSSQLConfig) {
        PoolOptions poolOptions = toPoolOptions(eventLoopCount, dataSourceRuntimeConfig, dataSourceReactiveRuntimeConfig,
                dataSourceReactiveMSSQLConfig);
        MSSQLConnectOptions mssqlConnectOptions = toMSSQLConnectOptions(dataSourceRuntimeConfig,
                dataSourceReactiveRuntimeConfig, dataSourceReactiveMSSQLConfig);
        if (dataSourceReactiveRuntimeConfig.threadLocal.isPresent()) {
            log.warn(
                    "Configuration element 'thread-local' on Reactive datasource connections is deprecated and will be ignored. The started pool will always be based on a per-thread separate pool now.");
        }
        return MSSQLPool.pool(vertx, mssqlConnectOptions, poolOptions);
    }

    private PoolOptions toPoolOptions(Integer eventLoopCount,
            DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMSSQLConfig dataSourceReactiveMSSQLConfig) {
        PoolOptions poolOptions;
        poolOptions = new PoolOptions();

        if (dataSourceReactiveRuntimeConfig.maxSize.isPresent()) {
            poolOptions.setMaxSize(dataSourceReactiveRuntimeConfig.maxSize.getAsInt());
        }

        if (dataSourceReactiveRuntimeConfig.idleTimeout.isPresent()) {
            int idleTimeout = Math.toIntExact(dataSourceReactiveRuntimeConfig.idleTimeout.get().toMillis());
            poolOptions.setIdleTimeout(idleTimeout).setIdleTimeoutUnit(TimeUnit.MILLISECONDS);
        }

        if (dataSourceReactiveRuntimeConfig.shared) {
            poolOptions.setShared(true);
            if (dataSourceReactiveRuntimeConfig.name.isPresent()) {
                poolOptions.setName(dataSourceReactiveRuntimeConfig.name.get());
            }
        }

        if (dataSourceReactiveRuntimeConfig.eventLoopSize.isPresent()) {
            poolOptions.setEventLoopSize(Math.max(0, dataSourceReactiveRuntimeConfig.eventLoopSize.getAsInt()));
        } else if (eventLoopCount != null) {
            poolOptions.setEventLoopSize(Math.max(0, eventLoopCount));
        }

        return poolOptions;
    }

    private MSSQLConnectOptions toMSSQLConnectOptions(DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMSSQLConfig dataSourceReactiveMSSQLConfig) {
        MSSQLConnectOptions mssqlConnectOptions;
        if (dataSourceReactiveRuntimeConfig.url.isPresent()) {
            String url = dataSourceReactiveRuntimeConfig.url.get();
            // clean up the URL to make migrations easier
            if (url.startsWith("vertx-reactive:sqlserver://")) {
                url = url.substring("vertx-reactive:".length());
            }
            mssqlConnectOptions = MSSQLConnectOptions.fromUri(url);
        } else {
            mssqlConnectOptions = new MSSQLConnectOptions();
        }

        if (dataSourceReactiveMSSQLConfig.packetSize.isPresent()) {
            mssqlConnectOptions.setPacketSize(dataSourceReactiveMSSQLConfig.packetSize.getAsInt());
        }

        if (dataSourceRuntimeConfig.username.isPresent()) {
            mssqlConnectOptions.setUser(dataSourceRuntimeConfig.username.get());
        }

        if (dataSourceRuntimeConfig.password.isPresent()) {
            mssqlConnectOptions.setPassword(dataSourceRuntimeConfig.password.get());
        }

        // credentials provider
        if (dataSourceRuntimeConfig.credentialsProvider.isPresent()) {
            String beanName = dataSourceRuntimeConfig.credentialsProviderName.orElse(null);
            CredentialsProvider credentialsProvider = CredentialsProviderFinder.find(beanName);
            String name = dataSourceRuntimeConfig.credentialsProvider.get();
            Map<String, String> credentials = credentialsProvider.getCredentials(name);
            String user = credentials.get(USER_PROPERTY_NAME);
            String password = credentials.get(PASSWORD_PROPERTY_NAME);
            if (user != null) {
                mssqlConnectOptions.setUser(user);
            }
            if (password != null) {
                mssqlConnectOptions.setPassword(password);
            }
        }

        mssqlConnectOptions.setReconnectAttempts(dataSourceReactiveRuntimeConfig.reconnectAttempts);

        mssqlConnectOptions.setReconnectInterval(dataSourceReactiveRuntimeConfig.reconnectInterval.toMillis());

        mssqlConnectOptions.setSsl(dataSourceReactiveMSSQLConfig.ssl);

        mssqlConnectOptions.setTrustAll(dataSourceReactiveRuntimeConfig.trustAll);

        configurePemTrustOptions(mssqlConnectOptions, dataSourceReactiveRuntimeConfig.trustCertificatePem);
        configureJksTrustOptions(mssqlConnectOptions, dataSourceReactiveRuntimeConfig.trustCertificateJks);
        configurePfxTrustOptions(mssqlConnectOptions, dataSourceReactiveRuntimeConfig.trustCertificatePfx);

        configurePemKeyCertOptions(mssqlConnectOptions, dataSourceReactiveRuntimeConfig.keyCertificatePem);
        configureJksKeyCertOptions(mssqlConnectOptions, dataSourceReactiveRuntimeConfig.keyCertificateJks);
        configurePfxKeyCertOptions(mssqlConnectOptions, dataSourceReactiveRuntimeConfig.keyCertificatePfx);

        if (dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm.isPresent()) {
            mssqlConnectOptions.setHostnameVerificationAlgorithm(
                    dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm.get());
        }

        return mssqlConnectOptions;
    }

}
