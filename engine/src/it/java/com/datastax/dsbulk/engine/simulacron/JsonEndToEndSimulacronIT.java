/*
 * Copyright DataStax Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.dsbulk.engine.simulacron;

import static com.datastax.dsbulk.commons.tests.logging.StreamType.STDERR;
import static com.datastax.dsbulk.commons.tests.logging.StreamType.STDOUT;
import static com.datastax.dsbulk.commons.tests.utils.FileUtils.deleteDirectory;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.createParameterizedQuery;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.createQueryWithError;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.createQueryWithResultSet;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.createSimpleParametrizedQuery;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.fetchContactPoints;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.setProductionKey;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.validateBadOps;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.validateExceptionsLog;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.validateOutputFiles;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.validatePrepare;
import static com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.validateQueryCount;
import static com.datastax.dsbulk.engine.tests.utils.JsonUtils.INSERT_INTO_IP_BY_COUNTRY;
import static com.datastax.dsbulk.engine.tests.utils.JsonUtils.IP_BY_COUNTRY_MAPPING;
import static com.datastax.dsbulk.engine.tests.utils.JsonUtils.JSON_RECORDS_CRLF;
import static com.datastax.dsbulk.engine.tests.utils.JsonUtils.JSON_RECORDS_ERROR;
import static com.datastax.dsbulk.engine.tests.utils.JsonUtils.JSON_RECORDS_PARTIAL_BAD;
import static com.datastax.dsbulk.engine.tests.utils.JsonUtils.JSON_RECORDS_SKIP;
import static com.datastax.dsbulk.engine.tests.utils.JsonUtils.JSON_RECORDS_UNIQUE;
import static com.datastax.dsbulk.engine.tests.utils.JsonUtils.SELECT_FROM_IP_BY_COUNTRY;
import static com.datastax.oss.simulacron.common.codec.ConsistencyLevel.LOCAL_ONE;
import static com.datastax.oss.simulacron.common.codec.ConsistencyLevel.ONE;
import static java.nio.file.Files.createTempDirectory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.slf4j.event.Level.ERROR;

import ch.qos.logback.core.joran.spi.JoranException;
import com.datastax.dsbulk.commons.config.LoaderConfig;
import com.datastax.dsbulk.commons.internal.config.DefaultLoaderConfig;
import com.datastax.dsbulk.commons.tests.logging.LogCapture;
import com.datastax.dsbulk.commons.tests.logging.LogInterceptingExtension;
import com.datastax.dsbulk.commons.tests.logging.LogInterceptor;
import com.datastax.dsbulk.commons.tests.logging.StreamCapture;
import com.datastax.dsbulk.commons.tests.logging.StreamInterceptingExtension;
import com.datastax.dsbulk.commons.tests.logging.StreamInterceptor;
import com.datastax.dsbulk.commons.tests.simulacron.SimulacronExtension;
import com.datastax.dsbulk.commons.tests.utils.CsvUtils;
import com.datastax.dsbulk.connectors.api.Record;
import com.datastax.dsbulk.connectors.json.JsonConnector;
import com.datastax.dsbulk.engine.Main;
import com.datastax.dsbulk.engine.internal.settings.LogSettings;
import com.datastax.dsbulk.engine.tests.MockConnector;
import com.datastax.oss.simulacron.common.cluster.RequestPrime;
import com.datastax.oss.simulacron.common.codec.ConsistencyLevel;
import com.datastax.oss.simulacron.common.codec.WriteType;
import com.datastax.oss.simulacron.common.result.FunctionFailureResult;
import com.datastax.oss.simulacron.common.result.SuccessResult;
import com.datastax.oss.simulacron.common.result.SyntaxErrorResult;
import com.datastax.oss.simulacron.common.result.UnavailableResult;
import com.datastax.oss.simulacron.common.result.WriteFailureResult;
import com.datastax.oss.simulacron.common.result.WriteTimeoutResult;
import com.datastax.oss.simulacron.common.stubbing.Prime;
import com.datastax.oss.simulacron.server.BoundCluster;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.ConfigFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.reactivestreams.Publisher;

@ExtendWith(SimulacronExtension.class)
@ExtendWith(LogInterceptingExtension.class)
@ExtendWith(StreamInterceptingExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonEndToEndSimulacronIT {

  private final BoundCluster simulacron;

  private Path unloadDir;

  JsonEndToEndSimulacronIT(BoundCluster simulacron) {
    this.simulacron = simulacron;
  }

  @BeforeEach
  void setUpDirs() throws IOException {
    unloadDir = createTempDirectory("test");
  }

  @AfterEach
  void deleteDirs() {
    deleteDirectory(unloadDir);
  }

  @BeforeEach
  void primeQueries() {
    RequestPrime prime = createSimpleParametrizedQuery(INSERT_INTO_IP_BY_COUNTRY);
    simulacron.prime(new Prime(prime));
  }

  @AfterEach
  void resetLogbackConfiguration() throws JoranException {
    com.datastax.dsbulk.engine.tests.utils.EndToEndUtils.resetLogbackConfiguration();
  }

  @Test
  void full_load() throws Exception {
    String[] args = {
      "load",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      JSON_RECORDS_UNIQUE.toExternalForm(),
      "--driver.query.consistency",
      "ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(args).run();
    assertThat(status).isZero();
    validateQueryCount(simulacron, 24, "INSERT INTO ip_by_country", ONE);
  }

  @Test
  void full_load_dry_run() throws Exception {
    String[] args = {
      "load",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      JSON_RECORDS_UNIQUE.toExternalForm(),
      "-dryRun",
      "true",
      "--driver.query.consistency",
      "ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(args).run();
    assertThat(status).isZero();

    validateQueryCount(simulacron, 0, "INSERT INTO ip_by_country", ONE);
  }

  @Test
  void full_load_crlf() throws Exception {

    String[] args = {
      "load",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      JSON_RECORDS_CRLF.toExternalForm(),
      "--driver.query.consistency",
      "ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(args).run();
    assertThat(status).isZero();

    validateQueryCount(simulacron, 24, "INSERT INTO ip_by_country", ONE);
  }

  @Test
  void partial_load() throws Exception {

    String[] args = {
      "load",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      JSON_RECORDS_PARTIAL_BAD.toExternalForm(),
      "--driver.query.consistency",
      "LOCAL_ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(args).run();
    assertThat(status).isEqualTo(Main.STATUS_COMPLETED_WITH_ERRORS);

    validateQueryCount(simulacron, 21, "INSERT INTO ip_by_country", LOCAL_ONE);
    Path logPath = Paths.get(System.getProperty(LogSettings.OPERATION_DIRECTORY_KEY));
    validateBadOps(2, logPath);
    validateExceptionsLog(2, "Source  :", "mapping-errors.log", logPath);
  }

  @Test
  void load_errors() throws Exception {
    simulacron.clearPrimes(true);

    HashMap<String, Object> params = new HashMap<>();
    params.put("country_name", "Sweden");
    RequestPrime prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY,
            params,
            new SuccessResult(new ArrayList<>(), new HashMap<>()));
    simulacron.prime(new Prime(prime1));

    // recoverable errors only

    params.put("country_name", "France");
    prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY, params, new UnavailableResult(LOCAL_ONE, 1, 0));
    simulacron.prime(new Prime(prime1));

    params.put("country_name", "Gregistan");
    prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY, params, new WriteTimeoutResult(ONE, 0, 0, WriteType.BATCH));
    simulacron.prime(new Prime(prime1));

    params.put("country_name", "Andybaijan");
    prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY,
            params,
            new WriteFailureResult(ONE, 0, 0, new HashMap<>(), WriteType.BATCH));
    simulacron.prime(new Prime(prime1));

    params = new HashMap<>();
    params.put("country_name", "United States");
    prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY,
            params,
            new FunctionFailureResult(
                "keyspace", "function", new ArrayList<>(), "bad function call"));
    simulacron.prime(new Prime(prime1));

    String[] args = {
      "load",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      JSON_RECORDS_ERROR.toExternalForm(),
      "--driver.query.consistency",
      "LOCAL_ONE",
      "--driver.policy.maxRetries",
      "1",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    // There are 24 rows of data, but two extra queries due to the retry for the write timeout and
    // the unavailable.
    int status = new Main(args).run();
    assertThat(status).isEqualTo(Main.STATUS_COMPLETED_WITH_ERRORS);

    validateQueryCount(simulacron, 26, "INSERT INTO ip_by_country", LOCAL_ONE);
    Path logPath = Paths.get(System.getProperty(LogSettings.OPERATION_DIRECTORY_KEY));
    validateBadOps(4, logPath);
    validateExceptionsLog(4, "Source  :", "load-errors.log", logPath);
  }

  @Test
  void skip_test_load() throws Exception {

    String[] args = {
      "load",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      JSON_RECORDS_SKIP.toExternalForm(),
      "--driver.query.consistency",
      "LOCAL_ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--connector.json.skipRecords",
      "3",
      "--connector.json.maxRecords",
      "24",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(args).run();
    assertThat(status).isEqualTo(Main.STATUS_COMPLETED_WITH_ERRORS);

    validateQueryCount(simulacron, 21, "INSERT INTO ip_by_country", LOCAL_ONE);
    Path logPath = Paths.get(System.getProperty(LogSettings.OPERATION_DIRECTORY_KEY));
    validateBadOps(3, logPath);
    validateExceptionsLog(3, "Source  :", "mapping-errors.log", logPath);
  }

  @Test
  void full_unload() throws Exception {

    RequestPrime prime = createQueryWithResultSet(SELECT_FROM_IP_BY_COUNTRY, 24);
    simulacron.prime(new Prime(prime));

    String[] unloadArgs = {
      "unload",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      unloadDir.toString(),
      "--connector.json.maxConcurrentFiles",
      "1 ",
      "--driver.query.consistency",
      "ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(unloadArgs).run();
    assertThat(status).isZero();

    validateQueryCount(simulacron, 1, SELECT_FROM_IP_BY_COUNTRY, ONE);
    validateOutputFiles(24, unloadDir);
  }

  @Test
  void full_unload_multi_thread() throws Exception {

    // 1000 rows required to fully exercise writing to 4 files
    RequestPrime prime = createQueryWithResultSet(SELECT_FROM_IP_BY_COUNTRY, 1000);
    simulacron.prime(new Prime(prime));

    String[] unloadArgs = {
      "unload",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      unloadDir.toString(),
      "--connector.json.maxConcurrentFiles",
      "4 ",
      "--driver.query.consistency",
      "LOCAL_ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(unloadArgs).run();
    assertThat(status).isZero();

    validateQueryCount(simulacron, 1, SELECT_FROM_IP_BY_COUNTRY, ConsistencyLevel.LOCAL_ONE);
    validateOutputFiles(1000, unloadDir);
  }

  @Test
  void unload_failure_during_read_single_thread() throws Exception {

    RequestPrime prime =
        createQueryWithError(
            SELECT_FROM_IP_BY_COUNTRY, new SyntaxErrorResult("Invalid table", 0L, false));
    simulacron.prime(new Prime(prime));

    String[] unloadArgs = {
      "unload",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      unloadDir.toString(),
      "--connector.json.maxConcurrentFiles",
      "1 ",
      "--driver.query.consistency",
      "ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(unloadArgs).run();
    assertThat(status).isEqualTo(Main.STATUS_ABORTED_FATAL_ERROR);

    validateQueryCount(simulacron, 0, SELECT_FROM_IP_BY_COUNTRY, ONE);
    validatePrepare(simulacron, SELECT_FROM_IP_BY_COUNTRY);
  }

  @Test
  void unload_failure_during_read_multi_thread() throws Exception {

    RequestPrime prime =
        createQueryWithError(
            SELECT_FROM_IP_BY_COUNTRY, new SyntaxErrorResult("Invalid table", 0L, false));
    simulacron.prime(new Prime(prime));

    String[] unloadArgs = {
      "unload",
      "-c",
      "json",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.json.url",
      unloadDir.toString(),
      "--connector.json.maxConcurrentFiles",
      "4 ",
      "--driver.query.consistency",
      "ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(unloadArgs).run();
    assertThat(status).isEqualTo(Main.STATUS_ABORTED_FATAL_ERROR);

    validateQueryCount(simulacron, 0, SELECT_FROM_IP_BY_COUNTRY, ONE);
    validatePrepare(simulacron, SELECT_FROM_IP_BY_COUNTRY);
  }

  @Test
  void unload_write_error(
      @LogCapture(value = Main.class, level = ERROR) LogInterceptor logs,
      @StreamCapture(STDERR) StreamInterceptor stdErr)
      throws Exception {

    MockConnector.setDelegate(
        new JsonConnector() {

          @Override
          public void configure(LoaderConfig settings, boolean read) {
            settings =
                new DefaultLoaderConfig(
                    ConfigFactory.parseMap(
                            ImmutableMap.of("url", unloadDir.toString(), "header", "false"))
                        .withFallback(ConfigFactory.load().getConfig("dsbulk.connector.json")));
            super.configure(settings, read);
          }

          @Override
          public Function<? super Publisher<Record>, ? extends Publisher<Record>> write() {
            // Prior to DAT-151 and DAT-191 this case would hang
            try {
              Files.createFile(unloadDir.resolve("output-000001.json"));
            } catch (IOException ignored) {
            }
            return super.write();
          }
        });

    RequestPrime prime = createQueryWithResultSet(CsvUtils.SELECT_FROM_IP_BY_COUNTRY, 10);
    simulacron.prime(new Prime(prime));

    String[] unloadArgs = {
      "unload",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "--connector.name",
      "mock",
      "--driver.query.consistency",
      "ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      CsvUtils.SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      CsvUtils.IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(unloadArgs).run();
    assertThat(status).isEqualTo(Main.STATUS_ABORTED_FATAL_ERROR);

    assertThat(stdErr.getStreamAsString())
        .contains(logs.getLoggedMessages())
        .contains("Error writing to file:")
        .contains("output-000001.json");
  }

  @Test
  void validate_stdout(
      @StreamCapture(STDOUT) StreamInterceptor stdOut,
      @StreamCapture(STDERR) StreamInterceptor stdErr,
      @LogCapture(LogSettings.class) LogInterceptor logs)
      throws Exception {

    RequestPrime prime = createQueryWithResultSet(CsvUtils.SELECT_FROM_IP_BY_COUNTRY, 24);
    simulacron.prime(new Prime(prime));

    setProductionKey();

    String[] args = {
      "unload",
      "--log.directory",
      Files.createTempDirectory("test").toString(),
      "-header",
      "false",
      "--connector.csv.url",
      "-",
      "--connector.csv.maxConcurrentFiles",
      "1 ",
      "--driver.query.consistency",
      "ONE",
      "--driver.hosts",
      fetchContactPoints(simulacron),
      "--driver.pooling.local.connections",
      "1",
      "--schema.query",
      CsvUtils.SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      CsvUtils.IP_BY_COUNTRY_MAPPING
    };

    int status = new Main(args).run();
    assertThat(status).isZero();

    validateQueryCount(simulacron, 1, CsvUtils.SELECT_FROM_IP_BY_COUNTRY, ONE);
    assertThat(stdOut.getStreamLines().size()).isEqualTo(24);
    assertThat(stdErr.getStreamAsString())
        .contains("Standard output is reserved, log messages are redirected to standard error.");
    assertThat(logs.getAllMessagesAsString())
        .contains("Standard output is reserved, log messages are redirected to standard error.");
  }
}