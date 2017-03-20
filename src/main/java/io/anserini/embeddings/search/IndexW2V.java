/**
 * Anserini: An information retrieval toolkit built on Lucene
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.embeddings.search;

import io.anserini.index.IndexUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.kohsuke.args4j.*;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;
import io.anserini.index.generator.LuceneDocumentGenerator;

public final class IndexW2V {
  private static final Logger LOG = LogManager.getLogger(IndexW2V.class);

  public static final class Args {

    // required arguments

    @Option(name = "-input", metaVar = "[Path]", required = true, usage = "collection path")
    public String input;

    @Option(name = "-index", metaVar = "[Path]", required = true, usage = "index path")
    public String index;

    @Option(name = "-term", metaVar = "String", usage = "get the embedding corresponding to the term")
    public String term = "";
  }

  public final class Counters {
    public AtomicLong indexedDocuments = new AtomicLong();
  }

  private final IndexW2V.Args args;
  private final Path indexPath;
  private final Path collectionPath;
  private final Counters counters;

  public IndexW2V(IndexW2V.Args args) throws Exception {
    this.args = args;

    this.indexPath = Paths.get(args.index);
    if (!Files.exists(this.indexPath)) {
      Files.createDirectories(this.indexPath);
    }

    collectionPath = Paths.get(args.input);
//    if (!Files.exists(collectionPath) || !Files.isReadable(collectionPath) || !Files.isDirectory(collectionPath)) {
//      throw new RuntimeException("Document directory " + collectionPath.toString() +
//          " does not exist or is not readable, please check the path");
//    }

    this.counters = new Counters();
  }

  public void indexEmbeddings() throws IOException, InterruptedException {
    LOG.info("Starting indexer...");

    final Directory dir = FSDirectory.open(indexPath);
    final WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
    final IndexWriterConfig config = new IndexWriterConfig(analyzer);
    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
    config.setUseCompoundFile(false);
    config.setMergeScheduler(new ConcurrentMergeScheduler());

    final IndexWriter writer = new IndexWriter(dir, config);
    Document document = new Document();
    BufferedReader bRdr = new BufferedReader(new FileReader(args.input));
    String line = null;
    bRdr.readLine();
    while ((line = bRdr.readLine()) != null){
      String[] termEmbedding = line.trim().split("\t");
      document.add(new StringField(LuceneDocumentGenerator.FIELD_ID, termEmbedding[0], Field.Store.YES));
      document.add(new StoredField(LuceneDocumentGenerator.FIELD_BODY, termEmbedding[1]));
    }
  }

  public static void main(String[] args) throws Exception {
    IndexW2V.Args indexCollectionArgs = new IndexW2V.Args();
    CmdLineParser parser = new CmdLineParser(indexCollectionArgs, ParserProperties.defaults().withUsageWidth(90));

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.err.println("Example: "+ IndexW2V.class.getSimpleName() +
          parser.printExample(OptionHandlerFilter.REQUIRED));
      return;
    }

    new IndexW2V(indexCollectionArgs).indexEmbeddings();

    if (!indexCollectionArgs.term.isEmpty()) {
      IndexUtils util = new IndexUtils(indexCollectionArgs.index);
      util.getRawDocument(indexCollectionArgs.term);
    }
  }
}
