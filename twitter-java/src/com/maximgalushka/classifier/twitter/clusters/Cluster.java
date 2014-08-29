package com.maximgalushka.classifier.twitter.clusters;

import com.google.gson.annotations.SerializedName;
import com.maximgalushka.classifier.twitter.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * @since 8/29/2014.
 */
public class Cluster {

    @SerializedName("label")
    private String label;

    @SerializedName("tweets")
    private List<Long> tweets = new ArrayList<Long>();

    public Cluster() {
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<Long> getTweets() {
        return tweets;
    }

    public void setTweets(List<Long> tweets) {
        this.tweets = tweets;
    }

    public String toString() {
        return String.format("[%s]: [%s]", label, tweets);
    }

}
