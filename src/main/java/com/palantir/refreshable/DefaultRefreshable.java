/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 *
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

package com.palantir.refreshable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import java.lang.ref.Cleaner;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a {@code T} that may be updated over time.
 *
 * <p>Internally, it differentiates between 'mapping' (to produce derived Refreshables) and 'subscribing' (for the
 * purposes of side-effects), to ensure that chains of unused derived Refreshables can be garbage collected, but any
 * undisposed side-effect subscribers keep all their ancestors alive.
 */
final class DefaultRefreshable<T> implements SettableRefreshable<T> {
    private static final Logger log = LoggerFactory.getLogger(DefaultRefreshable.class);
    private static final Cleaner REFRESHABLE_CLEANER = Cleaner.create();

    private static final int WARN_THRESHOLD = 1000;

    /** Subscribers are updated in deterministic order based on registration order. This prevents a class
     * of bugs where a listener on a refreshable uses a refreshable mapped from itself, and guarantees the child
     * mappings will be up-to-date before the listener is executed, as long as the input mapping occurred before
     * the subscription. While we strongly recommend against this kind of dependency, it's complicated to detect
     * in large projects with layers of indirection.
     * <p>
     * Consider the following:
     * <pre>{@code
     * SettableRefreshable<Integer> instance = Refreshable.create(1);
     * Refreshable<Integer> refreshablePlusOne = instance.map(i -> i + 1);
     * instance.subscribe(i -> {
     *     // Code invoked here should reliably be able to assume that refreshablePlusOne.current()
     *     // is i + 1. Without enforcing order, that would not be the case and vary by jvm initialization!
     * });
     * }</pre>
     */
    private final Set<Consumer<? super T>> orderedSubscribers = Collections.synchronizedSet(new LinkedHashSet<>());

    private final RootSubscriberTracker rootSubscriberTracker;
    private volatile T current;
    private final Lock writeLock;
    private final Lock readLock;

    /**
     * Ensures that in a long chain of mapped refreshables, intermediate ones can't be garbage collected if derived
     * refreshables are still in use. Empty for root refreshables only.
     */
    @SuppressWarnings("unused")
    private final Optional<?> strongParentReference;

    DefaultRefreshable(T current) {
        this(current, Optional.empty(), new RootSubscriberTracker());
    }

    private DefaultRefreshable(T current, Optional<?> strongParentReference, RootSubscriberTracker tracker) {
        this.current = current;
        this.strongParentReference = strongParentReference;
        this.rootSubscriberTracker = tracker;
        ReadWriteLock lock = new ReentrantReadWriteLock();
        writeLock = lock.writeLock();
        readLock = lock.readLock();
    }

    /** Updates the current value and sends the specified value to all subscribers. */
    @Override
    public void update(T value) {
        writeLock.lock();
        try {
            if (!Objects.equals(current, value)) {
                current = value;

                // iterating over a copy allows subscriptions to be disposed within an update without causing
                // ConcurrentModificationExceptions.
                ImmutableList.copyOf(orderedSubscribers).forEach(subscriber -> subscriber.accept(value));
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public T current() {
        return current;
    }

    @Override
    public Disposable subscribe(Consumer<? super T> throwingSubscriber) {
        readLock.lock();
        try {
            SideEffectSubscriber<? super T> trackedSubscriber =
                    rootSubscriberTracker.newSideEffectSubscriber(throwingSubscriber, this);
            return subscribeToSelf(trackedSubscriber, rootSubscriberTracker::deleteReferenceTo);
        } finally {
            readLock.unlock();
        }
    }

    private void preSubscribeLogging() {
        if (log.isWarnEnabled()) {
            int subscribers = orderedSubscribers.size() + 1;
            if (subscribers > WARN_THRESHOLD) {
                log.warn(
                        "Refreshable {} has an excessive number of subscribers: {} and is likely leaking memory. "
                                + "The current warning threshold is {}.",
                        SafeArg.of("refreshableIdentifier", System.identityHashCode(this)),
                        SafeArg.of("numSubscribers", subscribers),
                        SafeArg.of("warningThreshold", WARN_THRESHOLD),
                        new SafeRuntimeException("location"));
            } else if (log.isDebugEnabled()) {
                log.debug(
                        "Added a subscription to refreshable {} resulting in {} subscriptions",
                        SafeArg.of("refreshableIdentifier", System.identityHashCode(this)),
                        SafeArg.of("numSubscribers", subscribers));
            }
        }
    }

    @Override
    public <R> Refreshable<R> map(Function<? super T, R> function) {
        readLock.lock();
        try {
            R initialChildValue = function.apply(current);
            SettableRefreshable<R> child = createSettableChild(initialChildValue);

            // Contract for Cleaner#register states that the Runnable should never reference the object being registered
            Disposable disposable = registerMapSubscriber(function, new WeakReference<>(child));

            REFRESHABLE_CLEANER.register(child, disposable::dispose);

            return child;
        } finally {
            readLock.unlock();
        }
    }

    private <R> SettableRefreshable<R> createSettableChild(R initialChildValue) {
        Optional<?> parentReference = Optional.of(this);
        return new DefaultRefreshable<>(initialChildValue, parentReference, rootSubscriberTracker);
    }

    private <R> Disposable registerMapSubscriber(
            Function<? super T, R> function, WeakReference<SettableRefreshable<R>> childRef) {
        MappingSubscriber<? super T, R> mapSubscriber = new MappingSubscriber<>(function, childRef);
        return subscribeToSelf(mapSubscriber, _ignored -> {});
    }

    @GuardedBy("readLock")
    private <C extends NoThrowSubscriber<? super T>> Disposable subscribeToSelf(
            C subscriber, DisposeListener<C> disposeListener) {
        preSubscribeLogging();
        // The subscriber promises not to throw, so there isn't a way we could have a hanging
        orderedSubscribers.add(subscriber);
        subscriber.accept(current);
        return new NotifyingDisposable<>(
                subscriberToRemove -> {
                    orderedSubscribers.remove(subscriberToRemove);
                    disposeListener.disposed(subscriberToRemove);
                },
                subscriber);
    }

    interface DisposeListener<C extends Consumer<?>> {
        void disposed(C consumer);
    }

    // Marker interface for documentation: if you implement this, you must promise not to throw!
    interface NoThrowSubscriber<T> extends Consumer<T> {}

    /**
     * A {@link Disposable} object shouldn't prevent a {@link Refreshable} from being garbage collected if the
     * refreshable is no longer referenced. In that case it's impossible that the consumer could be called with an
     * update because the root refreshable has been garbage collected.
     * <pre>{@code
     * SettableRefreshable root = Refreshable.create(initial);
     * Disposable subscription = root.subscribe(System.out::println);
     * root = null;
     * // root should be garbage collected, nothing can update the SettableRefreshable value.
     * }</pre>
     */
    private static final class NotifyingDisposable<C extends Consumer<?>> implements Disposable {
        private final DisposeListener<C> listener;
        private final C subscriber;

        NotifyingDisposable(DisposeListener<C> listener, C subscriber) {
            this.listener = listener;
            this.subscriber = subscriber;
        }

        @Override
        public void dispose() {
            listener.disposed(subscriber);
        }
    }

    /**
     * Purely for GC purposes - this class holds a reference to its parent refreshable. Instances of this class are
     * themselves tracked by the {@link RootSubscriberTracker}.
     */
    private static class SideEffectSubscriber<T> implements NoThrowSubscriber<T> {
        private final Consumer<T> unsafeSubscriber;

        @SuppressWarnings("unused")
        private final Refreshable<?> strongParentReference;

        SideEffectSubscriber(Consumer<T> unsafeSubscriber, Refreshable<?> strongParentReference) {
            this.unsafeSubscriber = unsafeSubscriber;
            this.strongParentReference = strongParentReference;
        }

        @Override
        public void accept(T value) {
            try {
                unsafeSubscriber.accept(value);
            } catch (RuntimeException e) {
                log.error("Failed to update refreshable subscriber with value {}", UnsafeArg.of("value", value), e);
            }
        }
    }

    /** Updates the child refreshable, while still allowing that child refreshable to be garbage collected. */
    private static final class MappingSubscriber<T, R> implements NoThrowSubscriber<T> {
        private final WeakReference<SettableRefreshable<R>> childRef;
        private final Function<T, R> function;

        private MappingSubscriber(Function<T, R> function, WeakReference<SettableRefreshable<R>> childRef) {
            this.childRef = childRef;
            this.function = function;
        }

        @Override
        public void accept(T value) {
            SettableRefreshable<R> child = childRef.get();
            if (child != null) {
                try {
                    child.update(function.apply(value));
                } catch (RuntimeException e) {
                    log.error("Failed to update refreshable subscriber with value {}", UnsafeArg.of("value", value), e);
                }
            }
        }
    }

    /**
     * Stores references to all {@link SideEffectSubscriber} instances, so that they won't be garbage collected until
     * the whole refreshable tree is collected. Otherwise, derived Refreshables may be GC'd because their only inbound
     * references could be WeakReferences.
     */
    private static final class RootSubscriberTracker {
        private final Set<SideEffectSubscriber<?>> liveSubscribers = ConcurrentHashMap.newKeySet();

        <T> SideEffectSubscriber<? super T> newSideEffectSubscriber(
                Consumer<? super T> unsafeSubscriber, Refreshable<T> parent) {
            SideEffectSubscriber<? super T> freshSubscriber = new SideEffectSubscriber<>(unsafeSubscriber, parent);
            liveSubscribers.add(freshSubscriber);
            return freshSubscriber;
        }

        void deleteReferenceTo(SideEffectSubscriber<?> subscriber) {
            liveSubscribers.remove(subscriber);
        }
    }

    @VisibleForTesting
    int subscribers() {
        return orderedSubscribers.size();
    }
}
