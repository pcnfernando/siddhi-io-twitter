/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.extension.siddhi.io.twitter.source;

import org.apache.log4j.Logger;
import org.wso2.extension.siddhi.io.twitter.util.TwitterConstants;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.stream.input.source.SourceEventListener;
import twitter4j.FilterQuery;
import twitter4j.Query;
import twitter4j.QueryResult;
import twitter4j.StallWarning;
import twitter4j.Status;
import twitter4j.StatusDeletionNotice;
import twitter4j.StatusListener;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterObjectFactory;
import twitter4j.TwitterStream;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class handles consuming tweets and passing to the stream.
 * Since enums are inherently serializable and thread-safe, enum Singleton pattern is best way to
 * create Singleton in Java
 */
public enum TwitterConsumer {
    INSTANCE;

    private static final Logger log = Logger.getLogger(TwitterConsumer.class);
    private boolean paused;
    private ReentrantLock lock = new ReentrantLock();
    private Condition condition = lock.newCondition();
    long tweetId = -1;

    /**
     * This method handles consuming livestream tweets.
     *
     * @param twitterStream       - Twitter Stream instance
     * @param sourceEventListener - listens to events
     * @param filterQuery         - Specifies query for filter
     * @param paramSize           - No of parameters given for the query
     */
    public void consume(TwitterStream twitterStream, SourceEventListener sourceEventListener, FilterQuery filterQuery,
                        int paramSize) {
        twitterStream.addListener(new TwitterStatusListener(sourceEventListener));
        if (paramSize == TwitterConstants.MANDATORY_PARAM_SIZE) {
            twitterStream.sample();
        } else {
            twitterStream.filter(filterQuery);
        }
    }

    /**
     * This method handles consuming historical tweets within a week.
     *
     * @param twitter             - Twitter instance
     * @param query               - Specifies query
     * @param sourceEventListener - listens to events
     * @param siddhiAppContext    - Holder object for context information of siddhiapp
     * @param pollingInterval     - Specifies the interval to poll periodically
     */
    public void consume(Twitter twitter, Query query, SourceEventListener sourceEventListener, SiddhiAppContext
            siddhiAppContext, long pollingInterval) {
        ScheduledExecutorService scheduledExecutorService = siddhiAppContext.getScheduledExecutorService();
        scheduledExecutorService.scheduleAtFixedRate(new TwitterPoller(twitter, query, sourceEventListener),
                0, pollingInterval, TimeUnit.SECONDS);
    }

    /**
     * This class is for polling tweets continuously.
     */
    class TwitterPoller implements Runnable {
        Twitter twitter;
        Query query;
        QueryResult result;
        SourceEventListener sourceEventListener;


        /**
         * Handles polling in a different thread.
         *
         * @param twitter             - twitter instance
         * @param query               - specifies a query
         * @param sourceEventListener - listens events
         */
        TwitterPoller(Twitter twitter, Query query, SourceEventListener sourceEventListener) {
            this.twitter = twitter;
            this.query = query;
            this.sourceEventListener = sourceEventListener;
        }

        @Override
        public void run() {
            boolean isLatestId = true;
            do {
                try {
                    result = twitter.search(query);
                    List<Status> tweets = result.getTweets();
                    for (Status tweet : tweets) {
                        if (isLatestId) {
                            tweetId = tweet.getId();
                            isLatestId = false;
                        }
                        if (paused) { //spurious wakeup condition is deliberately traded off for performance
                            lock.lock();
                            try {
                                while (paused) {
                                    condition.await();
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            } finally {
                                lock.unlock();
                            }
                        }
                        sourceEventListener.onEvent(TwitterObjectFactory.getRawJSON(tweet), null);
                    }
                    if (result.nextQuery() != null) {
                        query = result.nextQuery();
                    } else {
                        query.setSinceId(tweetId);
                        query.setMaxId(-1);
                    }
                    checkRateLimit(result);
                } catch (TwitterException te) {
                    log.error("Failed to search tweets: " + te.getMessage());
                }
            } while (result.nextQuery() != null);
        }

        /**
         * Checks the number of the remaining requests within window and wait
         * until window will be reset.
         *
         * @param result - Results of the specified Query.
         */
        private void checkRateLimit(QueryResult result) {
            if (result.getRateLimitStatus().getRemaining() <= 0) {
                try {
                    Thread.sleep(result.getRateLimitStatus().getSecondsUntilReset());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Thread was interrupted during wait : " + e);
                }
            }
        }
    }

    /**
     * Listens to the public statuses.
     */
    class TwitterStatusListener implements StatusListener {
        private SourceEventListener sourceEventListener;

        TwitterStatusListener(SourceEventListener sourceEventListener) {
            this.sourceEventListener = sourceEventListener;
        }

        @Override
        public void onStatus(Status status) {
            if (paused) { //spurious wakeup condition is deliberately traded off for performance
                lock.lock();
                try {
                    while (paused) {
                        condition.await();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            }
            sourceEventListener.onEvent(TwitterObjectFactory.getRawJSON(status), null);
        }

        @Override
        public void onDeletionNotice(StatusDeletionNotice statusDeletionNotice) {
            log.debug("Got a status deletion notice id:" + statusDeletionNotice.getStatusId());
        }

        @Override
        public void onTrackLimitationNotice(int numberOfLimitedStatuses) {
            log.debug("Got track limitation notice: " + numberOfLimitedStatuses);

        }

        @Override
        public void onScrubGeo(long userId, long upToStatusId) {
            log.debug("Got scrub_geo event userId:" + userId + " upToStatusId:" + upToStatusId);

        }

        @Override
        public void onStallWarning(StallWarning warning) {
            log.debug("Got stall warning:" + warning);
        }

        @Override
        public void onException(Exception ex) {
            log.error("Twitter source threw an exception", ex);
        }
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
        try {
            lock.lock();
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }
}

