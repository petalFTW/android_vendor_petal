/* Copyright (C) 2026 petalOS; SPDX-License-Identifier: Apache-2.0 */
package org.petalos.power;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Serializes current-state reconciliation and merges requests waiting for the worker. */
final class PolicyUpdateQueue {
    private final Executor mExecutor;
    private final Consumer<RuntimeException> mOnFailure;
    private final ArrayList<Runnable> mCompletions = new ArrayList<>();
    private Runnable mLatestWork;
    private boolean mRunning;

    PolicyUpdateQueue(Executor executor, Consumer<RuntimeException> onFailure) {
        mExecutor = executor;
        mOnFailure = onFailure;
    }

    void submit(Runnable work, Runnable completion) {
        synchronized (this) {
            mLatestWork = work;
            if (completion != null) mCompletions.add(completion);
            if (mRunning) return;
            mRunning = true;
        }
        mExecutor.execute(this::drain);
    }

    private void drain() {
        while (true) {
            final Runnable work;
            final ArrayList<Runnable> completions;
            synchronized (this) {
                if (mLatestWork == null) {
                    mRunning = false;
                    return;
                }
                work = mLatestWork;
                mLatestWork = null;
                completions = new ArrayList<>(mCompletions);
                mCompletions.clear();
            }
            try {
                work.run();
            } catch (RuntimeException failure) {
                mOnFailure.accept(failure);
            } finally {
                for (Runnable completion : completions) {
                    try {
                        completion.run();
                    } catch (RuntimeException failure) {
                        mOnFailure.accept(failure);
                    }
                }
            }
            // Requests received during apply get a fresh pass; none can be lost on exit.
        }
    }
}
