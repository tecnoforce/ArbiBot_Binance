package com.arbitrage.persistence;

import com.arbitrage.model.SequenceOrder;
import com.arbitrage.model.TradingSequence;
import com.arbitrage.util.Log;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.TimeZone;

public class SequenceFileManager {
    private static final String TAG = "SEQ_FILE";
    
    private final String basePath;
    private final String seqFileName;
    private final String eventsFileName;
    private final ObjectMapper objectMapper;
    private final ReentrantLock fileLock;

    public SequenceFileManager(String basePath) {
        this(basePath, "sequences.seq", "sequences.events");
    }

    public SequenceFileManager(String basePath, String seqFileName, String eventsFileName) {
        this.basePath = basePath;
        this.seqFileName = seqFileName;
        this.eventsFileName = eventsFileName;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.setTimeZone(TimeZone.getTimeZone("UTC"));
        this.fileLock = new ReentrantLock();
    }

    private Path getSeqFilePath() {
        return Paths.get(basePath, seqFileName);
    }

    private Path getEventsFilePath() {
        return Paths.get(basePath, eventsFileName);
    }

    public void insert(TradingSequence sequence) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            sequences.put(sequence.getSeqId(), sequence);
            saveAll(sequences);
            Log.debug(TAG, "Inserted sequence: " + sequence.getSeqId());
        } finally {
            fileLock.unlock();
        }
    }

    public void updateOrder(String seqId, int opIndice, SequenceOrder order) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            TradingSequence seq = sequences.get(seqId);
            if (seq == null) {
                Log.warn(TAG, "Sequence not found for update: " + seqId);
                return;
            }
            
            switch (opIndice) {
                case 1: seq.setOp1(order); break;
                case 2: seq.setOp2(order); break;
                case 3: seq.setOp3(order); break;
            }
            
            sequences.put(seqId, seq);
            saveAll(sequences);
            Log.debug(TAG, "Updated order " + opIndice + " in sequence: " + seqId);
        } finally {
            fileLock.unlock();
        }
    }

    public void delete(String seqId) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            if (sequences.remove(seqId) != null) {
                saveAll(sequences);
                Log.debug(TAG, "Deleted sequence: " + seqId);
            } else {
                Log.warn(TAG, "Sequence not found for delete: " + seqId);
            }
        } finally {
            fileLock.unlock();
        }
    }

    public Map<String, TradingSequence> loadAll() {
        Path seqPath = getSeqFilePath();
        if (!Files.exists(seqPath)) {
            Log.debug(TAG, "Sequence file does not exist, starting clean");
            return new HashMap<>();
        }

        try {
            String content = new String(Files.readAllBytes(seqPath));
            if (content.trim().isEmpty()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(content, 
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, TradingSequence.class));
        } catch (Exception e) {
            Log.warn(TAG, "Error reading sequence file: " + e.getMessage() + ", starting clean");
            return new HashMap<>();
        }
    }

    private void saveAll(Map<String, TradingSequence> sequences) throws IOException {
        Path seqPath = getSeqFilePath();
        Path tempPath = Paths.get(seqPath.toString() + ".tmp");
        
        try {
            String json = objectMapper.writeValueAsString(sequences);
            Files.write(tempPath, json.getBytes());
            
            if (Files.exists(seqPath)) {
                Files.delete(seqPath);
            }
            Files.move(tempPath, seqPath, StandardCopyOption.ATOMIC_MOVE);
            
            Log.debug(TAG, "Saved " + sequences.size() + " sequences atomically");
        } catch (Exception e) {
            if (Files.exists(tempPath)) {
                Files.delete(tempPath);
            }
            throw new IOException("Failed to save sequences: " + e.getMessage(), e);
        }
    }

    public void appendEvent(TradingSequence sequence) throws IOException {
        fileLock.lock();
        try {
            Path eventsPath = getEventsFilePath();
            String json = objectMapper.writeValueAsString(sequence);
            
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(Files.newOutputStream(eventsPath, 
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND), 
                        java.nio.charset.StandardCharsets.UTF_8))) {
                writer.write(json);
                writer.newLine();
            }
            
            Log.debug(TAG, "Appended event for sequence: " + sequence.getSeqId());
        } finally {
            fileLock.unlock();
        }
    }

    public TradingSequence findById(String seqId) {
        Map<String, TradingSequence> sequences = loadAll();
        return sequences.get(seqId);
    }

    public List<TradingSequence> listAll() {
        return new ArrayList<>(loadAll().values());
    }

    public List<TradingSequence> findOpen() {
        List<TradingSequence> open = new ArrayList<>();
        for (TradingSequence seq : loadAll().values()) {
            if (seq.getEstado() == com.arbitrage.model.EstadoSecuencia.ABIERTA) {
                open.add(seq);
            }
        }
        return open;
    }
}