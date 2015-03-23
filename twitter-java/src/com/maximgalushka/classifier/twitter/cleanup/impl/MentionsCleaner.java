package com.maximgalushka.classifier.twitter.cleanup.impl;

import com.maximgalushka.classifier.twitter.cleanup.Cleaner;

/**
 * @author Maxim Galushka
 */
public class MentionsCleaner implements Cleaner {

  @Override
  public String clean(String input) {
    return input.replaceAll("@\\S+", "");
  }
}
