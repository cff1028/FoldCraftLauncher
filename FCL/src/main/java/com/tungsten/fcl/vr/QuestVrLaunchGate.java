package com.tungsten.fcl.vr;

import java.util.concurrent.TimeUnit;

public final class QuestVrLaunchGate {
    public enum State { LOADING, FLAT, SWITCHING, READY, FAILED }
    private State state = State.LOADING;
    private boolean immersiveResumed;

    public synchronized State state() {
        return state;
    }

    public boolean awaitReady(Runnable request, long timeoutMillis) throws InterruptedException {
        boolean first;
        synchronized (this) {
            first = state == State.LOADING || state == State.FLAT
                    || (state == State.READY && !immersiveResumed);
            if (first) state = State.SWITCHING;
        }
        if (first) {
            try {
                request.run();
            } catch (RuntimeException failure) {
                fail();
                throw failure;
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (this) {
            while (state == State.SWITCHING) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    fail();
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException interrupted) {
                    fail();
                    throw interrupted;
                }
            }
            return state == State.READY && immersiveResumed;
        }
    }

    public synchronized boolean ready() {
        if (state != State.SWITCHING && state != State.READY) return false;
        state = State.READY;
        immersiveResumed = true;
        notifyAll();
        return true;
    }

    public synchronized void paused() {
        immersiveResumed = false;
    }

    public synchronized boolean wantsImmersive() {
        return state == State.SWITCHING || state == State.READY;
    }

    public synchronized boolean firstGameFrame() {
        if (state != State.LOADING) return false;
        state = State.FLAT;
        return true;
    }

    public synchronized boolean flat() {
        if (state == State.FAILED) return false;
        boolean changed = state != State.FLAT;
        state = State.FLAT;
        immersiveResumed = false;
        notifyAll();
        return changed;
    }

    public synchronized void fail() {
        state = State.FAILED;
        notifyAll();
    }
}
