package org.apache.hadoop.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class TaskResourceUsageCalculator {
  private Log log = null;
  private Runtime runtime = null;
  private long minMemoryUsed, maxMemoryUsed;
  private int count = 0;
  private double meanMemoryUsed = -1;

  public TaskResourceUsageCalculator(String logname) {
    log = LogFactory.getLog(logname);
    runtime = Runtime.getRuntime();
    minMemoryUsed = Long.MAX_VALUE;
    maxMemoryUsed = Long.MIN_VALUE;
  }

  public void record() {
    long used = runtime.totalMemory() - runtime.freeMemory();
    minMemoryUsed = Math.min(minMemoryUsed, used);
    maxMemoryUsed = Math.max(maxMemoryUsed, used);

    if (meanMemoryUsed == -1) {
      count = 1;
      meanMemoryUsed = used;
    } else {
      meanMemoryUsed = (meanMemoryUsed * count + used) / (++count);
    }
  }

  public void log() {
    log.info("Min memory = " + minMemoryUsed);
    log.info("Max memory = " + maxMemoryUsed);
    log.info("Mean memory = " + meanMemoryUsed);
  }
}
