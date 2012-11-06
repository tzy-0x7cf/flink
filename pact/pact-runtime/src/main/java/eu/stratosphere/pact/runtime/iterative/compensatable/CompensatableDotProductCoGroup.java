package eu.stratosphere.pact.runtime.iterative.compensatable;

import eu.stratosphere.nephele.configuration.Configuration;
import eu.stratosphere.pact.common.stubs.CoGroupStub;
import eu.stratosphere.pact.common.stubs.Collector;
import eu.stratosphere.pact.common.type.PactRecord;
import eu.stratosphere.pact.common.type.base.PactDouble;
import eu.stratosphere.pact.common.type.base.PactLong;
import eu.stratosphere.pact.runtime.iterative.concurrent.IterationContext;

import java.util.Iterator;
import java.util.Random;

public class CompensatableDotProductCoGroup extends CoGroupStub {

  private PactRecord accumulator;

  private int workerIndex;
  private int currentIteration;

  private int failingIteration;
  private int failingWorker;
  private double messageLoss;

  private PageRankStatsAggregator aggregator = (PageRankStatsAggregator) new DiffL1NormConvergenceCriterion()
      .createAggregator();

  private long numVertices;
  private static final double beta = 0.85;

  @Override
  public void open(Configuration parameters) throws Exception {
    accumulator = new PactRecord();
    workerIndex = parameters.getInteger("pact.parallel.task.id", -1);
    if (workerIndex == -1) {
      throw new IllegalStateException("Invalid workerIndex " + workerIndex);
    }

    failingIteration = parameters.getInteger("compensation.failingIteration", -1);
    if (failingIteration == -1) {
      throw new IllegalStateException();
    }
    failingWorker = parameters.getInteger("compensation.failingWorker", -1);
    if (failingWorker == -1) {
      throw new IllegalStateException();
    }
    messageLoss = Double.parseDouble(parameters.getString("compensation.messageLoss", "-1"));
    if (messageLoss == -1) {
      throw new IllegalStateException();
    }
    currentIteration = parameters.getInteger("pact.iterations.currentIteration", -1);
    if (currentIteration == -1) {
      throw new IllegalStateException();
    }

    aggregator.reset();

    numVertices = parameters.getLong("pageRank.numVertices", -1);
    if (numVertices == -1) {
      throw new IllegalStateException();
    }

  }

  @Override
  public void coGroup(Iterator<PactRecord> currentPageRankIterator, Iterator<PactRecord> partialRanks,
      Collector<PactRecord> collector) {

    if (!currentPageRankIterator.hasNext()) {
      throw new IllegalStateException("No current page rank!");
    }
    PactRecord currentPageRank = currentPageRankIterator.next();

    double rank = 0;

    while (partialRanks.hasNext()) {
      PactRecord record = partialRanks.next();
//      if (currentPageRank.getField(0, PactLong.class).getValue() != record.getField(0, PactLong.class).getValue()) {
//        throw new IllegalStateException();
//      }
     rank += record.getField(1, PactDouble.class).getValue();
    }

    rank = beta * rank + (1d - beta) * (1d / numVertices) ;

    double currentRank = currentPageRank.getField(1, PactDouble.class).getValue();

    double diff = Math.abs(currentRank - rank);

    aggregator.aggregate(diff, rank, 1);

    accumulator.setField(0, currentPageRank.getField(0, PactLong.class));
    accumulator.setField(1, new PactDouble(rank));

    collector.collect(accumulator);
  }

  @Override
  public void close() throws Exception {
    //witzlos
    if (currentIteration == failingIteration && workerIndex == failingWorker) {
      aggregator.reset();
    }
    IterationContext.instance().setAggregate(workerIndex, aggregator.getAggregate());
  }
}
