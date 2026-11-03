package com.pnas.agent.watch;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * 递归目录监视器:注册根目录及其所有子目录,任何事件都触发防抖器。
 * 新建目录({@code ENTRY_CREATE} 且为目录)会被注册,单目录注册失败只记录并继续。
 * 仅依赖 JDK;{@link #close()} 关闭 WatchService 并结束监视线程。
 */
public final class Watcher implements AutoCloseable {

    private static final WatchEvent.Kind<?>[] KINDS = {
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE,
        StandardWatchEventKinds.ENTRY_MODIFY
    };

    private final Path root;
    private final Debouncer debouncer;
    private final WatchService service;
    private Thread thread;

    public Watcher(Path root, Debouncer debouncer) {
        if (root == null || debouncer == null) {
            throw new IllegalArgumentException("root 与 debouncer 不能为空");
        }
        this.root = root;
        this.debouncer = debouncer;
        try {
            this.service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new IllegalStateException("创建 WatchService 失败: " + e.getMessage(), e);
        }
    }

    /** 注册根目录并启动后台监视线程;根目录注册失败时记录错误但不抛出。 */
    public void start() {
        if (thread != null) {
            throw new IllegalStateException("Watcher 已启动");
        }
        if (!register(root)) {
            System.err.println("监视注册失败,跳过: " + root);
            return;
        }
        thread = new Thread(this::loop, "pnas-watch-" + root.getFileName());
        thread.setDaemon(true);
        thread.start();
    }

    /** 递归注册目录;根目录失败返回 false,子目录失败只记录。 */
    private boolean register(Path dir) {
        try {
            dir.register(service, KINDS);
        } catch (IOException e) {
            System.err.println("监视注册失败 " + dir + ": " + e.getMessage());
            return false;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    register(child);
                }
            }
        } catch (IOException e) {
            System.err.println("遍历子目录失败 " + dir + ": " + e.getMessage());
        }
        return true;
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            Path dir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    Path child = dir.resolve((Path) event.context());
                    if (Files.isDirectory(child)) {
                        register(child);
                    }
                }
                debouncer.trigger();
            }
            key.reset();
        }
    }

    @Override
    public void close() {
        try {
            service.close();
        } catch (IOException e) {
            System.err.println("关闭监视服务失败: " + e.getMessage());
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
