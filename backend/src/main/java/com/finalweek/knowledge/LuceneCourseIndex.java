package com.finalweek.knowledge;

import com.finalweek.common.config.FinalWeekProperties;
import com.finalweek.material.CourseSegment;
import com.finalweek.task.RetryableTaskException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
public class LuceneCourseIndex {
    private static final String CONTENT = "content";
    private final Path root;
    private final RedissonClient redisson;
    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private final ConcurrentHashMap<UUID, ReadWriteLock> localLocks = new ConcurrentHashMap<>();

    public LuceneCourseIndex(FinalWeekProperties properties, RedissonClient redisson) {
        this.root = properties.retrieval().luceneIndexPath().toAbsolutePath().normalize();
        this.redisson = redisson;
        try { Files.createDirectories(root); }
        catch (IOException exception) { throw new IllegalStateException("无法创建 Lucene 索引目录", exception); }
    }

    public void incrementalUpsert(UUID courseId, UUID materialId, List<CourseSegment> segments) {
        withWriteLock(courseId, () -> {
            var path = coursePath(courseId); Files.createDirectories(path);
            try (var directory = FSDirectory.open(path);
                 var writer = new IndexWriter(directory, writerConfig())) {
                writer.deleteDocuments(new Term("materialId", materialId.toString()));
                for (var segment : segments) writer.addDocument(document(segment));
                writer.commit();
            }
        });
    }

    public List<UUID> search(UUID userId, UUID courseId, String query, int limit) {
        var lock = localLock(courseId).readLock(); lock.lock();
        try {
            var path = coursePath(courseId);
            if (!Files.isDirectory(path)) return List.of();
            try (var directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) return List.of();
                try (var reader = DirectoryReader.open(directory)) {
                    var terms = analyze(query);
                    if (terms.isEmpty()) return List.of();
                    var lexical = new BooleanQuery.Builder();
                    terms.forEach(term -> lexical.add(new TermQuery(new Term(CONTENT, term)), BooleanClause.Occur.SHOULD));
                    lexical.setMinimumNumberShouldMatch(1);
                    var filtered = new BooleanQuery.Builder()
                            .add(lexical.build(), BooleanClause.Occur.MUST)
                            .add(new TermQuery(new Term("userId", userId.toString())), BooleanClause.Occur.FILTER)
                            .add(new TermQuery(new Term("courseId", courseId.toString())), BooleanClause.Occur.FILTER)
                            .build();
                    var searcher = new IndexSearcher(reader); searcher.setSimilarity(new BM25Similarity());
                    var top = searcher.search(filtered, limit); var result = new ArrayList<UUID>();
                    for (var hit : top.scoreDocs) result.add(UUID.fromString(searcher.storedFields().document(hit.doc).get("segmentId")));
                    return List.copyOf(result);
                }
            }
        } catch (Exception exception) {
            throw new RetryableTaskException("LUCENE_SEARCH_FAILED", "Lucene 检索暂时不可用");
        } finally { lock.unlock(); }
    }

    public void deleteMaterial(UUID courseId, UUID materialId) {
        withWriteLock(courseId, () -> {
            var path = coursePath(courseId);
            if (!Files.isDirectory(path)) return;
            try (var directory = FSDirectory.open(path);
                 var writer = new IndexWriter(directory, writerConfig())) {
                writer.deleteDocuments(new Term("materialId", materialId.toString())); writer.commit();
            }
        });
    }

    public void deleteCourse(UUID courseId) {
        withWriteLock(courseId, () -> deleteTree(coursePath(courseId)));
    }

    public void rebuild(UUID courseId, List<CourseSegment> segments) {
        withWriteLock(courseId, () -> {
            var target = coursePath(courseId);
            var temporary = root.resolve(courseId + ".rebuild-" + UUID.randomUUID()).normalize();
            var backup = root.resolve(courseId + ".backup-" + UUID.randomUUID()).normalize();
            try {
                Files.createDirectories(temporary);
                try (var directory = FSDirectory.open(temporary);
                     var writer = new IndexWriter(directory, writerConfig())) {
                    for (var segment : segments) writer.addDocument(document(segment));
                    writer.commit();
                }
                try (var directory = FSDirectory.open(temporary);
                     var reader = DirectoryReader.open(directory)) {
                    if (reader.numDocs() != segments.size()) throw new IOException("Lucene rebuild count mismatch");
                }
                boolean hadTarget = Files.exists(target);
                if (hadTarget) move(target, backup);
                try { move(temporary, target); }
                catch (Exception exception) {
                    if (hadTarget && Files.exists(backup) && !Files.exists(target)) move(backup, target);
                    throw exception;
                }
                if (Files.exists(backup)) deleteTree(backup);
            } finally {
                if (Files.exists(temporary)) deleteTree(temporary);
            }
        });
    }

    public int count(UUID courseId) {
        var lock = localLock(courseId).readLock(); lock.lock();
        try {
            var path = coursePath(courseId); if (!Files.isDirectory(path)) return 0;
            try (var directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) return 0;
                try (var reader = DirectoryReader.open(directory)) { return reader.numDocs(); }
            }
        } catch (IOException exception) { return -1; }
        finally { lock.unlock(); }
    }

    public Set<UUID> indexedCourseIds() {
        try (var paths = Files.list(root)) {
            var result = new HashSet<UUID>();
            for (var path : paths.filter(Files::isDirectory).toList()) {
                var name = path.getFileName().toString();
                if (name.contains(".rebuild-") || name.contains(".backup-")) continue;
                try { result.add(UUID.fromString(name)); } catch (IllegalArgumentException ignored) { }
            }
            return Set.copyOf(result);
        } catch (IOException exception) { throw new RetryableTaskException("LUCENE_LIST_FAILED", "Lucene 目录清单读取失败"); }
    }

    public Set<UUID> segmentIds(UUID courseId) {
        var lock = localLock(courseId).readLock(); lock.lock();
        try {
            var path = coursePath(courseId); if (!Files.isDirectory(path)) return Set.of();
            try (var directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) return Set.of();
                try (var reader = DirectoryReader.open(directory)) {
                    var result = new HashSet<UUID>();
                    var stored = reader.storedFields(); var liveDocs = MultiBits.getLiveDocs(reader);
                    for (int i = 0; i < reader.maxDoc(); i++) {
                        if (liveDocs == null || liveDocs.get(i))
                            result.add(UUID.fromString(stored.document(i).get("segmentId")));
                    }
                    return Set.copyOf(result);
                }
            }
        } catch (IOException exception) { throw new RetryableTaskException("LUCENE_LIST_FAILED", "Lucene 文档清单读取失败"); }
        finally { lock.unlock(); }
    }

    private List<String> analyze(String value) throws IOException {
        var terms = new ArrayList<String>();
        try (var stream = analyzer.tokenStream(CONTENT, value)) {
            var term = stream.addAttribute(CharTermAttribute.class); stream.reset();
            while (stream.incrementToken()) terms.add(term.toString()); stream.end();
        }
        return terms.stream().distinct().toList();
    }

    private IndexWriterConfig writerConfig() {
        var config = new IndexWriterConfig(analyzer); config.setSimilarity(new BM25Similarity()); return config;
    }

    private Document document(CourseSegment segment) {
        var value = new Document();
        value.add(new StringField("segmentId", segment.getId().toString(), Field.Store.YES));
        value.add(new StringField("userId", segment.getUserId().toString(), Field.Store.NO));
        value.add(new StringField("courseId", segment.getCourseId().toString(), Field.Store.NO));
        value.add(new StringField("materialId", segment.getMaterialId().toString(), Field.Store.NO));
        value.add(new TextField(CONTENT, segment.getContent(), Field.Store.NO));
        return value;
    }

    private void withWriteLock(UUID courseId, IoAction action) {
        var distributed = redisson.getLock("lucene-index:" + courseId);
        var local = localLock(courseId).writeLock();
        distributed.lock(); local.lock();
        try { action.run(); }
        catch (RetryableTaskException exception) { throw exception; }
        catch (Exception exception) { throw new RetryableTaskException("LUCENE_INDEX_FAILED", "Lucene 索引写入失败"); }
        finally {
            local.unlock();
            if (distributed.isHeldByCurrentThread()) distributed.unlock();
        }
    }

    private ReadWriteLock localLock(UUID courseId) {
        return localLocks.computeIfAbsent(courseId, ignored -> new ReentrantReadWriteLock());
    }
    private Path coursePath(UUID courseId) { return root.resolve(courseId.toString()).normalize(); }
    private void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(from, to); }
    }
    private void deleteTree(Path path) throws IOException {
        var normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) throw new IOException("Refusing broad Lucene delete");
        if (!Files.exists(normalized)) return;
        try (var paths = Files.walk(normalized)) {
            for (var candidate : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(candidate);
        }
    }
    @PreDestroy void close() { analyzer.close(); }
    @FunctionalInterface private interface IoAction { void run() throws Exception; }
}
