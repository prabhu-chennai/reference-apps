package com.databricks.apps.logs;

import java.io.IOException;

import com.google.common.collect.Iterators;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

/**
 * The LogAnalyzerAppMain is an sample logs analysis application.  For now,
 * it is a simple minimal viable product:
 *   - Read in new log files from a directory and input those new files into streaming.
 *   - Computes stats for all of time as well as the last time interval based on those logs.
 *   - Write the calculated stats to an txt file on the local file system
 *     that gets refreshed every time interval.
 *
 * Once you get this program up and running, feed apache access log files
 * into the local directory of your choosing.
 *
 * Then open your output text file, perhaps in a web browser, and refresh
 * that page to see more stats come in.
 *
 * Modify the command line flags to the values of your choosing.
 * Notice how they come after you specify the jar when using spark-submit.
 *
 * Example command to run:
 * %  ${YOUR_SPARK_HOME}/bin/spark-submit
 *     --class "com.databricks.apps.logs.LogAnalyzerAppMain"
 *     --master local[4]
 *     target/uber-log-analyzer-2.0.jar
 *     --logs_directory /tmp/logs
 *     --output_html_file /tmp/log_stats.html
 *     --window_length 30
 *     --slide_interval 5
 *     --checkpoint_directory /tmp/log-analyzer-streaming
 */
public class LogAnalyzerAppMain {
  public static final String WINDOW_LENGTH = "window_length";
  public static final String SLIDE_INTERVAL = "slide_interval";
  public static final String LOGS_DIRECTORY = "logs_directory";
  public static final String OUTPUT_HTML_FILE = "output_html_file";
  public static final String CHECKPOINT_DIRECTORY = "checkpoint_directory";

  private static final Options THE_OPTIONS = createOptions();
  private static Options createOptions() {
    Options options = new Options();

    options.addOption(
        new Option(WINDOW_LENGTH, true, "The window length in seconds"));
    options.addOption(
        new Option(SLIDE_INTERVAL, true, "The slide interval in seconds"));
    options.addOption(
        new Option(LOGS_DIRECTORY, true, "The directory where logs are written"));
    options.addOption(
        new Option(OUTPUT_HTML_FILE, true, "Where to write output html file"));
    options.addOption(
        new Option(CHECKPOINT_DIRECTORY, true, "The checkpoint directory."));
    return options;
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    Flags.setFromCommandLineArgs(THE_OPTIONS, args);

    // Startup the Spark Conf.
    SparkConf conf = new SparkConf()
        .setAppName("A Databricks Reference Application: Logs Analysis with Spark");
    JavaSparkContext sc = new JavaSparkContext(conf);
    JavaStreamingContext jssc = new JavaStreamingContext(sc,
        Flags.getInstance().getSlideInterval());

    // Checkpointing must be enabled to use the updateStateByKey function.
    jssc.checkpoint(Flags.getInstance().getCheckpointDirectory());

    // This methods monitors a directory for new files to read in for streaming.
    JavaDStream<String> logData = jssc.textFileStream(Flags.getInstance().getLogsDirectory());
    
    JavaDStream<ApacheAccessLog> accessLogsDStream = logData.flatMap(
        line -> {
            try {
                return Iterators.singletonIterator(ApacheAccessLog.parseFromLogLine(line));
            } catch (IOException e) {
                return Iterators.emptyIterator();
            }
        }
    ).cache();

    LogAnalyzerTotal logAnalyzerTotal = new LogAnalyzerTotal();
    LogAnalyzerWindowed logAnalyzerWindowed = new LogAnalyzerWindowed();

    // Process the DStream which gathers stats for all of time.
    logAnalyzerTotal.processAccessLogs(accessLogsDStream);

    // Calculate statistics for the last time interval.
    logAnalyzerWindowed.processAccessLogs(accessLogsDStream);

    // Render the output each time there is a new RDD in the accessLogsDStream.
    Renderer renderer = new Renderer();
    accessLogsDStream.foreachRDD(rdd -> {
      // Call this to output the stats.
      renderer.render(logAnalyzerTotal.getLogStatistics(),
          logAnalyzerWindowed.getLogStatistics());
    });

    // Start the streaming server.
    jssc.start();              // Start the computation
    jssc.awaitTermination();   // Wait for the computation to terminate
  }
}
