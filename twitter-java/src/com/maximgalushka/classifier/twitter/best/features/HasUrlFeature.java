package com.maximgalushka.classifier.twitter.best.features;

import com.maximgalushka.classifier.twitter.best.Feature;
import com.maximgalushka.classifier.twitter.model.Tweet;

/**
 * TODO:
 */
public class HasUrlFeature implements Feature<Boolean, Tweet> {

  @Override
  public Boolean extract(Tweet tweet) {
    return !(tweet.getEntities() == null ||
      tweet.getEntities().getUrls() == null ||
      tweet.getEntities().getUrls().isEmpty());
  }

  @Override
  public double metric(Boolean feature) {
    return feature ? 0 : 5;
  }

  @Override
  public boolean exclude(Boolean feature) {
    return false;
  }
}
