/*
 * Copyright 2015 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.factory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.ws.rs.ClientErrorException;

import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Uninterruptibles;
import com.palantir.async.initializer.AsyncInitializer;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.cleaner.Cleaner;
import com.palantir.atlasdb.cleaner.CleanupFollower;
import com.palantir.atlasdb.cleaner.DefaultCleanerBuilder;
import com.palantir.atlasdb.config.AtlasDbConfig;
import com.palantir.atlasdb.config.AtlasDbRuntimeConfig;
import com.palantir.atlasdb.config.ImmutableAtlasDbConfig;
import com.palantir.atlasdb.config.ImmutableServerListConfig;
import com.palantir.atlasdb.config.LeaderConfig;
import com.palantir.atlasdb.config.ServerListConfig;
import com.palantir.atlasdb.config.SweepConfig;
import com.palantir.atlasdb.config.TimeLockClientConfig;
import com.palantir.atlasdb.config.TimeLockClientConfigs;
import com.palantir.atlasdb.config.TimestampClientConfig;
import com.palantir.atlasdb.factory.Leaders.LocalPaxosServices;
import com.palantir.atlasdb.factory.startup.TimeLockMigrator;
import com.palantir.atlasdb.factory.timestamp.DecoratedTimelockServices;
import com.palantir.atlasdb.http.AtlasDbFeignTargetFactory;
import com.palantir.atlasdb.http.UserAgents;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.impl.ProfilingKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.SweepStatsKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.TracingKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.ValidatingQueryRewritingKeyValueService;
import com.palantir.atlasdb.logging.KvsProfilingLogger;
import com.palantir.atlasdb.memory.InMemoryAtlasDbConfig;
import com.palantir.atlasdb.persistentlock.CheckAndSetExceptionMapper;
import com.palantir.atlasdb.persistentlock.KvsBackedPersistentLockService;
import com.palantir.atlasdb.persistentlock.NoOpPersistentLockService;
import com.palantir.atlasdb.persistentlock.PersistentLockService;
import com.palantir.atlasdb.schema.generated.SweepTableFactory;
import com.palantir.atlasdb.spi.AtlasDbFactory;
import com.palantir.atlasdb.spi.KeyValueServiceConfig;
import com.palantir.atlasdb.sweep.AdjustableSweepBatchConfigSource;
import com.palantir.atlasdb.sweep.BackgroundSweeperImpl;
import com.palantir.atlasdb.sweep.BackgroundSweeperPerformanceLogger;
import com.palantir.atlasdb.sweep.CellsSweeper;
import com.palantir.atlasdb.sweep.ImmutableSweepBatchConfig;
import com.palantir.atlasdb.sweep.NoOpBackgroundSweeperPerformanceLogger;
import com.palantir.atlasdb.sweep.PersistentLockManager;
import com.palantir.atlasdb.sweep.SpecificTableSweeper;
import com.palantir.atlasdb.sweep.SweepBatchConfig;
import com.palantir.atlasdb.sweep.SweepMetrics;
import com.palantir.atlasdb.sweep.SweepTaskRunner;
import com.palantir.atlasdb.sweep.SweeperServiceImpl;
import com.palantir.atlasdb.table.description.Schema;
import com.palantir.atlasdb.transaction.api.AtlasDbConstraintCheckingMode;
import com.palantir.atlasdb.transaction.impl.ConflictDetectionManager;
import com.palantir.atlasdb.transaction.impl.ConflictDetectionManagers;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManagers;
import com.palantir.atlasdb.transaction.impl.TimelockTimestampServiceAdapter;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.atlasdb.transaction.service.TransactionServices;
import com.palantir.atlasdb.util.AtlasDbMetrics;
import com.palantir.leader.LeaderElectionService;
import com.palantir.leader.PingableLeader;
import com.palantir.leader.proxy.AwaitingLeadershipProxy;
import com.palantir.lock.LockClient;
import com.palantir.lock.LockRequest;
import com.palantir.lock.LockServerOptions;
import com.palantir.lock.LockService;
import com.palantir.lock.SimpleTimeDuration;
import com.palantir.lock.client.LockRefreshingLockService;
import com.palantir.lock.client.LockRefreshingTimelockService;
import com.palantir.lock.impl.LegacyTimelockService;
import com.palantir.lock.impl.LockServiceImpl;
import com.palantir.lock.v2.TimelockService;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.timestamp.TimestampService;
import com.palantir.timestamp.TimestampStoreInvalidator;
import com.palantir.util.OptionalResolver;

@Value.Immutable
public abstract class TransactionManagers {
    private static final int LOGGING_INTERVAL = 60;
    private static final Logger log = LoggerFactory.getLogger(TransactionManagers.class);

    public static final LockClient LOCK_CLIENT = LockClient.of("atlas instance");

    abstract AtlasDbConfig config();

    @Value.Default
    Supplier<Optional<AtlasDbRuntimeConfig>> runtimeConfigSupplier() {
        return Optional::empty;
    }

    abstract Set<Schema> schemas();

    @Value.Default
    Consumer<Object> registrar() {
        return resource -> { };
    }

    @Value.Default
    LockServerOptions lockServerOptions() {
        return LockServerOptions.DEFAULT;
    }

    @Value.Default
    boolean allowHiddenTableAccess() {
        return false;
    }

    abstract String userAgent();

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends ImmutableTransactionManagers.Builder {
        public SerializableTransactionManager buildSerializable() {
            return build().serializable();
        }
    }

    @VisibleForTesting
    static Consumer<Runnable> runAsync = task -> {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    };

    /**
     * Accepts a single {@link Schema}.
     * @see TransactionManagers#createInMemory(Set)
     */
    public static SerializableTransactionManager createInMemory(Schema schema) {
        return createInMemory(ImmutableSet.of(schema));
    }

    /**
     * Create a {@link SerializableTransactionManager} backed by an
     * {@link com.palantir.atlasdb.keyvalue.impl.InMemoryKeyValueService}. This should be used for testing
     * purposes only.
     */
    public static SerializableTransactionManager createInMemory(Set<Schema> schemas) {
        AtlasDbConfig config = ImmutableAtlasDbConfig.builder().keyValueService(new InMemoryAtlasDbConfig()).build();
        return builder().config(config).schemas(schemas).userAgent(UserAgents.DEFAULT_USER_AGENT).buildSerializable();
    }

    // Begin deprecated creation methods

    /**
     * @deprecated Use {@link #builder()} to create a {@link Builder}, and use {@link Builder#buildSerializable()} to
     * generate a {@link SerializableTransactionManager} from it.
     */
    @Deprecated
    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            Supplier<java.util.Optional<AtlasDbRuntimeConfig>> runtimeConfigSupplier,
            Schema schema,
            Environment env,
            boolean allowHiddenTableAccess) {
        return create(config, runtimeConfigSupplier, ImmutableSet.of(schema), env, allowHiddenTableAccess);
    }

    /**
     * @deprecated Use {@link #builder()} to create a {@link Builder}, and use {@link Builder#buildSerializable()} to
     * generate a {@link SerializableTransactionManager} from it.
     */
    @Deprecated
    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            Supplier<java.util.Optional<AtlasDbRuntimeConfig>> runtimeConfigSupplier,
            Set<Schema> schemas,
            Environment env,
            boolean allowHiddenTableAccess) {
        log.info("Called TransactionManagers.create. This should only happen once.",
                UnsafeArg.of("thread name", Thread.currentThread().getName()));
        return create(config, runtimeConfigSupplier, schemas, env, LockServerOptions.DEFAULT, allowHiddenTableAccess);
    }

    /**
     * @deprecated Use {@link #builder()} to create a {@link Builder}, and use {@link Builder#buildSerializable()} to
     * generate a {@link SerializableTransactionManager} from it.
     */
    @Deprecated
    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            Supplier<java.util.Optional<AtlasDbRuntimeConfig>> runtimeConfigSupplier,
            Set<Schema> schemas,
            Environment env,
            LockServerOptions lockServerOptions,
            boolean allowHiddenTableAccess) {
        return builder()
                .config(config)
                .runtimeConfigSupplier(runtimeConfigSupplier)
                .schemas(schemas)
                .registrar(env::register)
                .lockServerOptions(lockServerOptions)
                .allowHiddenTableAccess(allowHiddenTableAccess)
                .userAgent(UserAgents.DEFAULT_USER_AGENT)
                .buildSerializable();
    }

    /**
     * @deprecated Use {@link #builder()} to create a {@link Builder}, and use {@link Builder#buildSerializable()} to
     * generate a {@link SerializableTransactionManager} from it.
     */
    @Deprecated
    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            Supplier<java.util.Optional<AtlasDbRuntimeConfig>> runtimeConfigSupplier,
            Set<Schema> schemas,
            Environment env,
            LockServerOptions lockServerOptions,
            boolean allowHiddenTableAccess,
            Class<?> callingClass) {
        return builder()
                .config(config)
                .runtimeConfigSupplier(runtimeConfigSupplier)
                .schemas(schemas)
                .registrar(env::register)
                .lockServerOptions(lockServerOptions)
                .allowHiddenTableAccess(allowHiddenTableAccess)
                .userAgent(UserAgents.fromClass(callingClass))
                .buildSerializable();
    }

    /**
     * @deprecated Use {@link #builder()} to create a {@link Builder}, and use {@link Builder#buildSerializable()} to
     * generate a {@link SerializableTransactionManager} from it.
     */
    @Deprecated
    public static SerializableTransactionManager create(
            AtlasDbConfig config,
            Supplier<java.util.Optional<AtlasDbRuntimeConfig>> optionalRuntimeConfigSupplier,
            Set<Schema> schemas,
            Environment env,
            LockServerOptions lockServerOptions,
            boolean allowHiddenTableAccess,
            String userAgent) {
        return builder()
                .config(config)
                .runtimeConfigSupplier(optionalRuntimeConfigSupplier)
                .schemas(schemas)
                .registrar(env::register)
                .lockServerOptions(lockServerOptions)
                .allowHiddenTableAccess(allowHiddenTableAccess)
                .userAgent(userAgent)
                .buildSerializable();
    }

    /**
     * @deprecated This interface is deprecated and is not meant for use publicly. When creating a
     * {@link SerializableTransactionManager} via the {@link Builder}, specify a {@link Consumer}.
     */
    @Deprecated
    public interface Environment {
        void register(Object resource);
    }

    // End deprecated creation methods

    @JsonIgnore
    @Value.Derived
    SerializableTransactionManager serializable() {
        final AtlasDbConfig config = config();
        checkInstallConfig(config);

        AtlasDbRuntimeConfig defaultRuntime = AtlasDbRuntimeConfig.defaultRuntimeConfig();
        java.util.function.Supplier<AtlasDbRuntimeConfig> runtimeConfigSupplier =
                () -> runtimeConfigSupplier().get().orElse(defaultRuntime);

        ServiceDiscoveringAtlasSupplier atlasFactory =
                new ServiceDiscoveringAtlasSupplier(config.keyValueService(), config.leader(), config.namespace(),
                        config.initializeAsync());

        KeyValueService rawKvs = atlasFactory.getKeyValueService();
        LockRequest.setDefaultLockTimeout(
                SimpleTimeDuration.of(config.getDefaultLockTimeoutSeconds(), TimeUnit.SECONDS));
        LockAndTimestampServices lockAndTimestampServices = createLockAndTimestampServices(
                config,
                () -> runtimeConfigSupplier.get().timestampClient(),
                registrar(),
                () -> LockServiceImpl.create(lockServerOptions()),
                atlasFactory::getTimestampService,
                atlasFactory.getTimestampStoreInvalidator(),
                userAgent());

        KvsProfilingLogger.setSlowLogThresholdMillis(config.getKvsSlowLogThresholdMillis());
        KeyValueService kvs = ProfilingKeyValueService.create(rawKvs);
        kvs = SweepStatsKeyValueService.create(kvs,
                new TimelockTimestampServiceAdapter(lockAndTimestampServices.timelock()));
        kvs = TracingKeyValueService.create(kvs);
        kvs = AtlasDbMetrics.instrument(KeyValueService.class, kvs, MetricRegistry.name(KeyValueService.class));
        kvs = ValidatingQueryRewritingKeyValueService.create(kvs);

        TransactionManagersInitializer initializer = TransactionManagersInitializer.createInitialTables(
                kvs,
                schemas(),
                config.initializeAsync());
        PersistentLockService persistentLockService = createAndRegisterPersistentLockService(
                kvs,
                registrar(),
                config.initializeAsync());

        TransactionService transactionService = AtlasDbMetrics.instrument(TransactionService.class,
                TransactionServices.createTransactionService(kvs));
        ConflictDetectionManager conflictManager = ConflictDetectionManagers.create(kvs);
        SweepStrategyManager sweepStrategyManager = SweepStrategyManagers.createDefault(kvs);

        CleanupFollower follower = CleanupFollower.create(schemas());

        Cleaner cleaner = new DefaultCleanerBuilder(
                kvs,
                lockAndTimestampServices.timelock(),
                ImmutableList.of(follower),
                transactionService)
                .setBackgroundScrubAggressively(config.backgroundScrubAggressively())
                .setBackgroundScrubBatchSize(config.getBackgroundScrubBatchSize())
                .setBackgroundScrubFrequencyMillis(config.getBackgroundScrubFrequencyMillis())
                .setBackgroundScrubThreads(config.getBackgroundScrubThreads())
                .setPunchIntervalMillis(config.getPunchIntervalMillis())
                .setTransactionReadTimeout(config.getTransactionReadTimeoutMillis())
                .setInitializeAsync(config.initializeAsync())
                .buildCleaner();

        SerializableTransactionManager transactionManager = SerializableTransactionManager.create(
                kvs,
                lockAndTimestampServices.timelock(),
                lockAndTimestampServices.lock(),
                transactionService,
                Suppliers.ofInstance(AtlasDbConstraintCheckingMode.FULL_CONSTRAINT_CHECKING_THROWS_EXCEPTIONS),
                conflictManager,
                sweepStrategyManager,
                cleaner,
                () -> areTransactionManagerInitializationPrerequisitesSatisfied(initializer, lockAndTimestampServices),
                allowHiddenTableAccess(),
                () -> runtimeConfigSupplier.get().transaction().getLockAcquireTimeoutMillis(),
                config.keyValueService().concurrentGetRangesThreadPoolSize(),
                config.keyValueService().defaultGetRangesConcurrency(),
                config.initializeAsync(),
                () -> runtimeConfigSupplier.get().getTimestampCacheSize());

        PersistentLockManager persistentLockManager = new PersistentLockManager(
                persistentLockService,
                config.getSweepPersistentLockWaitMillis());
        initializeSweepEndpointAndBackgroundProcess(
                config,
                runtimeConfigSupplier,
                registrar(),
                kvs,
                transactionService,
                sweepStrategyManager,
                follower,
                transactionManager,
                persistentLockManager);

        return transactionManager;
    }

    private static boolean areTransactionManagerInitializationPrerequisitesSatisfied(
            AsyncInitializer initializer,
            LockAndTimestampServices lockAndTimestampServices) {
        return initializer.isInitialized() && timeLockMigrationCompleteIfNeeded(lockAndTimestampServices);
    }

    @VisibleForTesting
    static boolean timeLockMigrationCompleteIfNeeded(LockAndTimestampServices lockAndTimestampServices) {
        return lockAndTimestampServices.migrator().map(AsyncInitializer::isInitialized).orElse(true);
    }

    private static void checkInstallConfig(AtlasDbConfig config) {
        if (config.getSweepBatchSize() != null
                || config.getSweepCellBatchSize() != null
                || config.getSweepReadLimit() != null
                || config.getSweepCandidateBatchHint() != null
                || config.getSweepDeleteBatchHint() != null) {
            log.error("Your configuration specifies sweep parameters on the install config. They will be ignored."
                    + " Please use the runtime config to specify them.");
        }
    }

    private static void initializeSweepEndpointAndBackgroundProcess(
            AtlasDbConfig config,
            Supplier<AtlasDbRuntimeConfig> runtimeConfigSupplier,
            Consumer<Object> env,
            KeyValueService kvs,
            TransactionService transactionService,
            SweepStrategyManager sweepStrategyManager,
            CleanupFollower follower,
            SerializableTransactionManager transactionManager,
            PersistentLockManager persistentLockManager) {
        CellsSweeper cellsSweeper = new CellsSweeper(
                transactionManager,
                kvs,
                persistentLockManager,
                ImmutableList.of(follower));
        SweepTaskRunner sweepRunner = new SweepTaskRunner(
                kvs,
                transactionManager::getUnreadableTimestamp,
                transactionManager::getImmutableTimestamp,
                transactionService,
                sweepStrategyManager,
                cellsSweeper);
        BackgroundSweeperPerformanceLogger sweepPerfLogger = new NoOpBackgroundSweeperPerformanceLogger();
        AdjustableSweepBatchConfigSource sweepBatchConfigSource = AdjustableSweepBatchConfigSource.create(() ->
                getSweepBatchConfig(runtimeConfigSupplier.get().sweep(), config.keyValueService()));

        SweepMetrics sweepMetrics = new SweepMetrics();

        SpecificTableSweeper specificTableSweeper = initializeSweepEndpoint(
                env,
                kvs,
                transactionManager,
                sweepRunner,
                sweepPerfLogger,
                sweepMetrics,
                sweepBatchConfigSource);

        BackgroundSweeperImpl backgroundSweeper = BackgroundSweeperImpl.create(
                sweepBatchConfigSource,
                () -> runtimeConfigSupplier.get().sweep().enabled(),
                () -> runtimeConfigSupplier.get().sweep().pauseMillis(),
                persistentLockManager,
                specificTableSweeper);

        transactionManager.registerClosingCallback(backgroundSweeper::shutdown);
        backgroundSweeper.runInBackground();
    }

    private static SpecificTableSweeper initializeSweepEndpoint(
            Consumer<Object> env,
            KeyValueService kvs,
            SerializableTransactionManager transactionManager,
            SweepTaskRunner sweepRunner,
            BackgroundSweeperPerformanceLogger sweepPerfLogger,
            SweepMetrics sweepMetrics,
            AdjustableSweepBatchConfigSource sweepBatchConfigSource) {
        SpecificTableSweeper specificTableSweeper = SpecificTableSweeper.create(
                transactionManager,
                kvs,
                sweepRunner,
                SweepTableFactory.of(),
                sweepPerfLogger,
                sweepMetrics);
        env.accept(new SweeperServiceImpl(specificTableSweeper, sweepBatchConfigSource));
        return specificTableSweeper;
    }

    private static SweepBatchConfig getSweepBatchConfig(SweepConfig sweepConfig, KeyValueServiceConfig kvsConfig) {
        return ImmutableSweepBatchConfig.builder()
                .maxCellTsPairsToExamine(sweepConfig.readLimit())
                .candidateBatchSize(sweepConfig.candidateBatchHint()
                        .orElse(getDefaultSweepCandidateBatchHint(kvsConfig)))
                .deleteBatchSize(sweepConfig.deleteBatchHint())
                .build();
    }

    // TODO(nziebart): this is a hack until we fix cassandra's getCandidateCellsForSweep to behave like the other KVSs
    private static int getDefaultSweepCandidateBatchHint(KeyValueServiceConfig kvsConfig) {
        if (kvsConfig.type().equals("cassandra")) {
            return AtlasDbConstants.DEFAULT_SWEEP_CANDIDATE_BATCH_HINT_CASSANDRA;
        }
        return AtlasDbConstants.DEFAULT_SWEEP_CANDIDATE_BATCH_HINT_NON_CASSANDRA;
    }

    private static PersistentLockService createAndRegisterPersistentLockService(
            KeyValueService kvs,
            Consumer<Object> env,
            boolean initializeAsync) {
        if (!kvs.supportsCheckAndSet()) {
            return new NoOpPersistentLockService();
        }

        PersistentLockService pls = KvsBackedPersistentLockService.create(kvs, initializeAsync);
        env.accept(pls);
        env.accept(new CheckAndSetExceptionMapper());
        return pls;
    }

    /**
     * This method should not be used directly. It remains here to support the AtlasDB-Dagger module and the CLIs, but
     * may be removed at some point in the future.
     *
     * @deprecated Not intended for public use outside of the AtlasDB CLIs
     */
    @Deprecated
    public static LockAndTimestampServices createLockAndTimestampServices(
            AtlasDbConfig config,
            Consumer<Object> env,
            com.google.common.base.Supplier<LockService> lock,
            com.google.common.base.Supplier<TimestampService> time) {
        LockAndTimestampServices lockAndTimestampServices =
                createRawInstrumentedServices(config,
                        env,
                        lock,
                        time,
                        () -> {
                            log.warn("Note: Automatic migration isn't performed by the CLI tools.");
                            return AtlasDbFactory.NO_OP_FAST_FORWARD_TIMESTAMP;
                        },
                        UserAgents.DEFAULT_USER_AGENT);
        return withRefreshingLockService(lockAndTimestampServices);
    }

    @VisibleForTesting
    static LockAndTimestampServices createLockAndTimestampServices(
            AtlasDbConfig config,
            java.util.function.Supplier<TimestampClientConfig> runtimeConfigSupplier,
            Consumer<Object> env,
            com.google.common.base.Supplier<LockService> lock,
            com.google.common.base.Supplier<TimestampService> time,
            TimestampStoreInvalidator invalidator,
            String userAgent) {
        LockAndTimestampServices lockAndTimestampServices =
                createRawInstrumentedServices(config, env, lock, time, invalidator, userAgent);
        return withRequestBatchingTimestampService(
                runtimeConfigSupplier,
                withRefreshingLockService(lockAndTimestampServices));
    }

    private static LockAndTimestampServices withRefreshingLockService(
            LockAndTimestampServices lockAndTimestampServices) {
        return ImmutableLockAndTimestampServices.builder()
                .from(lockAndTimestampServices)
                .timelock(LockRefreshingTimelockService.createDefault(lockAndTimestampServices.timelock()))
                .lock(LockRefreshingLockService.create(lockAndTimestampServices.lock()))
                .build();
    }

    private static LockAndTimestampServices withRequestBatchingTimestampService(
            java.util.function.Supplier<TimestampClientConfig> timestampClientConfigSupplier,
            LockAndTimestampServices lockAndTimestampServices) {
        TimelockService timelockServiceWithBatching = DecoratedTimelockServices
                .createTimelockServiceWithTimestampBatching(
                        lockAndTimestampServices.timelock(),
                        timestampClientConfigSupplier);

        return ImmutableLockAndTimestampServices.builder()
                .from(lockAndTimestampServices)
                .timestamp(new TimelockTimestampServiceAdapter(timelockServiceWithBatching))
                .timelock(timelockServiceWithBatching)
                .build();
    }

    @VisibleForTesting
    static LockAndTimestampServices createRawInstrumentedServices(
            AtlasDbConfig config,
            Consumer<Object> env,
            com.google.common.base.Supplier<LockService> lock,
            com.google.common.base.Supplier<TimestampService> time,
            TimestampStoreInvalidator invalidator,
            String userAgent) {
        if (config.leader().isPresent()) {
            return createRawLeaderServices(config.leader().get(), env, lock, time, userAgent);
        } else if (config.timestamp().isPresent() && config.lock().isPresent()) {
            return createRawRemoteServices(config, userAgent);
        } else if (config.timelock().isPresent()) {
            return createRawServicesFromTimeLock(config, invalidator, userAgent);
        } else {
            return createRawEmbeddedServices(env, lock, time);
        }
    }

    private static LockAndTimestampServices createRawServicesFromTimeLock(
            AtlasDbConfig config,
            TimestampStoreInvalidator invalidator,
            String userAgent) {
        Preconditions.checkState(config.timelock().isPresent(),
                "Cannot create raw services from timelock without a timelock block!");
        TimeLockClientConfig clientConfig = config.timelock().get();
        String resolvedClient = OptionalResolver.resolve(clientConfig.client(), config.namespace());
        TimeLockClientConfig timeLockClientConfig =
                TimeLockClientConfigs.copyWithClient(config.timelock().get(), resolvedClient);
        TimeLockMigrator migrator =
                TimeLockMigrator.create(timeLockClientConfig, invalidator, userAgent, config.initializeAsync());
        migrator.migrate(); // This can proceed async if config.initializeAsync() was set
        return ImmutableLockAndTimestampServices.copyOf(
                createNamespacedRawRemoteServices(timeLockClientConfig, userAgent))
                .withMigrator(migrator);
    }

    private static LockAndTimestampServices createNamespacedRawRemoteServices(
            TimeLockClientConfig config,
            String userAgent) {
        ServerListConfig namespacedServerListConfig = config.toNamespacedServerList();
        return getLockAndTimestampServices(namespacedServerListConfig, userAgent);
    }

    private static LockAndTimestampServices getLockAndTimestampServices(
            ServerListConfig timelockServerListConfig,
            String userAgent) {
        LockService lockService = new ServiceCreator<>(LockService.class, userAgent)
                .apply(timelockServerListConfig);
        TimelockService timelockService = new ServiceCreator<>(TimelockService.class, userAgent)
                .apply(timelockServerListConfig);

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .timestamp(new TimelockTimestampServiceAdapter(timelockService))
                .timelock(timelockService)
                .build();
    }

    private static LockAndTimestampServices createRawLeaderServices(
            LeaderConfig leaderConfig,
            Consumer<Object> env,
            com.google.common.base.Supplier<LockService> lock,
            com.google.common.base.Supplier<TimestampService> time,
            String userAgent) {
        // Create local services, that may or may not end up being registered in an Consumer<Object>.
        LocalPaxosServices localPaxosServices = Leaders.createAndRegisterLocalServices(env, leaderConfig, userAgent);
        LeaderElectionService leader = localPaxosServices.leaderElectionService();
        LockService localLock = ServiceCreator.createInstrumentedService(
                AwaitingLeadershipProxy.newProxyInstance(LockService.class, lock, leader),
                LockService.class);
        TimestampService localTime = ServiceCreator.createInstrumentedService(
                AwaitingLeadershipProxy.newProxyInstance(TimestampService.class, time, leader),
                TimestampService.class);
        env.accept(localLock);
        env.accept(localTime);

        // Create remote services, that may end up calling our own local services.
        ImmutableServerListConfig serverListConfig = ImmutableServerListConfig.builder()
                .servers(leaderConfig.leaders())
                .sslConfiguration(leaderConfig.sslConfiguration())
                .build();
        LockService remoteLock = new ServiceCreator<>(LockService.class, userAgent)
                .apply(serverListConfig);
        TimestampService remoteTime = new ServiceCreator<>(TimestampService.class, userAgent)
                .apply(serverListConfig);

        if (leaderConfig.leaders().size() == 1) {
            // Attempting to connect to ourself while processing a request can lead to deadlock if incoming request
            // volume is high, as all Jetty threads end up waiting for the timestamp server, and no threads remain to
            // actually handle the timestamp server requests. If we are the only single leader, we can avoid the
            // deadlock entirely; so use PingableLeader's getUUID() to detect this situation and eliminate the redundant
            // call.

            PingableLeader localPingableLeader = localPaxosServices.pingableLeader();
            String localServerId = localPingableLeader.getUUID();
            PingableLeader remotePingableLeader = AtlasDbFeignTargetFactory.createRsProxy(
                    ServiceCreator.createSslSocketFactory(leaderConfig.sslConfiguration()),
                    Iterables.getOnlyElement(leaderConfig.leaders()),
                    PingableLeader.class,
                    userAgent);

            // Determine asynchronously whether the remote services are talking to our local services.
            CompletableFuture<Boolean> useLocalServicesFuture = new CompletableFuture<>();
            runAsync.accept(() -> {
                int logAfter = LOGGING_INTERVAL;
                while (true) {
                    try {
                        String remoteServerId = remotePingableLeader.getUUID();
                        useLocalServicesFuture.complete(localServerId.equals(remoteServerId));
                        return;
                    } catch (ClientErrorException e) {
                        useLocalServicesFuture.complete(false);
                        return;
                    } catch (Throwable e) {
                        if (--logAfter == 0) {
                            log.warn("Failed to read remote timestamp server ID", e);
                            logAfter = LOGGING_INTERVAL;
                        }
                    }
                    Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
                }
            });

            // Create dynamic service proxies, that switch to talking directly to our local services if it turns out our
            // remote services are pointed at them anyway.
            LockService dynamicLockService = LocalOrRemoteProxy.newProxyInstance(
                    LockService.class, localLock, remoteLock, useLocalServicesFuture);
            TimestampService dynamicTimeService = LocalOrRemoteProxy.newProxyInstance(
                    TimestampService.class, localTime, remoteTime, useLocalServicesFuture);
            return ImmutableLockAndTimestampServices.builder()
                    .lock(dynamicLockService)
                    .timestamp(dynamicTimeService)
                    .timelock(new LegacyTimelockService(dynamicTimeService, dynamicLockService, LOCK_CLIENT))
                    .build();

        } else {
            return ImmutableLockAndTimestampServices.builder()
                    .lock(remoteLock)
                    .timestamp(remoteTime)
                    .timelock(new LegacyTimelockService(remoteTime, remoteLock, LOCK_CLIENT))
                    .build();
        }
    }

    private static LockAndTimestampServices createRawRemoteServices(AtlasDbConfig config, String userAgent) {
        LockService lockService = new ServiceCreator<>(LockService.class, userAgent)
                .apply(config.lock().get());
        TimestampService timeService = new ServiceCreator<>(TimestampService.class, userAgent)
                .apply(config.timestamp().get());

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .timestamp(timeService)
                .timelock(new LegacyTimelockService(timeService, lockService, LOCK_CLIENT))
                .build();
    }

    private static LockAndTimestampServices createRawEmbeddedServices(
            Consumer<Object> env,
            com.google.common.base.Supplier<LockService> lock,
            com.google.common.base.Supplier<TimestampService> time) {
        LockService lockService = ServiceCreator.createInstrumentedService(lock.get(), LockService.class);
        TimestampService timeService = ServiceCreator.createInstrumentedService(time.get(), TimestampService.class);

        env.accept(lockService);
        env.accept(timeService);

        return ImmutableLockAndTimestampServices.builder()
                .lock(lockService)
                .timestamp(timeService)
                .timelock(new LegacyTimelockService(timeService, lockService, LOCK_CLIENT))
                .build();
    }

    @Value.Immutable
    public interface LockAndTimestampServices {
        LockService lock();
        TimestampService timestamp();
        TimelockService timelock();
        Optional<TimeLockMigrator> migrator();
    }
}
