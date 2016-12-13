package io.anserini.rts;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.args4j.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public class TRECSearcher {
  public static class SearchArgs {
    @Option(name = "-host", metaVar = "[String]", required = true, usage = "host details")
    public String host;

    @Option(name = "-index", metaVar = "[file]", required = true, usage = "path to the index")
    public String index;

    @Option(name = "-port", metaVar = "[String]", required = true, usage = "port details")
    public String port;

    @Option(name = "-groupid", metaVar = "[String]", required = true, usage = "groupid details")
    public String groupid;

    //optional arguments
    @Option(name = "-batch", required = true, forbids = {"-host", "-index", "-port", "-groupid"},
        usage = "batch evaluation if specified")
    public boolean batch = true;
  }

  public static final Logger LOG = LogManager.getLogger(TRECSearcher.class);

  static BufferedWriter scenarioALogWriter;
  static BufferedWriter scenarioBLogWriter;

  /* Change values for the following 3, non-critical variable */
  static final String interestProfilePath = "src/main/java/io/anserini/rts/TREC2016Profiles/";
  private static final String scenarioLogPath = "src/main/java/io/anserini/rts/scenarioLog";
  static final String alias = "WaterlooBaseline";

  static String api_base;
  static String clientid;
  static TRECTopic[] topics;

  private static String groupid;
  static String indexName;

  private static ArrayList<TimerTask> threadList = new ArrayList<TimerTask>();
  private static ArrayList<Timer> timerList = new ArrayList<Timer>();

  static long minuteInterval = 60 * 1000;
  static long dailyInterval = 24 * 60 * minuteInterval;
  /*
   * Organizer suggests that poll the broker API for topics no more frequent
   * than once every hour
   */
  private static long topicCheckInterval = 60 * minuteInterval;

  public void close() throws IOException {
    Indexer.close();
  }

  public static void keepTaskInList(TimerTask tasknew, Timer timer) {
    threadList.add(tasknew);
    timerList.add(timer);
  }

  public static void main(String[] args) throws Exception {
    final SearchArgs searchArgs = new SearchArgs();
//    CmdLineParser parser = new CmdLineParser(searchArgs, ParserProperties.defaults().withUsageWidth(90));
    final String INDEX_OPTION = "index";

    Calendar now;
    Calendar tomorrow;
//
//    try {
//      parser.parseArgument(args);
//    } catch (CmdLineException e) {
//      System.err.println(e.getMessage());
//      parser.printUsage(System.err);
//      System.err.println("Example: " + SearchArgs.class.getSimpleName() + parser.printExample(OptionHandlerFilter.REQUIRED));
//      return;
//    }

    SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.ENGLISH);
    searchArgs.batch = true;
    if (!searchArgs.batch) {
      String host = searchArgs.host;
      groupid = searchArgs.groupid;
      int port = Integer.parseInt(searchArgs.port);
      api_base = new String("http://" + host + ":" + port + "/");

      clientid = Registrar.register(api_base, groupid, alias);
      topics = TopicPoller.getInitialTopics(api_base, clientid, interestProfilePath);

      now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
      tomorrow = Calendar.getInstance();
    } else {
      //read the interest profile from the local disk
      topics = TopicPoller.getTopicsFromFile(interestProfilePath);

      // go back in time
      now = Calendar.getInstance();
      now.setTime(sdf.parse("Mon Aug 1 00:00:00 UTC 2016"));
      // System.out.println(sdf.format(now.getTime()));
      tomorrow = now;
    }

    tomorrow.set(Calendar.HOUR, 0);
    tomorrow.set(Calendar.MINUTE, 0);
    tomorrow.set(Calendar.SECOND, 0);
    tomorrow.set(Calendar.AM_PM, Calendar.AM);
    tomorrow.set(Calendar.DATE, now.get(Calendar.DATE) + 1);
    tomorrow.set(Calendar.DAY_OF_YEAR, now.get(Calendar.DAY_OF_YEAR) + 1);
    tomorrow.setTimeZone(TimeZone.getTimeZone("UTC"));
//    System.out.println(sdf.format(tomorrow.getTime()));
    indexName = Indexer.StartIndexing(INDEX_OPTION);

    File file = new File(scenarioLogPath);
    boolean isDirectoryCreated = file.mkdir();
    if (isDirectoryCreated) {
      LOG.info("Scenario log profile directory successfully made");
    } else {
      FileUtils.deleteDirectory(file);
      file.mkdir();
      LOG.info("Scenario log profile directory successfully covered");
    }
    scenarioALogWriter = new BufferedWriter(new FileWriter(new File(scenarioLogPath + "/scenarioALog")));
    scenarioBLogWriter = new BufferedWriter(new FileWriter(new File(scenarioLogPath + "/scenarioBLog")));

    for (TRECTopic topic : topics) {
      Timer timer = new Timer();
      TimerTask tasknew = new TRECScenarioRunnable(indexName, interestProfilePath + topic.topid + ".json",
          api_base + "tweet/" + topic.topid + "/:tweetid/" + clientid, "A");

      // Schedule scenario A search task every minute Interval
      // At the first time, there's a 30000 milliseconds delay for the
      // delay in connecting Twitter Streaming API
      timer.scheduleAtFixedRate(tasknew, 30000, minuteInterval);
      keepTaskInList(tasknew, timer);

    }

    for (TRECTopic topic : topics) {
      Timer timer = new Timer();
      TimerTask tasknew = new TRECScenarioRunnable(indexName, interestProfilePath + topic.topid + ".json",
          api_base + "tweets/" + topic.topid + "/" + clientid, "B");
      LOG.info("Scenario B will start at epoch " + tomorrow.getTimeInMillis() + " Now is " + now.getTimeInMillis());

      // [Deprecated!] Scenario A only for this year
      // Schedule scenario B search task every day at 0'00'01
      // The 1000 milliseconds delay is to ensure that the search action
      // lies exactly at the new day, as long as 1000 milliseconds delay
      // will not discount the reward.
      // At the first time, there's a delay to wait till a new day.
      timer.scheduleAtFixedRate(tasknew, (long) (tomorrow.getTimeInMillis() - now.getTimeInMillis() + 1000),
          dailyInterval);
      keepTaskInList(tasknew, timer);
    }

    Timer timer = new Timer();
    TimerTask tasknew = new NewTopicsPeriodicalRunnable();
    LOG.info("Successfully set up the thread for periodically check new topics every" + topicCheckInterval);
    timer.scheduleAtFixedRate(tasknew, 0, topicCheckInterval);
    keepTaskInList(tasknew, timer);

    Indexer.join();
  }
}