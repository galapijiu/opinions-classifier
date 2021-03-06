package com.maximgalushka.classifier.twitter.classify.carrot;

import com.google.common.base.Optional;
import com.maximgalushka.classifier.storage.StorageService;
import com.maximgalushka.classifier.twitter.best.ClusterRepresentativeFinder;
import com.maximgalushka.classifier.twitter.classify.TextCleanup;
import com.maximgalushka.classifier.twitter.classify.Tools;
import com.maximgalushka.classifier.twitter.clusters.Clusters;
import com.maximgalushka.classifier.twitter.clusters.TweetsCluster;
import com.maximgalushka.classifier.twitter.model.Entities;
import com.maximgalushka.classifier.twitter.model.Tweet;
import org.apache.log4j.Logger;
import org.carrot2.clustering.lingo.LingoClusteringAlgorithm;
import org.carrot2.core.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.maximgalushka.classifier.twitter.classify.Tools.cleanFromStart;
import static com.maximgalushka.classifier.twitter.classify.Tools.slice;

/**
 * Clustering via Lingo algorithm from Carrot2
 */
@SuppressWarnings("UnusedDeclaration")
public class ClusteringTweetsListAlgorithm {

  public static final Logger log = Logger.getLogger(
    ClusteringTweetsListAlgorithm.class
  );

  private List<Cluster> previousClusters;
  private static final ClusteringTweetsListAlgorithm algorithm = new
    ClusteringTweetsListAlgorithm();

  private AtomicInteger ai = new AtomicInteger(0);
  private Controller controller;
  private StorageService storage;

  private ClusterRepresentativeFinder representativeFinder;

  private ClusteringTweetsListAlgorithm() {
    try {
      this.representativeFinder = new ClusterRepresentativeFinder();
    } catch (IOException | ParserConfigurationException | SAXException e) {
      e.printStackTrace();
    }
  }

  public void setController(Controller controller) {
    this.controller = controller;
  }

  public void setStorage(StorageService storage) {
    this.storage = storage;
  }

  public static ClusteringTweetsListAlgorithm getAlgorithm() {
    return algorithm;
  }

  /**
   * 1 thread processing because we need to keep previous batch. <br/>
   * <p>
   * TODO: result of this method should be map:<br/>
   * from cluster id: to cluster id<br/>
   * <p>
   * This method updates underlying model.<br/>
   * TODO: maybe this is not the best design. To think about it.<br/>
   */
  public synchronized void classify(List<Tweet> batch, Clusters model) throws
                                                                       IOException {
    List<Document> docs = readTweetsToDocs(batch);
    if (docs.isEmpty()) {
      return;
    }

    // helper map to extract any required tween metadata
    Map<String, Tweet> tweetsIndex = readTweetsToMap(batch);

    // Perform clustering by topic using the Lingo algorithm.
    final ProcessingResult byTopicClusters = controller.process(
      docs,
      null,
      LingoClusteringAlgorithm.class
    );
    final List<Cluster> clustersByTopic = byTopicClusters.getClusters();

    log.debug(
      String.format(
        "Found [%d] clusters BEFORE merge:\n%s",
        clustersByTopic.size(),
        printClusters(clustersByTopic)
      )
    );

    if (previousClusters != null) {
      Map<Integer, Optional<Integer>> fromTo = compareWithPrev(
        previousClusters,
        clustersByTopic
      );
      updateModel(model, clustersByTopic, fromTo, tweetsIndex);

      log.debug(
        String.format(
          "New [%d] clusters AFTER merge:\n%s",
          clustersByTopic.size(),
          printClusters(clustersByTopic)
        )
      );
    }
    previousClusters = clustersByTopic;
  }

  /**
   * @param clusters in-memory current clusters snapshot model - this is
   *                 singleton object stored on app level
   */
  private void updateModel(
    @Deprecated final Clusters clusters,
    List<Cluster> currentClusters,
    Map<Integer, Optional<Integer>> fromTo,
    Map<String, Tweet> tweetsIndex
  ) {
    // if cluster id is no longer in fromTo map - we should remove it
    // if cluster migrated to another - we should change it tracking id
    // if new cluster was created - we should just add it
    Map<Integer, Cluster> currentClustersIndex = new HashMap<>();
    for (Cluster c : currentClusters) currentClustersIndex.put(c.getId(), c);

    List<TweetsCluster> updated = new ArrayList<>();
    List<TweetsCluster> snapshot = Collections.unmodifiableList(
      clusters
        .getClusters()
    );
    for (TweetsCluster old : snapshot) {
      Optional<Integer> to = Optional.fromNullable(fromTo.get(old.getId()))
                                     .or(Optional.<Integer>absent());
      if (to.isPresent()) {
        TweetsCluster updatedCluster = old.clone();
        Cluster newCluster = currentClustersIndex.get(to.get());
        updatedCluster.setTrackingId(to.get());
        updatedCluster.setScore(
          old.getScore() + newCluster.getAllDocuments().size()
        );
        updated.add(updatedCluster);
      }
    }
    for (Cluster current : currentClusters) {
      TweetsCluster old = clusters.clusterById(current.getId());
      if (old == null) {
        Tweet representative = representativeFinder
          .findRepresentativeScoreBased(current.getAllDocuments(), tweetsIndex);
        Entities entities = representative.getEntities();
        String url = "";
        String image = "";
        if (entities != null) {
          url = entities.getUrls().isEmpty() ? "" : entities.getUrls()
                                                            .get(0)
                                                            .getUrl();
          image = entities.getMedia().isEmpty() ? "" : entities.getMedia()
                                                               .get(0)
                                                               .getUrl();
        }
        // create new
        updated.add(
          new TweetsCluster(
            current.getId(),
            current.getLabel(),
            representative.getText(),
            current.getAllDocuments().size(),
            url, image
          )
        );
      }
    }
    synchronized (this) {
      clusters.cleanClusters();
      List<TweetsCluster> finalList = filterAndFormatRepresetnations(updated);
      log.debug(String.format("Final clusters list: [%s]", finalList));
      clusters.addClusters(finalList);

      // storing
      storage.saveNewClustersGroup(clusters);
    }
  }

  /**
   * TODO: kind of messy method to filter out duplicate cluster
   * representations if any.
   *
   * @return list of clusters (domain model) without duplicated
   */
  private List<TweetsCluster>
  filterAndFormatRepresetnations(List<TweetsCluster> clusters) {

    List<TweetsCluster> result = new ArrayList<>();
    HashMap<String, TweetsCluster> messagesIndex = new HashMap<>();
    HashMap<String, TweetsCluster> urlsIndex = new HashMap<>();
    HashMap<String, TweetsCluster> imagesIndex = new HashMap<>();

    List<TweetsCluster> snapshot =
      Collections.unmodifiableList(clusters);

    for (TweetsCluster c : snapshot) {
      String message = c.getMessage();
      String url = c.getUrl();
      String image = c.getImage();
      String trimmedMessage = message.trim();
      Tuple<TweetsCluster, Double> similar = similarExisted(
        messagesIndex,
        trimmedMessage
      );
      if (similar.a != null) {
        log.warn(
          String.format(
            "Similar cluster centres found (similarity = %f): [%s], [%s]",
            similar.b,
            similar.a.getMessage(),
            trimmedMessage
          )
        );
        mergeClusters(c, similar.a);
      } else if (url != null && urlsIndex.containsKey(url)) {
        mergeClusters(c, urlsIndex.get(url));
      } else if (image != null && imagesIndex.containsKey(image)) {
        mergeClusters(c, imagesIndex.get(image));
      } else {
        messagesIndex.put(trimmedMessage, c);
        if (url != null) {
          urlsIndex.put(url, c);
        }
        if (image != null) {
          imagesIndex.put(image, c);
        }
      }
    }
    result.addAll(messagesIndex.values());

    for (TweetsCluster cluster : result) {
      cluster.setMessage(TextCleanup.reformatMessage(cluster.getMessage()));
    }

    return result;
  }

  /**
   * This is far from ideal.
   * Shuffles current clusters list and walks through first 50 to check for
   * similarity.
   * Only test content of clusters are checked.
   * O(50*n)
   *
   * @return true if similar message existed and is center of another cluster.
   */
  private Tuple<TweetsCluster, Double> similarExisted(
    Map<String, TweetsCluster> messagesIndex,
    String trimmedMessage
  ) {
    final Tuple<TweetsCluster, Double> EMPTY = new Tuple<>(null, 0D);
    if (trimmedMessage == null) {
      return EMPTY;
    }

    TweetsCluster ifExisted = messagesIndex.get(trimmedMessage);
    if (ifExisted != null) {
      return new Tuple<>(ifExisted, 1D);
    }

    int CAP = 50; // heuristics = number of clusters to avoid O(n^2) complexity
    double THRESHOLD = 0.75;
    int count = 0;
    List<String> messages = new ArrayList<>(messagesIndex.keySet());
    Collections.shuffle(messages);
    for (String existing : messages) {
      if (count++ > CAP) {
        break;
      }
      double similarity = Tools.jaccard(existing, trimmedMessage);
      if (similarity >= THRESHOLD) {
        return new Tuple<>(
          messagesIndex.get(existing),
          similarity
        );
      }
    }
    return EMPTY;
  }

  private void mergeClusters(
    TweetsCluster from,
    TweetsCluster to
  ) {
    log.warn(
      String.format(
        "Merging cluster [%d] to [%d]",
        from.getId(),
        to.getId()
      )
    );
    // recalculate sore
    to.setScore(to.getScore() + from.getScore());
  }

  /**
   * @return map which contain mapping between old cluster id which was
   * migrated to new cluster id (optional if old
   * cluster split)
   */
  private Map<Integer, Optional<Integer>> compareWithPrev(
    List<Cluster> prev,
    List<Cluster> current
  ) {
    Map<Integer, Optional<Integer>> fromTo = new HashMap<>(
      prev.size() * 2
    );

    // documentId -> Current cluster
    HashMap<String, Cluster> currMap = new HashMap<>();

    // reversed index on cluster id - to find cluster
    HashMap<Integer, Cluster> clusterIdsMap = new HashMap<>();

    for (Cluster c : current) {
      clusterIdsMap.put(c.getId(), c);
      for (Document d : c.getAllDocuments()) {
        currMap.put(d.getStringId(), c);
      }
    }

    // threshold to determine if cluster stayed the same.
    // if >= this threshold elements stayed in this cluster - it is
    // considered to stay the same
    double SAME = 0.6d;
    for (Cluster oldCluster : prev) {
      List<Document> docs = oldCluster.getAllDocuments();
      int totalMoved = 0;
      // next cluster id -> how many documents moved from this cluster to
      // next cluster on next step
      HashMap<Integer, Integer> howManyMovedAndWhere = new HashMap<>();
      for (Document d : docs) {
        String docId = d.getStringId();
        Cluster toCluster = currMap.get(docId);
        if (toCluster != null) {
          Integer toClusterId = toCluster.getId();
          Integer to = howManyMovedAndWhere.get(toClusterId);
          if (to == null) {
            howManyMovedAndWhere.put(toClusterId, 0);
            to = 0;
          }
          howManyMovedAndWhere.put(toClusterId, to + 1);
          totalMoved++;
        }
      }
      boolean split = true;
      for (Integer clusterId : howManyMovedAndWhere.keySet()) {
        int count = howManyMovedAndWhere.get(clusterId);
        double ratio = (double) count / totalMoved;
        Cluster currentCluster = clusterIdsMap.get(clusterId);

        if (ratio >= SAME) {
          //String message = currentCluster.getAllDocuments().get(0)
          // .getSummary();
          //model.updateCluster(p.getId(), clusterId, currentCluster.getLabel
          // (), message);
          fromTo.put(oldCluster.getId(), Optional.fromNullable(clusterId));
          log.debug(
            String.format(
              "Cluster [%s] moved to [%s]",
              oldCluster.getId(),
              clusterId
            )
          );
          split = false;
          break;
        }
        String currentLabel = currentCluster.getLabel();
        String prevLabel = oldCluster.getLabel();
        if (currentLabel.equals(prevLabel)) {
          //String message = currentCluster.getAllDocuments().get(0)
          // .getSummary();
          //model.updateCluster(oldCluster.getId(), clusterId, currentCluster
          // .getLabel(), message);
          fromTo.put(oldCluster.getId(), Optional.fromNullable(clusterId));
          log.debug(
            String.format(
              "Cluster [%s] moved to [%s]",
              oldCluster.getId(),
              clusterId
            )
          );
          split = false;
          break;
        }
      }
      if (split) {
        //model.removeCluster(oldCluster.getId());
        fromTo.put(oldCluster.getId(), Optional.<Integer>absent());
        log.debug(String.format("Cluster [%s] splitted", oldCluster.getId()));
      }
    }
    return fromTo;
  }

  /**
   * Reads tweets to document list ready for classification.<br/>
   * Filters out any duplicate tweets (with dame tweet id).
   */
  private List<Document> readTweetsToDocs(List<Tweet> tweets) throws
                                                              IOException {
    List<Document> docs = new ArrayList<>(tweets.size());
    Set<String> set = new HashSet<>(2 * docs.size());
    for (Tweet t : tweets) {
      String id = Long.toString(t.getId());
      if (!set.contains(id)) {
        docs.add(
          new Document(
            null,
            t.getText(),
            null,
            LanguageCode.ENGLISH,
            id
          )
        );
        set.add(id);
      } else {
        log.debug(String.format("Skip duplicate document: [%s]", id));
      }
    }
    return docs;
  }

  private Map<String, Tweet> readTweetsToMap(List<Tweet> tweets) throws
  IOException {
    Map<String, Tweet> map = new HashMap<>(2 * tweets.size());
    for (Tweet t : tweets) {
      map.put(Long.toString(t.getId()), t);
    }
    return map;
  }

  private boolean readDocsToDeque(
    BufferedReader fr,
    ArrayDeque<Document> docs,
    int N
  ) throws IOException {
    boolean stop = false;
    String line;
    int count = 0;
    while (count++ < N) {
      line = fr.readLine();
      if (line == null) {
        stop = true;
        break;
      }
      docs.addLast(
        new Document(
          null,
          line,
          null,
          LanguageCode.ENGLISH,
          Integer.toString(ai.incrementAndGet())
        )
      );
    }
    return stop;
  }

  private String printClusters(List<Cluster> clusters) {
    StringBuilder sb = new StringBuilder();
    for (Cluster c : clusters) {
      sb.append(c.getId()).append(": ").append(c.getLabel()).append("\n");
      for (Document d : c.getAllDocuments()) {
        sb.append("\t")
          .append(d.getStringId())
          .append("\t[")
          .append(d.getSummary())
          .append("]\n");
      }
    }
    return sb.toString();
  }


  public static void main(String[] args) throws IOException {
    log.debug("Start batch clustering");
    BufferedReader fr = new BufferedReader(new FileReader(args[0]));
    ClusteringTweetsListAlgorithm c = new ClusteringTweetsListAlgorithm();

    int D = 1000;
    int delta = 100;
    final ArrayDeque<Document> documents = new ArrayDeque<>(D);
    List<Cluster> prev = null;

    // read 1st batch
    boolean stop = c.readDocsToDeque(fr, documents, D);
    int batchId = 1;
    while (true) {
      log.debug(String.format("Start batch [%d]", batchId++));
      // Prepare next batch
      if (!stop) {
        stop = c.readDocsToDeque(fr, documents, delta);
      }

      ArrayList<Document> docs = slice(documents, D);
      if (docs.isEmpty()) {
        break;
      }

      // A controller to manage the processing pipeline.
      final Controller controller = ControllerFactory.createSimple();

      // Perform clustering by topic using the Lingo algorithm.
      final ProcessingResult byTopicClusters = controller.process(
        docs,
        null,
        LingoClusteringAlgorithm.class
      );
      final List<Cluster> clustersByTopic = byTopicClusters.getClusters();
      log.debug(
        String.format(
          "Found [%d] clusters BEFORE merge:\n%s", clustersByTopic.size(),
          c.printClusters(clustersByTopic)
        )
      );

      if (prev != null) {
        c.compareWithPrev(prev, clustersByTopic);
      }
      prev = clustersByTopic;

      if (stop) {
        break;
      }
      cleanFromStart(documents, delta);
    }
  }

  private static final class Tuple<A, B> {
    private A a;
    private B b;

    public Tuple(A a, B b) {
      this.a = a;
      this.b = b;
    }
  }

}

