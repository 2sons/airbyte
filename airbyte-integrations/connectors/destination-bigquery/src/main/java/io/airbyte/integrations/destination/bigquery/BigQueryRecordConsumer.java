/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.bigquery;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.TableId;
import io.airbyte.cdk.integrations.base.AirbyteMessageConsumer;
import io.airbyte.cdk.integrations.base.FailureTrackingAirbyteMessageConsumer;
import io.airbyte.cdk.integrations.destination.StreamSyncSummary;
import io.airbyte.cdk.integrations.destination_async.AsyncStreamConsumer;
import io.airbyte.cdk.integrations.util.ConnectorExceptionUtil;
import io.airbyte.integrations.base.destination.typing_deduping.ParsedCatalog;
import io.airbyte.integrations.base.destination.typing_deduping.StreamConfig;
import io.airbyte.integrations.base.destination.typing_deduping.TypeAndDedupeOperationValve;
import io.airbyte.integrations.base.destination.typing_deduping.TyperDeduper;
import io.airbyte.integrations.destination.bigquery.formatter.DefaultBigQueryRecordFormatter;
import io.airbyte.integrations.destination.bigquery.uploader.AbstractBigQueryUploader;
import io.airbyte.protocol.models.v0.AirbyteMessage;
import io.airbyte.protocol.models.v0.AirbyteMessage.Type;
import io.airbyte.protocol.models.v0.AirbyteStreamNameNamespacePair;
import io.airbyte.protocol.models.v0.DestinationSyncMode;
import io.airbyte.protocol.models.v0.StreamDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Record Consumer used for STANDARD INSERTS
 */
@SuppressWarnings("try")
class BigQueryRecordConsumer extends FailureTrackingAirbyteMessageConsumer implements AirbyteMessageConsumer {

  private static final Logger LOGGER = LoggerFactory.getLogger(BigQueryRecordConsumer.class);

  private final BigQuery bigquery;
  private final Map<AirbyteStreamNameNamespacePair, AbstractBigQueryUploader<?>> uploaderMap;
  private final Consumer<AirbyteMessage> outputRecordCollector;
  private final String defaultDatasetId;
  private AirbyteMessage lastStateMessage = null;

  private final TypeAndDedupeOperationValve streamTDValve = new TypeAndDedupeOperationValve();
  private final ParsedCatalog catalog;
  private final TyperDeduper typerDeduper;
  private final ConcurrentMap<StreamDescriptor, AtomicLong> recordCounts;

  public BigQueryRecordConsumer(final BigQuery bigquery,
                                final Map<AirbyteStreamNameNamespacePair, AbstractBigQueryUploader<?>> uploaderMap,
                                final Consumer<AirbyteMessage> outputRecordCollector,
                                final String defaultDatasetId,
                                final TyperDeduper typerDeduper,
                                final ParsedCatalog catalog) {
    this.bigquery = bigquery;
    this.uploaderMap = uploaderMap;
    this.outputRecordCollector = outputRecordCollector;
    this.defaultDatasetId = defaultDatasetId;
    this.typerDeduper = typerDeduper;
    this.catalog = catalog;

    this.recordCounts = new ConcurrentHashMap<>();
    catalog.streams().forEach(stream -> recordCounts.put(stream.id().asStreamDescriptor(), new AtomicLong(0)));

    LOGGER.info("Got parsed catalog {}", catalog);
    LOGGER.info("Got canonical stream IDs {}", uploaderMap.keySet());
  }

  @Override
  protected void startTracked() {
    // todo (cgardens) - move contents of #write into this method.
    // Set up our raw tables
    uploaderMap.forEach((streamId, uploader) -> {
      final StreamConfig stream = catalog.getStream(streamId);
      if (stream.destinationSyncMode() == DestinationSyncMode.OVERWRITE) {
        // For streams in overwrite mode, truncate the raw table.
        // non-1s1t syncs actually overwrite the raw table at the end of the sync, so we only do this in
        // 1s1t mode.
        final TableId rawTableId = TableId.of(stream.id().rawNamespace(), stream.id().rawName());
        bigquery.delete(rawTableId);
        BigQueryUtils.createPartitionedTableIfNotExists(bigquery, rawTableId, DefaultBigQueryRecordFormatter.SCHEMA_V2);
      } else {
        uploader.createRawTable();
      }
    });
  }

  /**
   * Processes STATE and RECORD {@link AirbyteMessage} with all else logged as unexpected
   *
   * <li>For STATE messages emit messages back to the platform</li>
   * <li>For RECORD messages upload message to associated Airbyte Stream. This means that RECORDS will
   * be associated with their respective streams when more than one record exists</li>
   *
   * @param message {@link AirbyteMessage} to be processed
   */
  @Override
  public void acceptTracked(final AirbyteMessage message) throws Exception {
    if (message.getType() == Type.STATE) {
      lastStateMessage = message;
      outputRecordCollector.accept(message);
    } else if (message.getType() == Type.RECORD) {
      if (StringUtils.isEmpty(message.getRecord().getNamespace())) {
        message.getRecord().setNamespace(defaultDatasetId);
      }
      processRecord(message);
    } else {
      LOGGER.warn("Unexpected message: {}", message.getType());
    }
  }

  /**
   * Processes {@link io.airbyte.protocol.models.AirbyteRecordMessage} by writing Airbyte stream data
   * to Big Query Writer
   *
   * @param message record to be written
   */
  private void processRecord(final AirbyteMessage message) {
    final var streamId = AirbyteStreamNameNamespacePair.fromRecordMessage(message.getRecord());
    uploaderMap.get(streamId).upload(message);
    // We are not doing any incremental typing and de-duping for Standard Inserts, see
    // https://github.com/airbytehq/airbyte/issues/27586

    recordCounts
        .get(new StreamDescriptor()
            .withName(streamId.getName())
            .withNamespace(streamId.getNamespace())
        ).incrementAndGet();
  }

  @Override
  public void close(final boolean hasFailed) throws Exception {
    LOGGER.info("Started closing all connections");
    final List<Exception> exceptionsThrown = new ArrayList<>();
    uploaderMap.forEach((streamId, uploader) -> uploader.close(hasFailed, outputRecordCollector, lastStateMessage));

    Map<StreamDescriptor, StreamSyncSummary> streamSyncSummaries = AsyncStreamConsumer.getSyncSummaries(
        catalog.streams().stream().map(stream -> stream.id().asStreamDescriptor()).toList(),
        recordCounts
    );
    typerDeduper.typeAndDedupe(streamSyncSummaries);
    typerDeduper.commitFinalTables();
    typerDeduper.cleanup();

    ConnectorExceptionUtil.logAllAndThrowFirst("Exceptions thrown while closing consumer: ", exceptionsThrown);
  }

}
