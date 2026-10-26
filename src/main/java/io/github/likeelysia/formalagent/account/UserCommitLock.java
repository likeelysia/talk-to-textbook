package io.github.likeelysia.formalagent.account;

import java.util.concurrent.locks.ReentrantLock;

/** 单 owner 的提交协调：会话删除/换域与后台记忆+冲突提交共用，锁只覆盖短提交区。 */
public final class UserCommitLock {
    private final ReentrantLock lock = new ReentrantLock();

    public void lock() { lock.lock(); }
    public void unlock() { lock.unlock(); }
}
