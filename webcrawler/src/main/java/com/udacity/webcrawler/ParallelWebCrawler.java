package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.IgnoredWords;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.regex.Pattern;

/**
 * Parallel web crawler implementation using ForkJoinPool.
 */
final class ParallelWebCrawler implements WebCrawler {

  private final Clock clock;
  private final Duration timeout;
  private final int maxDepth;
  private final int popularWordCount;
  private final List<Pattern> ignoredUrls;
  private final List<Pattern> ignoredWords;
  private final PageParserFactory parserFactory;
  private final ForkJoinPool forkJoinPool;

  @Inject
  ParallelWebCrawler(
          Clock clock,
          PageParserFactory parserFactory,
          @Timeout Duration timeout,
          @PopularWordCount int popularWordCount,
          @MaxDepth int maxDepth,
          @IgnoredUrls List<Pattern> ignoredUrls,
          @IgnoredWords List<Pattern> ignoredWords,
          @TargetParallelism int parallelism) {

    this.clock = clock;
    this.parserFactory = parserFactory;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
    this.ignoredWords = ignoredWords;
    this.forkJoinPool =
            new ForkJoinPool(Math.min(parallelism, getMaxParallelism()));
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {

    if (startingUrls == null || startingUrls.isEmpty() || maxDepth == 0) {
      return new CrawlResult.Builder()
              .setWordCounts(Map.of())
              .setUrlsVisited(0)
              .build();
    }

    Instant deadline = clock.instant().plus(timeout);

    Set<String> visited = ConcurrentHashMap.newKeySet();
    ConcurrentMap<String, Integer> counts = new ConcurrentHashMap<>();

    forkJoinPool.invoke(
            new CrawlJob(startingUrls, 0, deadline, visited, counts)
    );

    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visited.size())
            .build();
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }

  /**
   * A recursive job that crawls a batch of URLs.
   */
  private final class CrawlJob extends RecursiveAction {

    private final List<String> urls;
    private final int depth;
    private final Instant deadline;
    private final Set<String> visited;
    private final ConcurrentMap<String, Integer> counts;

    CrawlJob(
            List<String> urls,
            int depth,
            Instant deadline,
            Set<String> visited,
            ConcurrentMap<String, Integer> counts) {

      this.urls = urls;
      this.depth = depth;
      this.deadline = deadline;
      this.visited = visited;
      this.counts = counts;
    }

    @Override
    protected void compute() {

      if (depth >= maxDepth || clock.instant().isAfter(deadline)) {
        return;
      }

      List<CrawlJob> nextJobs = new ArrayList<>();

      for (String url : urls) {

        if (shouldIgnoreUrl(url)) {
          continue;
        }

        if (!visited.add(url)) {
          continue;
        }

        PageParser.Result result = parserFactory.get(url).parse();

        mergeWordCounts(result.getWordCounts());

        if (!result.getLinks().isEmpty()) {
          nextJobs.add(
                  new CrawlJob(
                          result.getLinks(),
                          depth + 1,
                          deadline,
                          visited,
                          counts
                  )
          );
        }
      }

      invokeAll(nextJobs);
    }

    private boolean shouldIgnoreUrl(String url) {
      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          return true;
        }
      }
      return false;
    }

    private void mergeWordCounts(Map<String, Integer> pageCounts) {
      pageCounts.forEach((word, count) -> {
        for (Pattern pattern : ignoredWords) {
          if (pattern.matcher(word).matches()) {
            return;
          }
        }
        counts.merge(word, count, Integer::sum);
      });
    }
  }
}
