/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution.resourceGroups;

import com.facebook.presto.execution.QueryExecution;
import io.airlift.units.DataSize;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.units.DataSize.Unit.BYTE;
import static java.util.Objects.requireNonNull;

@ThreadSafe
public class ResourceGroup
        implements ConfigurableResourceGroup
{
    private final ResourceGroup root;
    private final Optional<ResourceGroup> parent;
    private final ResourceGroupId id;
    private final Executor executor;

    @GuardedBy("root")
    private final Map<String, ResourceGroup> subGroups = new HashMap<>();
    // Sub groups with queued queries, that have capacity to run them
    // That is, they must return true when internalStartNext() is called on them
    @GuardedBy("root")
    private final Queue<ResourceGroup> eligibleSubGroups = new LinkedHashQueue<>();
    @GuardedBy("root")
    private final Set<ResourceGroup> dirtySubGroups = new HashSet<>();
    @GuardedBy("root")
    private long softMemoryLimitBytes;
    @GuardedBy("root")
    private int maxRunningQueries;
    @GuardedBy("root")
    private int maxQueuedQueries;
    @GuardedBy("root")
    private int descendantRunningQueries;
    @GuardedBy("root")
    private int descendantQueuedQueries;
    @GuardedBy("root")
    private long cachedMemoryUsageBytes;
    @GuardedBy("root")
    private final Queue<QueryExecution> queuedQueries = new LinkedHashQueue<>();
    @GuardedBy("root")
    private final Set<QueryExecution> runningQueries = new HashSet<>();

    protected ResourceGroup(Optional<ResourceGroup> parent, String name, Executor executor)
    {
        this.parent = requireNonNull(parent, "parent is null");
        this.executor = requireNonNull(executor, "executor is null");
        requireNonNull(name, "name is null");
        if (parent.isPresent()) {
            id = new ResourceGroupId(parent.get().id, name);
            root = parent.get().root;
        }
        else {
            id = new ResourceGroupId(name);
            root = this;
        }
    }

    public ResourceGroupInfo getInfo()
    {
        synchronized (root) {
            List<ResourceGroupInfo> infos = subGroups.values().stream()
                    .map(ResourceGroup::getInfo)
                    .collect(Collectors.toList());
            return new ResourceGroupInfo(
                    id,
                    new DataSize(softMemoryLimitBytes, BYTE),
                    maxRunningQueries,
                    maxQueuedQueries,
                    runningQueries.size() + descendantRunningQueries,
                    queuedQueries.size() + descendantQueuedQueries,
                    new DataSize(cachedMemoryUsageBytes, BYTE),
                    infos);
        }
    }

    @Override
    public ResourceGroupId getId()
    {
        return id;
    }

    @Override
    public DataSize getSoftMemoryLimit()
    {
        synchronized (root) {
            return new DataSize(softMemoryLimitBytes, BYTE);
        }
    }

    @Override
    public void setSoftMemoryLimit(DataSize limit)
    {
        synchronized (root) {
            boolean oldCanRun = canRunMore();
            this.softMemoryLimitBytes = limit.toBytes();
            if (canRunMore() != oldCanRun) {
                updateEligiblility();
            }
        }
    }

    @Override
    public int getMaxRunningQueries()
    {
        synchronized (root) {
            return maxRunningQueries;
        }
    }

    @Override
    public void setMaxRunningQueries(int maxRunningQueries)
    {
        checkArgument(maxRunningQueries >= 0, "maxRunningQueries is negative");
        synchronized (root) {
            boolean oldCanRun = canRunMore();
            this.maxRunningQueries = maxRunningQueries;
            if (canRunMore() != oldCanRun) {
                updateEligiblility();
            }
        }
    }

    @Override
    public int getMaxQueuedQueries()
    {
        synchronized (root) {
            return maxQueuedQueries;
        }
    }

    @Override
    public void setMaxQueuedQueries(int maxQueuedQueries)
    {
        checkArgument(maxQueuedQueries >= 0, "maxQueuedQueries is negative");
        synchronized (root) {
            this.maxQueuedQueries = maxQueuedQueries;
        }
    }

    public ResourceGroup getOrCreateSubGroup(String name)
    {
        requireNonNull(name, "name is null");
        synchronized (root) {
            checkArgument(runningQueries.isEmpty() && queuedQueries.isEmpty(), "Cannot add sub group to %s while queries are running", id);
            if (subGroups.containsKey(name)) {
                return subGroups.get(name);
            }
            ResourceGroup subGroup = new ResourceGroup(Optional.of(this), name, executor);
            subGroups.put(name, subGroup);
            return subGroup;
        }
    }

    public boolean add(QueryExecution query)
    {
        synchronized (root) {
            checkState(subGroups.isEmpty(), "Cannot add queries to %s. It is not a leaf group.", id);
            // Check all ancestors for capacity
            ResourceGroup group = this;
            boolean canQueue = true;
            boolean canRun = true;
            while (true) {
                canQueue &= group.canQueueMore();
                canRun &= group.canRunMore();
                if (!group.parent.isPresent()) {
                    break;
                }
                group = group.parent.get();
            }
            if (!canQueue && !canRun) {
                return false;
            }
            if (canRun) {
                startInBackground(query);
            }
            else {
                enqueueQuery(query);
            }
            query.addStateChangeListener(state -> {
                if (state.isDone()) {
                    queryFinished(query);
                }
            });
            if (query.getState().isDone()) {
                queryFinished(query);
            }
            return true;
        }
    }

    private void enqueueQuery(QueryExecution query)
    {
        checkState(Thread.holdsLock(root), "Must hold lock to enqueue a query");
        synchronized (root) {
            queuedQueries.add(query);
            ResourceGroup group = this;
            while (group.parent.isPresent()) {
                group.parent.get().descendantQueuedQueries++;
                group = group.parent.get();
            }
            updateEligiblility();
        }
    }

    private void updateEligiblility()
    {
        checkState(Thread.holdsLock(root), "Must hold lock to update eligibility");
        synchronized (root) {
            if (!parent.isPresent()) {
                return;
            }
            if (isEligibleToStartNext()) {
                parent.get().eligibleSubGroups.add(this);
            }
            else {
                parent.get().eligibleSubGroups.remove(this);
            }
            parent.get().updateEligiblility();
        }
    }

    private void startInBackground(QueryExecution query)
    {
        checkState(Thread.holdsLock(root), "Must hold lock to start a query");
        synchronized (root) {
            runningQueries.add(query);
            ResourceGroup group = this;
            while (group.parent.isPresent()) {
                group.parent.get().descendantRunningQueries++;
                group.parent.get().dirtySubGroups.add(group);
                group = group.parent.get();
            }
            updateEligiblility();
            executor.execute(query::start);
        }
    }

    private void queryFinished(QueryExecution query)
    {
        synchronized (root) {
            if (!runningQueries.contains(query) && !queuedQueries.contains(query)) {
                // Query has already been cleaned up
                return;
            }
            if (runningQueries.contains(query)) {
                runningQueries.remove(query);
                ResourceGroup group = this;
                while (group.parent.isPresent()) {
                    group.parent.get().descendantRunningQueries--;
                    group = group.parent.get();
                }
            }
            else {
                queuedQueries.remove(query);
                ResourceGroup group = this;
                while (group.parent.isPresent()) {
                    group.parent.get().descendantQueuedQueries--;
                    group = group.parent.get();
                }
            }
            updateEligiblility();
        }
    }

    protected void internalRefreshStats()
    {
        checkState(Thread.holdsLock(root), "Must hold lock to refresh stats");
        synchronized (root) {
            if (subGroups.isEmpty()) {
                cachedMemoryUsageBytes = 0;
                for (QueryExecution query : runningQueries) {
                    cachedMemoryUsageBytes += query.getTotalMemoryReservation();
                }
            }
            else {
                for (Iterator<ResourceGroup> iterator = dirtySubGroups.iterator(); iterator.hasNext(); ) {
                    ResourceGroup subGroup = iterator.next();
                    cachedMemoryUsageBytes -= subGroup.cachedMemoryUsageBytes;
                    subGroup.internalRefreshStats();
                    cachedMemoryUsageBytes += subGroup.cachedMemoryUsageBytes;
                    if (!subGroup.isDirty()) {
                        iterator.remove();
                    }
                }
            }
        }
    }

    protected boolean internalStartNext()
    {
        checkState(Thread.holdsLock(root), "Must hold lock to find next query");
        synchronized (root) {
            if (!canRunMore()) {
                return false;
            }
            QueryExecution query = queuedQueries.poll();
            if (query != null) {
                startInBackground(query);
                return true;
            }

            // Remove even if the sub group still has queued queries, so that it goes to the back of the queue
            ResourceGroup subGroup = eligibleSubGroups.poll();
            if (subGroup == null) {
                return false;
            }
            boolean started = subGroup.internalStartNext();
            checkState(started, "Eligible sub group had no queries to run");
            descendantQueuedQueries--;
            // Don't call updateEligibility here, as we're in a recursive call, and don't want to repeatedly update our ancestors.
            if (subGroup.isEligibleToStartNext()) {
                eligibleSubGroups.add(subGroup);
            }
            return true;
        }
    }

    private boolean isDirty()
    {
        checkState(Thread.holdsLock(root), "Must hold lock");
        synchronized (root) {
            return runningQueries.size() + descendantRunningQueries > 0;
        }
    }

    private boolean isEligibleToStartNext()
    {
        checkState(Thread.holdsLock(root), "Must hold lock");
        synchronized (root) {
            if (!canRunMore()) {
                return false;
            }
            return !queuedQueries.isEmpty() || !eligibleSubGroups.isEmpty();
        }
    }

    private boolean canQueueMore()
    {
        checkState(Thread.holdsLock(root), "Must hold lock");
        synchronized (root) {
            return descendantQueuedQueries + queuedQueries.size() < maxQueuedQueries;
        }
    }

    private boolean canRunMore()
    {
        checkState(Thread.holdsLock(root), "Must hold lock");
        synchronized (root) {
            return runningQueries.size() + descendantRunningQueries < maxRunningQueries &&
                    cachedMemoryUsageBytes < softMemoryLimitBytes;
        }
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("id", id)
                .toString();
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResourceGroup)) {
            return false;
        }
        ResourceGroup that = (ResourceGroup) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(id);
    }

    @ThreadSafe
    public static final class RootResourceGroup
            extends ResourceGroup
    {
        public RootResourceGroup(String name, Executor executor)
        {
            super(Optional.empty(), name, executor);
        }

        public synchronized void processQueuedQueries()
        {
            internalRefreshStats();
            while (internalStartNext()) {
                // start all the queries we can
            }
        }
    }

    // A queue with constant time contains(E) and remove(E)
    private static final class LinkedHashQueue<E>
            implements Queue<E>
    {
        private final Set<E> delegate = new LinkedHashSet<>();

        @Override
        public boolean add(E element)
        {
            return delegate.add(element);
        }

        @Override
        public boolean offer(E element)
        {
            return delegate.add(element);
        }

        @Override
        public E remove()
        {
            E element = poll();
            if (element == null) {
                throw new NoSuchElementException();
            }
            return element;
        }

        @Override
        public boolean contains(Object element)
        {
            return delegate.contains(element);
        }

        @Override
        public boolean remove(Object element)
        {
            return delegate.remove(element);
        }

        @Override
        public boolean containsAll(Collection<?> elements)
        {
            return delegate.containsAll(elements);
        }

        @Override
        public boolean addAll(Collection<? extends E> elements)
        {
            return delegate.addAll(elements);
        }

        @Override
        public boolean removeAll(Collection<?> elements)
        {
            return delegate.removeAll(elements);
        }

        @Override
        public boolean retainAll(Collection<?> elements)
        {
            return delegate.retainAll(elements);
        }

        @Override
        public void clear()
        {
            delegate.clear();
        }

        @Override
        public E poll()
        {
            Iterator<E> iterator = iterator();
            if (!iterator.hasNext()) {
                return null;
            }
            E element = iterator.next();
            iterator.remove();
            return element;
        }

        @Override
        public E element()
        {
            E element = peek();
            if (element == null) {
                throw new NoSuchElementException();
            }
            return element;
        }

        @Override
        public E peek()
        {
            Iterator<E> iterator = iterator();
            if (!iterator.hasNext()) {
                return null;
            }
            return iterator.next();
        }

        @Override
        public Iterator<E> iterator()
        {
            return delegate.iterator();
        }

        @Override
        public Object[] toArray()
        {
            return delegate.toArray();
        }

        @Override
        public <T> T[] toArray(T[] array)
        {
            return delegate.toArray(array);
        }

        @Override
        public int size()
        {
            return delegate.size();
        }

        @Override
        public boolean isEmpty()
        {
            return delegate.isEmpty();
        }
    }
}
