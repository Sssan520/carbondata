/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.carbondata.processing.loading.steps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.datastore.exception.CarbonDataWriterException;
import org.apache.carbondata.core.datastore.row.CarbonRow;
import org.apache.carbondata.core.localdictionary.generator.LocalDictionaryGenerator;
import org.apache.carbondata.core.metadata.CarbonTableIdentifier;
import org.apache.carbondata.core.util.CarbonThreadFactory;
import org.apache.carbondata.core.util.CarbonTimeStatisticsFactory;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.core.util.path.CarbonTablePath;
import org.apache.carbondata.processing.datamap.DataMapWriterListener;
import org.apache.carbondata.processing.loading.AbstractDataLoadProcessorStep;
import org.apache.carbondata.processing.loading.CarbonDataLoadConfiguration;
import org.apache.carbondata.processing.loading.exception.CarbonDataLoadingException;
import org.apache.carbondata.processing.loading.row.CarbonRowBatch;
import org.apache.carbondata.processing.store.CarbonFactDataHandlerModel;
import org.apache.carbondata.processing.store.CarbonFactHandler;
import org.apache.carbondata.processing.store.CarbonFactHandlerFactory;
import org.apache.carbondata.processing.util.CarbonDataProcessorUtil;

import org.apache.log4j.Logger;

/**
 * It reads data from sorted files which are generated in previous sort step.
 * And it writes data to carbondata file. It also generates mdk key while writing to carbondata file
 */
public class DataWriterProcessorStepImpl extends AbstractDataLoadProcessorStep {

  private static final Logger LOGGER =
      LogServiceFactory.getLogService(DataWriterProcessorStepImpl.class.getName());

  private long readCounter;

  private DataMapWriterListener listener;

  private final Map<String, LocalDictionaryGenerator> localDictionaryGeneratorMap;

  private ExecutorService rangeExecutorService;

  private List<CarbonFactHandler> carbonFactHandlers;

  public DataWriterProcessorStepImpl(CarbonDataLoadConfiguration configuration,
      AbstractDataLoadProcessorStep child) {
    super(configuration, child);
    this.localDictionaryGeneratorMap =
        CarbonUtil.getLocalDictionaryModel(configuration.getTableSpec().getCarbonTable());
  }

  public DataWriterProcessorStepImpl(CarbonDataLoadConfiguration configuration) {
    super(configuration, null);
    this.localDictionaryGeneratorMap =
        CarbonUtil.getLocalDictionaryModel(configuration.getTableSpec().getCarbonTable());
  }

  @Override public void initialize() throws IOException {
    super.initialize();
    child.initialize();
    this.carbonFactHandlers = new CopyOnWriteArrayList<>();
  }

  private String[] getStoreLocation() {
    String[] storeLocation = CarbonDataProcessorUtil
        .getLocalDataFolderLocation(configuration.getTableSpec().getCarbonTable(),
            String.valueOf(configuration.getTaskNo()), configuration.getSegmentId(), false, false);
    CarbonDataProcessorUtil.createLocations(storeLocation);
    return storeLocation;
  }

  public CarbonFactDataHandlerModel getDataHandlerModel() {
    String[] storeLocation = getStoreLocation();
    listener = getDataMapWriterListener(0);
    CarbonFactDataHandlerModel carbonFactDataHandlerModel = CarbonFactDataHandlerModel
        .createCarbonFactDataHandlerModel(configuration, storeLocation, 0, 0, listener);
    carbonFactDataHandlerModel.setColumnLocalDictGenMap(localDictionaryGeneratorMap);
    return carbonFactDataHandlerModel;
  }

  @Override public Iterator<CarbonRowBatch>[] execute() throws CarbonDataLoadingException {
    Iterator<CarbonRowBatch>[] iterators = child.execute();
    CarbonTableIdentifier tableIdentifier =
        configuration.getTableIdentifier().getCarbonTableIdentifier();
    String tableName = tableIdentifier.getTableName();
    try {
      CarbonTimeStatisticsFactory.getLoadStatisticsInstance()
          .recordDictionaryValue2MdkAdd2FileTime(CarbonTablePath.DEPRECATED_PATITION_ID,
              System.currentTimeMillis());
      rangeExecutorService = Executors.newFixedThreadPool(iterators.length,
          new CarbonThreadFactory("WriterForwardPool: " + tableName));
      List<Future<Void>> rangeExecutorServiceSubmitList = new ArrayList<>(iterators.length);
      int i = 0;
      // do this concurrently
      for (Iterator<CarbonRowBatch> iterator : iterators) {
        rangeExecutorServiceSubmitList.add(
            rangeExecutorService.submit(new WriterForwarder(iterator, i)));
        i++;
      }
      try {
        rangeExecutorService.shutdown();
        rangeExecutorService.awaitTermination(2, TimeUnit.DAYS);
        for (int j = 0; j < rangeExecutorServiceSubmitList.size(); j++) {
          rangeExecutorServiceSubmitList.get(j).get();
        }
      } catch (InterruptedException e) {
        throw new CarbonDataWriterException(e);
      } catch (ExecutionException e) {
        throw new CarbonDataWriterException(e.getCause());
      }
    } catch (CarbonDataWriterException e) {
      throw new CarbonDataLoadingException("Error while initializing writer: " + e.getMessage(), e);
    } catch (Exception e) {
      throw new CarbonDataLoadingException("There is an unexpected error: " + e.getMessage(), e);
    }
    return null;
  }

  @Override protected String getStepName() {
    return "Data Writer";
  }

  /**
   * Used to forward rows to different ranges based on range id.
   */
  private final class WriterForwarder implements Callable<Void> {
    private Iterator<CarbonRowBatch> insideRangeIterator;
    private int rangeId;

    WriterForwarder(Iterator<CarbonRowBatch> insideRangeIterator, int rangeId) {
      this.insideRangeIterator = insideRangeIterator;
      this.rangeId = rangeId;
    }

    @Override public Void call() {
      processRange(insideRangeIterator, rangeId);
      return null;
    }
  }

  private void processRange(Iterator<CarbonRowBatch> insideRangeIterator, int rangeId) {
    String[] storeLocation = getStoreLocation();

    listener = getDataMapWriterListener(rangeId);
    CarbonFactDataHandlerModel model = CarbonFactDataHandlerModel
        .createCarbonFactDataHandlerModel(configuration, storeLocation, rangeId, 0, listener);
    model.setColumnLocalDictGenMap(localDictionaryGeneratorMap);
    CarbonFactHandler dataHandler = null;
    boolean rowsNotExist = true;
    while (insideRangeIterator.hasNext()) {
      if (rowsNotExist) {
        rowsNotExist = false;
        dataHandler = CarbonFactHandlerFactory.createCarbonFactHandler(model);
        carbonFactHandlers.add(dataHandler);
        dataHandler.initialise();
      }
      processBatch(insideRangeIterator.next(), dataHandler);
    }
    if (!rowsNotExist) {
      finish(dataHandler);
    }
    carbonFactHandlers.remove(dataHandler);
  }

  public void finish(CarbonFactHandler dataHandler) {
    CarbonTableIdentifier tableIdentifier =
        configuration.getTableIdentifier().getCarbonTableIdentifier();
    String tableName = tableIdentifier.getTableName();
    dataHandler.finish();
    LOGGER.info("Record Processed For table: " + tableName);
    String logMessage =
        "Finished Carbon DataWriterProcessorStepImpl: Read: " + readCounter + ": Write: "
            + rowCounter.get();
    LOGGER.info(logMessage);
    CarbonTimeStatisticsFactory.getLoadStatisticsInstance().recordTotalRecords(rowCounter.get());
    processingComplete(dataHandler);
    CarbonTimeStatisticsFactory.getLoadStatisticsInstance()
        .recordDictionaryValue2MdkAdd2FileTime(CarbonTablePath.DEPRECATED_PATITION_ID,
            System.currentTimeMillis());
    CarbonTimeStatisticsFactory.getLoadStatisticsInstance()
        .recordMdkGenerateTotalTime(CarbonTablePath.DEPRECATED_PATITION_ID,
            System.currentTimeMillis());
  }

  private void processingComplete(CarbonFactHandler dataHandler) {
    if (null != dataHandler) {
      dataHandler.closeHandler();
    }
  }

  private void processBatch(CarbonRowBatch batch, CarbonFactHandler dataHandler) {
    while (batch.hasNext()) {
      CarbonRow row = batch.next();
      dataHandler.addDataToStore(row);
      readCounter++;
    }
    rowCounter.getAndAdd(batch.getSize());
  }

  public void processRow(CarbonRow row, CarbonFactHandler dataHandler) {
    readCounter++;
    dataHandler.addDataToStore(row);
    rowCounter.getAndAdd(1);
  }

  @Override public void close() {
    if (!closed) {
      super.close();
      if (listener != null) {
        try {
          LOGGER.info("closing all the DataMap writers registered to DataMap writer listener");
          listener.finish();
        } catch (IOException e) {
          LOGGER.error("error while closing the datamap writers", e);
          // ignoring the exception
        }
      }
      if (null != rangeExecutorService) {
        rangeExecutorService.shutdownNow();
      }
      if (null != this.carbonFactHandlers && !this.carbonFactHandlers.isEmpty()) {
        for (CarbonFactHandler carbonFactHandler : this.carbonFactHandlers) {
          carbonFactHandler.finish();
          carbonFactHandler.closeHandler();
        }
      }
    }
  }
}
