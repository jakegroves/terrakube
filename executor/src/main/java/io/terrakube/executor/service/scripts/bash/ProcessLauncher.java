package io.terrakube.executor.service.scripts.bash;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ProcessLauncher {
    private Process process;
    private ProcessBuilder builder;
    private Consumer<String> outputListener, errorListener;
    private boolean inheritIO;
    private ExecutorService executor;

    public ProcessLauncher(ExecutorService executor, String... commands) {
        assert executor != null;
        this.executor = executor;
        this.process = null;
        this.builder = new ProcessBuilder(commands);
    }

    public void setOutputListener(Consumer<String> listener) {
        assert this.process == null;
        this.outputListener = listener;
    }

    public void setErrorListener(Consumer<String> listener) {
        assert this.process == null;
        this.errorListener = listener;
    }

    void setInheritIO(boolean inheritIO) {
        assert this.process == null;
        this.inheritIO = inheritIO;
    }

    public void setDirectory(File directory) {
        assert this.process == null;
        this.builder.directory(directory);
    }

    void appendCommands(String... commands) {
        Stream<String> filteredCommands = Arrays.stream(commands).filter(c -> c != null && c.length() > 0);
        this.builder.command().addAll(filteredCommands.collect(Collectors.toList()));
    }

    public void setEnvironmentVariable(String name, String value) {
        assert name != null && name.length() > 0;
        Map<String, String> env = this.builder.environment();
        value = (value != null ? env.put(name, value) : env.remove(name));
    }

    public void setOrAppendEnvironmentVariable(String name, String value, String delimiter) {
        assert name != null && name.length() > 0;
        if (value != null && value.length() > 0) {
            String current = System.getenv(name);
            String target = (current == null || current.length() == 0 ? value : String.join(delimiter, current, value));
            this.setEnvironmentVariable(name, target);
        }
    }

    public CompletableFuture<Integer> launch() {
        assert this.process == null;
        if (this.inheritIO) {
            this.builder.inheritIO();
        }
        try {
            this.process = this.builder.start();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        // Stream readers run on separate submitted tasks from the exit-code wait below - without
        // waiting for them here too, the returned future could resolve as soon as the process
        // exits, before the reader tasks have finished draining stdout/stderr to EOF (a race that
        // widens under thread-pool contention). A caller that reads its output/error buffers
        // immediately after this future completes could then see a truncated capture.
        List<CompletableFuture<Boolean>> streamReaders = new ArrayList<>();
        if (!this.inheritIO) {
            if (this.outputListener != null) {
                streamReaders.add(CompletableFuture.supplyAsync(
                        () -> this.readProcessStream(this.process.getInputStream(), this.outputListener), this.executor));
            }
            if (this.errorListener != null) {
                streamReaders.add(CompletableFuture.supplyAsync(
                        () -> this.readProcessStream(this.process.getErrorStream(), this.errorListener), this.executor));
            }
        }

        CompletableFuture<Integer> exitCodeFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return this.process.waitFor();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ex);
            }
        }, this.executor);

        if (streamReaders.isEmpty()) {
            return exitCodeFuture;
        }

        CompletableFuture<Void> allStreamsRead = CompletableFuture.allOf(streamReaders.toArray(new CompletableFuture[0]));
        return exitCodeFuture.thenCombine(allStreamsRead, (exitCode, ignored) -> exitCode);
    }

    private boolean readProcessStream(InputStream stream, Consumer<String> listener) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.accept(line);
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
