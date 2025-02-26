/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.indexer;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.indices.InvalidAliasNameException;
import org.graylog2.configuration.ElasticsearchConfiguration;
import org.graylog2.indexer.indices.Indices;
import org.graylog2.indexer.ranges.CreateNewSingleIndexRangeJob;
import org.graylog2.indexer.ranges.RebuildIndexRangesJob;
import org.graylog2.shared.system.activities.Activity;
import org.graylog2.shared.system.activities.ActivityWriter;
import org.graylog2.system.jobs.SystemJob;
import org.graylog2.system.jobs.SystemJobConcurrencyException;
import org.graylog2.system.jobs.SystemJobManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Format of actual indexes behind the Deflector:
 * [configured_prefix]_1
 * [configured_prefix]_2
 * [configured_prefix]_3
 * ...
 */
public class Deflector { // extends Ablenkblech
    private static final Logger LOG = LoggerFactory.getLogger(Deflector.class);

    public static final String DEFLECTOR_SUFFIX = "deflector";
    public static final String SEPARATOR = "_";

    private final SystemJobManager systemJobManager;
    private final ActivityWriter activityWriter;
    private final CreateNewSingleIndexRangeJob.Factory createNewSingleIndexRangeJobFactory;
    private final String indexPrefix;
    private final String deflectorName;
    private final Indices indices;
    private final SetIndexReadOnlyJob.Factory indexReadOnlyJobFactory;

    @Inject
    public Deflector(final SystemJobManager systemJobManager,
                     final ElasticsearchConfiguration configuration,
                     final ActivityWriter activityWriter,
                     final SetIndexReadOnlyJob.Factory indexReadOnlyJobFactory,
                     final CreateNewSingleIndexRangeJob.Factory createNewSingleIndexRangeJobFactory,
                     final Indices indices) {
        this.indexPrefix = configuration.getIndexPrefix();

        this.systemJobManager = systemJobManager;
        this.activityWriter = activityWriter;
        this.indexReadOnlyJobFactory = indexReadOnlyJobFactory;
        this.createNewSingleIndexRangeJobFactory = createNewSingleIndexRangeJobFactory;

        this.deflectorName = buildName(configuration.getIndexPrefix());
        this.indices = indices;
    }

    public boolean isUp() {
        return indices.aliasExists(getName());
    }

    public void setUp() {
        // Check if there already is an deflector index pointing somewhere.
        if (isUp()) {
            LOG.info("Found deflector alias <{}>. Using it.", getName());
        } else {
            LOG.info("Did not find an deflector alias. Setting one up now.");

            // Do we have a target index to point to?
            try {
                final String currentTarget = getNewestTargetName();
                LOG.info("Pointing to already existing index target <{}>", currentTarget);

                pointTo(currentTarget);
            } catch (NoTargetIndexException ex) {
                final String msg = "There is no index target to point to. Creating one now.";
                LOG.info(msg);
                activityWriter.write(new Activity(msg, Deflector.class));

                cycle(); // No index, so automatically cycling to a new one.
            } catch (InvalidAliasNameException e) {
                LOG.error("Seems like there already is an index called [{}]", getName());
            }
        }
    }

    public void cycle() {
        LOG.info("Cycling deflector to next index now.");
        int oldTargetNumber;

        try {
            oldTargetNumber = getNewestTargetNumber();
        } catch (NoTargetIndexException ex) {
            oldTargetNumber = -1;
        }
        final int newTargetNumber = oldTargetNumber + 1;

        final String newTarget = buildIndexName(indexPrefix, newTargetNumber);
        final String oldTarget = buildIndexName(indexPrefix, oldTargetNumber);

        if (oldTargetNumber == -1) {
            LOG.info("Cycling from <none> to <{}>", newTarget);
        } else {
            LOG.info("Cycling from <{}> to <{}>", oldTarget, newTarget);
        }

        // Create new index.
        LOG.info("Creating index target <{}>...", newTarget);
        if (!indices.create(newTarget)) {
            LOG.error("Could not properly create new target <{}>", newTarget);
        }

        LOG.info("Waiting for index allocation of <{}>", newTarget);
        ClusterHealthStatus healthStatus = indices.waitForRecovery(newTarget);
        LOG.debug("Health status of index <{}>: {}", newTarget, healthStatus);

        LOG.info("Done!");

        // Point deflector to new index.
        LOG.info("Pointing deflector to new target index....");

        final Activity activity = new Activity(Deflector.class);
        if (oldTargetNumber == -1) {
            // Only pointing, not cycling.
            pointTo(newTarget);
            activity.setMessage("Cycled deflector from <none> to <" + newTarget + ">");
        } else {
            // Re-pointing from existing old index to the new one.
            pointTo(newTarget, oldTarget);
            addSingleIndexRanges(oldTarget);

            // perform these steps after a delay, so we don't race with indexing into the alias
            // it can happen that an index request still writes to the old deflector target, while we cycled it above.
            // setting the index to readOnly would result in ClusterBlockExceptions in the indexing request.
            // waiting 30 seconds to perform the background task should completely get rid of these errors.
            final SystemJob makeReadOnlyJob = indexReadOnlyJobFactory.create(oldTarget);
            try {
                systemJobManager.submitWithDelay(makeReadOnlyJob, 30, TimeUnit.SECONDS);
            } catch (SystemJobConcurrencyException e) {
                LOG.error("Cannot set index <" + oldTarget + "> to read only. It won't be optimized.", e);
            }
            activity.setMessage("Cycled deflector from <" + oldTarget + "> to <" + newTarget + ">");
        }

        addSingleIndexRanges(newTarget);

        LOG.info("Done!");

        activityWriter.write(activity);
    }

    public int getNewestTargetNumber() throws NoTargetIndexException {
        final Map<String, IndexStats> indices = this.indices.getAll();
        if (indices.isEmpty()) {
            throw new NoTargetIndexException();
        }

        final List<Integer> indexNumbers = Lists.newArrayListWithExpectedSize(indices.size());
        for (String indexName : indices.keySet()) {
            if (!isGraylog2Index(indexName)) {
                continue;
            }

            try {
                indexNumbers.add(extractIndexNumber(indexName));
            } catch (NumberFormatException ex) {
                LOG.debug("Couldn't extract index number from index name " + indexName, ex);
            }
        }

        if (indexNumbers.isEmpty()) {
            throw new NoTargetIndexException();
        }

        return Collections.max(indexNumbers);
    }

    public String[] getAllDeflectorIndexNames() {
        final Map<String, IndexStats> indices = this.indices.getAll();
        final List<String> result = Lists.newArrayListWithExpectedSize(indices.size());
        for (String indexName : indices.keySet()) {
            if (isGraylog2Index(indexName)) {
                result.add(indexName);
            }
        }

        return result.toArray(new String[result.size()]);
    }

    public Map<String, IndexStats> getAllDeflectorIndices() {
        final ImmutableMap.Builder<String, IndexStats> result = ImmutableMap.builder();
        for (Map.Entry<String, IndexStats> e : indices.getAll().entrySet()) {
            final String name = e.getKey();

            if (isGraylog2Index(name)) {
                result.put(name, e.getValue());
            }
        }
        return result.build();
    }

    public String getNewestTargetName() throws NoTargetIndexException {
        return buildIndexName(indexPrefix, getNewestTargetNumber());
    }

    public static String buildIndexName(final String prefix, final int number) {
        return prefix + SEPARATOR + number;
    }

    public static String buildName(final String prefix) {
        return prefix + SEPARATOR + DEFLECTOR_SUFFIX;
    }

    public static int extractIndexNumber(final String indexName) throws NumberFormatException {
        final String[] parts = indexName.split(SEPARATOR);

        try {
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            LOG.debug("Could not extract index number from index <" + indexName + ">.", e);
            throw new NumberFormatException();
        }
    }

    public void pointTo(final String newIndex, final String oldIndex) {
        indices.cycleAlias(getName(), newIndex, oldIndex);
    }

    public void pointTo(final String newIndex) {
        indices.cycleAlias(getName(), newIndex);
    }

    private void addSingleIndexRanges(String indexName) {
        try {
            systemJobManager.submit(createNewSingleIndexRangeJobFactory.create(this, indexName));
        } catch (SystemJobConcurrencyException e) {
            final String msg = "Could not calculate index ranges for index " + indexName + " after cycling deflector: Maximum concurrency of job is reached.";
            activityWriter.write(new Activity(msg, Deflector.class));
            LOG.error(msg, e);
        }
    }

    @Nullable
    public String getCurrentActualTargetIndex() {
        return indices.aliasTarget(getName());
    }

    public String getName() {
        return deflectorName;
    }

    public String getDeflectorWildcard() {
        return indexPrefix + SEPARATOR + "*";
    }

    public boolean isDeflectorAlias(final String indexName) {
        return getName().equals(indexName);
    }

    public boolean isGraylog2Index(final String indexName) {
        return !isDeflectorAlias(indexName) && indexName.startsWith(indexPrefix + SEPARATOR);
    }

}