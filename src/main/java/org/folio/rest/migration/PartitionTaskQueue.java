package org.folio.rest.migration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.folio.rest.migration.model.request.AbstractContext;
import org.folio.rest.migration.utility.TimingUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PartitionTaskQueue<C extends AbstractContext> {

  private static final Logger log = LoggerFactory.getLogger(PartitionTaskQueue.class);

  private final long startTime = System.nanoTime();

  private final C context;

  private final TaskCallback callback;

  private final ExecutorService executor;

  private final BlockingQueue<PartitionTask<C>> inProcess;

  private final BlockingQueue<PartitionTask<C>> inWait;

  public PartitionTaskQueue(C context, TaskCallback callback) {
    this.context = context;
    this.callback = callback;
    this.executor = Executors.newFixedThreadPool(context.getParallelism());
    this.inProcess = new ArrayBlockingQueue<>(context.getParallelism());
    this.inWait = new ArrayBlockingQueue<>(1024);
  }

  public synchronized void submit(PartitionTask<C> task) {
    if (inProcess.size() < context.getParallelism()) {
      start(task);
    } else {
      inWait.add(task);
    }
  }

  public synchronized void complete(PartitionTask<C> task) {
    inProcess.remove(task);
    try {
      if (inWait.isEmpty()) {
        if (inProcess.isEmpty()) {
          shutdown();
        }
      } else {
        start(inWait.take());
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void start(PartitionTask<C> task) {
    inProcess.add(task);
    CompletableFuture.supplyAsync(() -> task.execute(context), executor).thenAccept(this::complete);
  }

  private void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(1, TimeUnit.MINUTES);
    executor.shutdownNow();
    log.info("finished: {} milliseconds", TimingUtility.getDeltaInMilliseconds(startTime));
    callback.complete();
  }

}
